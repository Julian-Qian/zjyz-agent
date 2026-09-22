import fs from 'node:fs';
import crypto from 'node:crypto';

const baseUrl = process.env.TEST_BASE_URL || 'http://127.0.0.1:8080';

function parseEnv(path) {
  const result = {};
  const text = fs.readFileSync(path, 'utf8');
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const index = line.indexOf('=');
    if (index < 1) continue;
    const key = line.slice(0, index).trim();
    let value = line.slice(index + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

const env = { ...parseEnv('qa/module-tests/.env'), ...process.env };
if (!env.TEST_USER_NAME || !env.TEST_PASSWORD) {
  throw new Error('Missing TEST_USER_NAME/TEST_PASSWORD');
}

let accessToken = '';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function nonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

async function api(path, { method = 'GET', body, timeoutMs = 120000 } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const payload = await response.json();
    if (!response.ok || payload?.succeed !== true) {
      throw new Error(`${method} ${path}: HTTP ${response.status}, ${payload?.returnCode || 'UNKNOWN'} ${payload?.errorMessage || payload?.message || ''}`);
    }
    return payload.data;
  } finally {
    clearTimeout(timer);
  }
}

const unsuccessfulTaskStatuses = new Set(['BLOCKED', 'CANCELLED', 'FAILED']);
const failedRunStatuses = new Set(['FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT']);

async function waitForTask(taskId, label) {
  const deadline = Date.now() + 180000;
  let lastStatus = '';
  while (Date.now() < deadline) {
    const task = await api(`/agent/v2/tasks/${taskId}`);
    const taskStatus = task?.taskStatus;
    const runStatus = task?.run?.status || 'UNKNOWN';
    requireCondition(nonEmptyString(taskStatus), `${label}: taskStatus is missing from task response`);
    const signature = `${taskStatus}/${runStatus}`;
    if (signature !== lastStatus) {
      console.log(`[${label}] status=${signature}`);
      lastStatus = signature;
    }
    if (failedRunStatuses.has(runStatus)) {
      throw new Error(`${label}: run ended as ${runStatus}${task?.run?.errorCode ? ` (${task.run.errorCode})` : ''}`);
    }
    if (unsuccessfulTaskStatuses.has(taskStatus)) {
      throw new Error(`${label}: task ended as ${taskStatus}${task?.run?.errorMessage ? `: ${task.run.errorMessage}` : ''}`);
    }
    if (taskStatus === 'WAITING_USER') {
      throw new Error(`${label}: unexpectedly requested clarification`);
    }
    if (taskStatus === 'COMPLETED') {
      requireCondition(runStatus === 'COMPLETED', `${label}: task completed but run status is ${runStatus}`);
      return task;
    }
    await sleep(1000);
  }
  throw new Error(`${label}: task timeout`);
}

function parseEventNames(text) {
  return Array.from(new Set(String(text || '').split(/\r?\n/)
    .filter((line) => line.startsWith('event:'))
    .map((line) => line.slice(6).trim())
    .filter(Boolean)));
}

async function replayEventsOnce(runId) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  let reader;
  let streamText = '';
  try {
    const response = await fetch(`${baseUrl}/agent/runs/${runId}/events?afterSeq=0`, {
      signal: controller.signal,
      headers: {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${accessToken}`,
      },
    });
    requireCondition(response.ok, `SSE replay ${runId}: HTTP ${response.status}`);
    const contentType = String(response.headers.get('content-type') || '').toLowerCase();
    requireCondition(contentType.includes('text/event-stream'), `SSE replay ${runId}: unexpected content-type ${contentType || '(missing)'}`);
    requireCondition(response.body, `SSE replay ${runId}: response body is missing`);
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        streamText += decoder.decode();
        break;
      }
      streamText += decoder.decode(value, { stream: true });
      const names = parseEventNames(streamText);
      if (names.includes('run.completed') || names.includes('run.failed')) break;
    }
    return { names: parseEventNames(streamText), timedOut: false };
  } catch (error) {
    if (error?.name === 'AbortError') {
      return { names: parseEventNames(streamText), timedOut: true };
    }
    throw error;
  } finally {
    clearTimeout(timer);
    if (reader) await reader.cancel().catch(() => {});
  }
}

async function replayEventNames(runId, label, requiredEvents) {
  const seen = new Set();
  let lastTimedOut = false;
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    const replay = await replayEventsOnce(runId);
    replay.names.forEach((name) => seen.add(name));
    lastTimedOut = replay.timedOut;
    if (seen.has('run.failed')) throw new Error(`${label}: SSE contains run.failed`);
    const missing = requiredEvents.filter((name) => !seen.has(name));
    if (missing.length === 0) return [...seen];
    if (attempt < 5) await sleep(attempt * 250);
  }
  const missing = requiredEvents.filter((name) => !seen.has(name));
  throw new Error(`${label}: SSE replay ${lastTimedOut ? 'timed out and ' : ''}missed ${missing.join(', ')}; seen=${[...seen].join(', ') || '(none)'}`);
}

async function latestAssistant(threadId, runId) {
  const messages = await api(`/agent/threads/${threadId}/messages`);
  requireCondition(Array.isArray(messages), `messages response for ${threadId} is not an array`);
  return [...messages].reverse().find((item) => String(item?.role || '').toLowerCase() === 'assistant' && item.runId === runId);
}

function assertSubmission(submission, threadId, expectedContextTaskId, label) {
  requireCondition(submission && typeof submission === 'object', `${label}: V2 submission is missing`);
  requireCondition(submission.runtimeVersion === 'V2', `${label}: submission runtimeVersion is not V2`);
  for (const field of ['taskId', 'turnId', 'runId', 'threadId', 'workspaceId', 'streamUrl']) {
    requireCondition(nonEmptyString(submission[field]), `${label}: submission.${field} is missing`);
  }
  requireCondition(submission.threadId === threadId, `${label}: submission threadId mismatch`);
  requireCondition(nonEmptyString(submission.status), `${label}: submission run status is missing`);
  requireCondition(nonEmptyString(submission.taskStatus), `${label}: submission taskStatus is missing`);
  requireCondition(submission.streamUrl === `/agent/runs/${submission.runId}/events`, `${label}: submission streamUrl mismatch`);
  requireCondition(submission.eventCursor?.runId === submission.runId, `${label}: submission event cursor runId mismatch`);
  requireCondition(submission.eventCursor?.afterSeq === 0, `${label}: submission event cursor does not start at zero`);
  requireCondition(submission.eventCursor?.eventsUrl === submission.streamUrl, `${label}: submission event cursor URL mismatch`);
  requireCondition(submission.scopeSnapshot && typeof submission.scopeSnapshot === 'object', `${label}: submission scopeSnapshot is missing`);
  if (expectedContextTaskId === null) {
    requireCondition(submission.contextTaskId == null, `${label}: first turn unexpectedly has a context task`);
  } else if (expectedContextTaskId) {
    requireCondition(submission.contextTaskId === expectedContextTaskId, `${label}: context task mismatch`);
  }
}

function assertCompletedTask(task, submission, label, expectations) {
  requireCondition(task?.taskStatus === 'COMPLETED', `${label}: task status is ${task?.taskStatus || 'missing'}`);
  requireCondition(task?.run?.status === 'COMPLETED', `${label}: run status is ${task?.run?.status || 'missing'}`);
  requireCondition(task?.run?.runId === submission.runId, `${label}: task runId mismatch`);
  requireCondition(nonEmptyString(task.dialogueAct) && task.dialogueAct !== 'PENDING', `${label}: dialogueAct was not resolved`);
  requireCondition(nonEmptyString(task.relationType) && task.relationType !== 'PENDING', `${label}: relationType was not resolved`);
  if (expectations.dialogueAct) {
    requireCondition(task.dialogueAct === expectations.dialogueAct, `${label}: expected dialogueAct ${expectations.dialogueAct}, got ${task.dialogueAct}`);
  }
  if (expectations.relationType) {
    requireCondition(task.relationType === expectations.relationType, `${label}: expected relationType ${expectations.relationType}, got ${task.relationType}`);
  }
}

function assertAssistant(assistant, submission, task, label) {
  requireCondition(assistant && typeof assistant === 'object', `${label}: assistant message is missing`);
  requireCondition(String(assistant.role || '').toLowerCase() === 'assistant', `${label}: message role is not assistant`);
  requireCondition(nonEmptyString(assistant.content), `${label}: assistant content is empty`);
  const metadata = assistant.metadata;
  requireCondition(metadata && typeof metadata === 'object' && !Array.isArray(metadata), `${label}: assistant metadata is missing`);
  requireCondition(metadata.runtimeVersion === 'V2', `${label}: assistant metadata runtimeVersion is not V2`);
  requireCondition(metadata.taskId === submission.taskId, `${label}: assistant metadata taskId mismatch`);
  requireCondition(metadata.turnId === submission.turnId, `${label}: assistant metadata turnId mismatch`);
  requireCondition(metadata.interpretation?.dialogueAct === task.dialogueAct, `${label}: assistant interpretation dialogueAct mismatch`);
  requireCondition(metadata.interpretation?.relationType === task.relationType, `${label}: assistant interpretation relationType mismatch`);
}

function assertBusinessEvidence(assistant, events, label) {
  const metadata = assistant.metadata;
  const facts = metadata.facts;
  requireCondition(metadata.evidenceRequired === true, `${label}: assistant did not mark evidence as required`);
  requireCondition(Array.isArray(metadata.usedTools) && metadata.usedTools.length > 0, `${label}: assistant metadata has no used tools`);
  requireCondition(Array.isArray(facts) && facts.length > 0, `${label}: assistant metadata has no facts`);
  requireCondition(facts.every((fact) => fact && typeof fact === 'object' && fact.evidence), `${label}: at least one fact has no evidence`);
  requireCondition(events.includes('tool.completed'), `${label}: SSE has no tool.completed event`);
  requireCondition(events.includes('evidence.available'), `${label}: SSE has no evidence.available event`);
  requireCondition(assistant.content.includes('数据依据：'), `${label}: answer does not expose its data basis`);
}

async function runTurn(threadId, message, label, expectations = {}) {
  const clientRequestId = `local-smoke-${crypto.randomUUID()}`;
  const submission = await api(`/agent/v2/threads/${threadId}/turns`, {
    method: 'POST',
    body: {
      message,
      clientRequestId,
      scopeSelection: {
        selectionMode: 'ALL',
        projectIds: [],
        explicitOverride: label === 'capability',
      },
      context: { source: 'LOCAL_SMOKE' },
      timezone: 'Asia/Shanghai',
    },
  });
  assertSubmission(submission, threadId, expectations.contextTaskId, label);
  console.log(`[${label}] accepted task=${submission.taskId} run=${submission.runId} runtime=${submission.runtimeVersion}`);
  const task = await waitForTask(submission.taskId, label);
  assertCompletedTask(task, submission, label, expectations);
  const assistant = await latestAssistant(threadId, submission.runId);
  assertAssistant(assistant, submission, task, label);
  const requiredEvents = ['assistant.completed', 'task.status', 'run.completed'];
  if (expectations.business) requiredEvents.push('tool.completed', 'evidence.available');
  const events = await replayEventNames(submission.runId, label, requiredEvents);
  requireCondition(!events.includes('run.failed'), `${label}: SSE contains run.failed`);
  if (expectations.business) assertBusinessEvidence(assistant, events, label);
  const facts = assistant.metadata.facts || [];
  console.log(`[${label}] act=${task.dialogueAct} relation=${task.relationType} task=${task.taskStatus} run=${task.run.status} facts=${facts.length}`);
  console.log(`[${label}] events=${events.join(',')}`);
  console.log(`[${label}] answerLength=${assistant.content.length}`);
  return { submission, task, assistant };
}

const login = await api('/user/login', {
  method: 'POST',
  body: { userName: env.TEST_USER_NAME, password: env.TEST_PASSWORD },
  timeoutMs: 30000,
});
accessToken = login.accessToken;
if (!accessToken) throw new Error('Login did not return accessToken');
console.log('[auth] ok');

const capability = await api('/agent/v2/capabilities?selectionMode=ALL');
console.log(`[capabilities] enabled=${capability.enabled} v2Enabled=${capability.v2Enabled} runtime=${capability.runtimeVersion} mode=${capability.mode} available=${(capability.capabilities || []).filter((item) => item.enabled).length}`);
if (!capability.enabled || !capability.v2Enabled || capability.runtimeVersion !== 'V2') {
  throw new Error('V2 capability gate is not enabled for the test account');
}

const workspace = await api('/agent/workspaces', {
  method: 'POST',
  body: { scopeType: 'UNIFIED' },
});
const thread = await api(`/agent/workspaces/${workspace.workspaceId}/threads`, {
  method: 'POST',
  body: { title: `Agent V2 本地验收 ${new Date().toISOString().slice(0, 16)}` },
});
requireCondition(nonEmptyString(workspace?.workspaceId), 'Workspace creation did not return workspaceId');
requireCondition(nonEmptyString(thread?.threadId), 'Thread creation did not return threadId');
console.log(`[workspace] ok thread=${thread.threadId}`);

const capabilityTurn = await runTurn(thread.threadId, '你好，你目前支持哪些能力？', 'capability', {
  contextTaskId: null,
  dialogueAct: 'CAPABILITY_QUERY',
});
const followupTurn = await runTurn(thread.threadId, '然后呢？', 'followup', {
  contextTaskId: capabilityTurn.submission.taskId,
  relationType: 'CONTINUE',
});
await runTurn(thread.threadId, '统计当前全部项目中租入、租出、进行中和已完成的数量。', 'business', {
  contextTaskId: followupTurn.submission.taskId,
  dialogueAct: 'BUSINESS_QUERY',
  business: true,
});

const active = await api(`/agent/v2/threads/${thread.threadId}/active-task`);
requireCondition(active === null, 'Active task was not cleared after terminal runs');
console.log('[active-task] terminalCleared=true');
console.log('[smoke] PASS');
