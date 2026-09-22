import apiConfig from '@/config/api.config';
import { getToken, request } from '@/utils';
import { refreshSession } from '@/utils/request';

const unwrapProjectList = (response, businessType) => {
  const items = response?.data?.projectBriefInfos || [];
  return items.map((item) => ({
    ...item,
    projectId: item.projectId,
    projectName: item.projectName || '未命名项目',
    projectBusinessType: item.projectBusinessType || businessType,
  }));
};

export function chatWithAgent(payload) {
  return request({ url: '/agent/chat', method: 'POST', data: payload });
}

export function getAgentHealth() {
  return request({ url: '/agent/health', method: 'GET' });
}

export function getAgentCapabilities(projectId) {
  return request({
    url: '/agent/capabilities',
    method: 'GET',
    params: projectId ? { projectId } : undefined,
  });
}

export function getAgentV2Capabilities({ scopeMode, selectionMode, projectIds } = {}) {
  const params = {};
  if (selectionMode || scopeMode) params.selectionMode = selectionMode || scopeMode;
  if (Array.isArray(projectIds) && projectIds.length) params.projectIds = projectIds.join(',');
  return request({
    url: '/agent/v2/capabilities',
    method: 'GET',
    params: Object.keys(params).length ? params : undefined,
  });
}

export async function listAgentProjects() {
  const load = (projectBusinessType) => request({
    url: '/project/queryProjectList',
    method: 'GET',
    params: { pageNum: 1, pageSize: 1000, projectBusinessType },
  });
  const [rentOut, rentIn] = await Promise.all([load('rent_out'), load('rent_in')]);
  const merged = [
    ...unwrapProjectList(rentOut, 'rent_out'),
    ...unwrapProjectList(rentIn, 'rent_in'),
  ];
  return Array.from(new Map(merged.map((item) => [item.projectId, item])).values());
}

export function createAgentWorkspace(payload) {
  const data = typeof payload === 'string'
    ? { scopeType: 'PROJECT', projectId: payload }
    : payload;
  return request({ url: '/agent/workspaces', method: 'POST', data });
}

export function listAgentThreads(workspaceId) {
  return request({ url: `/agent/workspaces/${workspaceId}/threads`, method: 'GET' });
}

export function createAgentThread(workspaceId, title = '新对话') {
  return request({ url: `/agent/workspaces/${workspaceId}/threads`, method: 'POST', data: { title } });
}

export function updateAgentThread(threadId, title) {
  return request({ url: `/agent/threads/${threadId}`, method: 'PATCH', data: { title } });
}

export function archiveAgentThread(threadId) {
  return request({ url: `/agent/threads/${threadId}`, method: 'DELETE' });
}

export function listAgentMessages(threadId) {
  return request({ url: `/agent/threads/${threadId}/messages`, method: 'GET' });
}

export function createAgentRun(threadId, payload) {
  return request({
    url: `/agent/threads/${threadId}/runs`,
    method: 'POST',
    data: payload,
    timeout: 120000,
  });
}

export function createAgentTurn(threadId, payload) {
  return request({
    url: `/agent/v2/threads/${threadId}/turns`,
    method: 'POST',
    data: payload,
    timeout: 120000,
  });
}

export function retryAgentTask(taskId, payload) {
  return request({ url: `/agent/v2/tasks/${encodeURIComponent(taskId)}/retry`, method: 'POST', data: payload, timeout: 120000 });
}

export function getAgentTask(taskId) {
  return request({ url: `/agent/v2/tasks/${taskId}`, method: 'GET' });
}

export function getAgentActiveTask(threadId) {
  return request({ url: `/agent/v2/threads/${threadId}/active-task`, method: 'GET' });
}

export function cancelAgentTask(taskId) {
  return request({ url: `/agent/v2/tasks/${taskId}/cancel`, method: 'POST' });
}

export function answerAgentInteraction(interactionId, payload) {
  return request({
    url: `/agent/v2/interactions/${interactionId}/answer`,
    method: 'POST',
    data: payload,
    timeout: 120000,
  });
}

export function getAgentRun(runId) {
  return request({ url: `/agent/runs/${runId}`, method: 'GET' });
}

export function cancelAgentRun(runId) {
  return request({ url: `/agent/runs/${runId}/cancel`, method: 'POST' });
}

export function listAgentArtifacts(threadId) {
  return request({ url: `/agent/threads/${threadId}/artifacts`, method: 'GET' });
}

export async function downloadAgentArtifact(artifactId, fallbackTitle = '小云产物') {
  const token = getToken();
  const baseUrl = (apiConfig.baseURL || '').replace(/\/$/, '');
  const response = await fetch(`${baseUrl}/agent/artifacts/${artifactId}/download`, {
    method: 'GET',
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  const contentType = response.headers.get('content-type') || '';
  if (!response.ok || contentType.includes('application/json')) {
    let errorMessage = response.status === 401 ? '登录状态已失效，请重新登录' : '产物下载失败';
    try {
      const errorPayload = await response.json();
      errorMessage = errorPayload?.errorMessage || errorPayload?.message || errorMessage;
    } catch (error) {
      // Keep the safe fallback when the server did not return a JSON error body.
    }
    throw new Error(errorMessage);
  }
  const disposition = response.headers.get('content-disposition') || '';
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  let filename = `${fallbackTitle}.xlsx`;
  if (encodedName) {
    try { filename = decodeURIComponent(encodedName); } catch (error) { filename = `${fallbackTitle}.xlsx`; }
  }
  return { blob: await response.blob(), filename };
}

const parseSseBlock = (block) => {
  let event = 'message';
  let id = '';
  const data = [];
  block.split('\n').forEach((line) => {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    if (line.startsWith('id:')) id = line.slice(3).trim();
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  });
  const raw = data.join('\n');
  let payload = raw;
  try {
    payload = raw ? JSON.parse(raw) : {};
  } catch (error) {
    payload = { content: raw };
  }
  return { event, id, payload };
};

const streamAgentEvents = async (url, { signal, onEvent }) => {
  const baseUrl = (apiConfig.baseURL || '').replace(/\/$/, '');
  const openStream = () => {
    const token = getToken();
    return fetch(`${baseUrl}${url}`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      signal,
    });
  };

  let response = await openStream();
  let contentType = response.headers.get('content-type') || '';
  if (response.status === 401 || contentType.includes('application/json')) {
    let payload = null;
    try { payload = await response.json(); } catch (error) { payload = null; }
    if (response.status === 401 || payload?.returnCode === 'AUTH401') {
      await refreshSession();
      response = await openStream();
      contentType = response.headers.get('content-type') || '';
    }
  }
  if (!response.ok || !response.body || !contentType.includes('text/event-stream')) {
    const error = new Error(response.status === 401 ? '登录状态已失效，请重新登录' : '无法连接小云事件流');
    error.status = response.status;
    throw error;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n');
    const blocks = buffer.split('\n\n');
    buffer = blocks.pop() || '';
    blocks.filter(Boolean).forEach((block) => onEvent?.(parseSseBlock(block)));
    if (done) break;
  }
  if (buffer.trim()) onEvent?.(parseSseBlock(buffer));
};

export async function streamAgentRun(runId, { afterSeq = 0, signal, onEvent }) {
  return streamAgentEvents(`/agent/runs/${runId}/events?afterSeq=${afterSeq}`, { signal, onEvent });
}
