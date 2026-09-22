import { createSlice } from '@reduxjs/toolkit';
import {
  archiveAgentThread,
  answerAgentInteraction,
  cancelAgentRun,
  cancelAgentTask,
  retryAgentTask,
  createAgentRun,
  createAgentTurn,
  createAgentThread,
  createAgentWorkspace,
  getAgentActiveTask,
  getAgentCapabilities,
  getAgentTask,
  getAgentV2Capabilities,
  getAgentRun,
  listAgentArtifacts,
  listAgentMessages,
  listAgentProjects,
  listAgentThreads,
  streamAgentRun,
  updateAgentThread,
} from '@/api/agent';
import {
  abortableDelay,
  clearPendingAgentRun,
  createAgentRunStatusLatch,
  createRunWithIdempotentRetry,
  getRunWithRecoveryRetry,
  isAgentRunTerminal,
  isRetryableRunCreationError,
  isUnrecoverableAgentRunError,
  readPendingAgentRun,
  writePendingAgentRun,
} from './agentRunReliability';

let activeStreamController = null;
let activeRunSessionId = 0;
let capabilityRequestGeneration = 0;
export const ALL_AGENT_PROJECTS = '__ALL_PROJECTS__';

const normalizedScopeParts = (projectIds = []) => {
  const values = Array.isArray(projectIds) ? projectIds.filter(Boolean) : [];
  const allProjects = values.includes(ALL_AGENT_PROJECTS);
  const explicitProjectIds = values.filter((projectId) => projectId !== ALL_AGENT_PROJECTS).sort();
  return {
    selectionMode: allProjects || !explicitProjectIds.length ? 'ALL' : 'EXPLICIT',
    projectIds: allProjects ? [] : explicitProjectIds,
  };
};

const scopeKeyOf = (selectionMode, projectIds = []) => (
  `${selectionMode || 'ALL'}:${(Array.isArray(projectIds) ? [...projectIds].sort() : []).join(',')}`
);

const selectedScopeKey = (projectIds) => {
  const scope = normalizedScopeParts(projectIds);
  return scopeKeyOf(scope.selectionMode, scope.projectIds);
};

const abortActiveRunSession = () => {
  activeRunSessionId += 1;
  if (activeStreamController) activeStreamController.abort();
  activeStreamController = null;
};

const isCurrentSessionIdentity = (getState, { sessionId, threadId } = {}) => (
  sessionId === activeRunSessionId
  && getState().agent.selectedThreadId === threadId
);

const isCurrentRunSession = (getState, session = {}) => (
  isCurrentSessionIdentity(getState, session) && !session.controller?.signal?.aborted
);

const initialState = {
  capability: { checked: false, enabled: false },
  projects: [],
  selectedProjectIds: [ALL_AGENT_PROJECTS],
  scopeOverridePending: false,
  workspace: null,
  threads: [],
  selectedThreadId: null,
  messages: [],
  artifacts: [],
  quota: null,
  currentRun: null,
  currentTask: null,
  currentInteraction: null,
  runEvents: [],
  streamingContent: '',
  statusMessage: '',
  bootstrapping: false,
  loadingMessages: false,
  sending: false,
  cancelling: false,
  terminalReconcilePending: false,
  error: null,
};

const agentSlice = createSlice({
  name: 'agent',
  initialState,
  reducers: {
    setCapability: (state, action) => { state.capability = action.payload; },
    setProjects: (state, action) => { state.projects = action.payload; },
    setSelectedProjectIds: (state, action) => { state.selectedProjectIds = action.payload; },
    setScopeOverridePending: (state, action) => {
      if (!action.payload) {
        state.scopeOverridePending = false;
        return;
      }
      if (typeof action.payload === 'object') {
        state.scopeOverridePending = {
          threadId: action.payload.threadId || state.selectedThreadId,
          scopeKey: action.payload.scopeKey || selectedScopeKey(state.selectedProjectIds),
        };
        return;
      }
      state.scopeOverridePending = {
        threadId: state.selectedThreadId,
        scopeKey: selectedScopeKey(state.selectedProjectIds),
      };
    },
    consumeScopeOverridePending: (state, action) => {
      const current = state.scopeOverridePending;
      if (!current) return;
      if (current === true || (
        current.threadId === action.payload?.threadId
        && current.scopeKey === action.payload?.scopeKey
      )) state.scopeOverridePending = false;
    },
    setWorkspace: (state, action) => { state.workspace = action.payload; },
    setThreads: (state, action) => { state.threads = action.payload; },
    setSelectedThreadId: (state, action) => { state.selectedThreadId = action.payload; },
    setMessages: (state, action) => { state.messages = action.payload; },
    addMessage: (state, action) => { state.messages.push(action.payload); },
    setMessageDeliveryStatus: (state, action) => {
      const target = state.messages.find((item) => item.clientRequestId === action.payload?.clientRequestId);
      if (target) {
        target.deliveryStatus = action.payload?.status;
        target.optimistic = action.payload?.status !== 'SENT';
      }
    },
    setArtifacts: (state, action) => { state.artifacts = action.payload; },
    setCurrentRun: (state, action) => {
      state.currentRun = action.payload;
      if (action.payload?.quota) state.quota = action.payload.quota;
    },
    setCurrentTask: (state, action) => { state.currentTask = action.payload; },
    mergeCurrentTask: (state, action) => {
      state.currentTask = { ...(state.currentTask || {}), ...(action.payload || {}) };
    },
    setCurrentInteraction: (state, action) => { state.currentInteraction = action.payload || null; },
    setRunStatus: (state, action) => {
      state.currentRun = { ...(state.currentRun || {}), status: action.payload };
    },
    settleTerminalRunView: (state, action) => {
      if (action.payload) state.currentRun = action.payload;
      state.streamingContent = '';
      state.statusMessage = '';
      state.sending = false;
      state.cancelling = false;
      state.terminalReconcilePending = false;
    },
    setStreamingContent: (state, action) => { state.streamingContent = action.payload; },
    appendStreamingContent: (state, action) => { state.streamingContent += action.payload || ''; },
    setStatusMessage: (state, action) => { state.statusMessage = action.payload || ''; },
    setTerminalReconcilePending: (state, action) => { state.terminalReconcilePending = !!action.payload; },
    appendRunEvent: (state, action) => {
      state.runEvents.push(action.payload);
      if (state.runEvents.length > 80) state.runEvents.shift();
    },
    resetRunView: (state) => {
      state.currentRun = null;
      state.currentTask = null;
      state.currentInteraction = null;
      state.runEvents = [];
      state.streamingContent = '';
      state.statusMessage = '';
      state.cancelling = false;
      state.terminalReconcilePending = false;
    },
    resetWorkspaceView: (state) => {
      state.workspace = null;
      state.scopeOverridePending = false;
      state.threads = [];
      state.selectedThreadId = null;
      state.messages = [];
      state.artifacts = [];
      state.quota = null;
      state.currentRun = null;
      state.currentTask = null;
      state.currentInteraction = null;
      state.runEvents = [];
      state.streamingContent = '';
      state.statusMessage = '';
      state.error = null;
      state.sending = false;
      state.cancelling = false;
      state.terminalReconcilePending = false;
    },
    setBootstrapping: (state, action) => { state.bootstrapping = action.payload; },
    setLoadingMessages: (state, action) => { state.loadingMessages = action.payload; },
    setSending: (state, action) => { state.sending = action.payload; },
    setCancelling: (state, action) => { state.cancelling = action.payload; },
    setError: (state, action) => { state.error = action.payload; },
  },
});

export const {
  setCapability,
  setProjects,
  setSelectedProjectIds,
  setScopeOverridePending,
  consumeScopeOverridePending,
  setWorkspace,
  setThreads,
  setSelectedThreadId,
  setMessages,
  addMessage,
  setMessageDeliveryStatus,
  setArtifacts,
  setCurrentRun,
  setCurrentTask,
  mergeCurrentTask,
  setCurrentInteraction,
  setRunStatus,
  settleTerminalRunView,
  setStreamingContent,
  appendStreamingContent,
  setStatusMessage,
  setTerminalReconcilePending,
  appendRunEvent,
  resetRunView,
  resetWorkspaceView,
  setBootstrapping,
  setLoadingMessages,
  setSending,
  setCancelling,
  setError,
} = agentSlice.actions;

const dataOf = (response, fallback) => {
  if (!response?.succeed) {
    const error = new Error(response?.errorMessage || fallback);
    error.returnCode = response?.returnCode;
    throw error;
  }
  return response.data;
};

const newClientRequestId = () => `agent_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;

const endpointUnavailable = (error) => {
  const status = Number(error?.response?.status) || 0;
  const code = error?.returnCode || error?.errorCode || error?.response?.data?.returnCode;
  return status === 404 || status === 405 || ['AGENT_V2_UNAVAILABLE', 'AGT404'].includes(code);
};

const isV2DisabledError = (error) => {
  const code = error?.returnCode
    || error?.errorCode
    || error?.response?.data?.returnCode
    || error?.response?.data?.errorCode;
  return ['AGT_V2_DISABLED', 'V2_DISABLED'].includes(code);
};

const normalizeTaskSnapshot = (value, fallbackRuntimeVersion = 'V2') => {
  if (!value || typeof value !== 'object') return null;
  const envelope = value;
  const source = envelope.task || envelope.currentTask || envelope;
  const taskId = envelope.taskId || source.taskId || source.id;
  if (!taskId) return null;
  const scopeSnapshot = envelope.scopeSnapshot || source.scopeSnapshot || envelope.scope || source.scope;
  return {
    ...source,
    taskId,
    status: envelope.taskStatus || source.taskStatus || source.status || source.state || envelope.status || 'OPEN',
    runtimeVersion: envelope.runtimeVersion || source.runtimeVersion || fallbackRuntimeVersion,
    ...(scopeSnapshot ? { scopeSnapshot } : {}),
  };
};

const normalizeRunTask = (run, fallbackRuntimeVersion = 'V2') => normalizeTaskSnapshot({
  ...(run?.task || {}),
  taskId: run?.taskId || run?.task?.taskId,
  taskStatus: run?.taskStatus || run?.task?.taskStatus || run?.task?.status,
  status: run?.taskStatus || run?.task?.status || run?.status,
  runtimeVersion: run?.runtimeVersion || fallbackRuntimeVersion,
  scopeSnapshot: run?.scopeSnapshot || run?.task?.scopeSnapshot || run?.scope || run?.task?.scope,
}, fallbackRuntimeVersion);

const normalizeActiveTaskRun = (value, task) => {
  if (!value || typeof value !== 'object') return null;
  const source = value.run || value.currentRun || {};
  const runId = source.runId || value.runId || task?.latestRunId;
  if (!runId) return null;
  return {
    ...source,
    runId,
    threadId: source.threadId || value.threadId || task?.threadId,
    status: source.status || value.runStatus || value.status || 'QUEUED',
    runtimeVersion: source.runtimeVersion || value.runtimeVersion || task?.runtimeVersion || 'V2',
  };
};

const activeTaskView = (value) => {
  if (!value || typeof value !== 'object') return null;
  const task = normalizeTaskSnapshot(value);
  const source = value.task || value.currentTask || value;
  return task ? {
    task,
    interaction: value.interaction || value.currentInteraction || source.interaction || null,
    run: normalizeActiveTaskRun(value, task),
  } : null;
};

const loadActiveAgentTask = async (threadId) => {
  try {
    const response = await getAgentActiveTask(threadId);
    const value = dataOf(response, '当前任务加载失败');
    let snapshot = activeTaskView(value);
    if (!snapshot?.task?.taskId) return snapshot;
    try {
      const detailResponse = await getAgentTask(snapshot.task.taskId);
      const detail = activeTaskView(dataOf(detailResponse, '任务详情加载失败'));
      if (detail?.task) {
        snapshot = {
          task: { ...snapshot.task, ...detail.task },
          interaction: detail.interaction || snapshot.interaction,
          run: detail.run || snapshot.run,
        };
      }
    } catch (error) {
      if (!endpointUnavailable(error)) return snapshot;
    }
    return snapshot;
  } catch (error) {
    // The endpoint is optional during the V1/V2 staggered rollout. A missing or
    // temporarily unavailable task view must not prevent durable messages loading.
    return null;
  }
};

const manifestRuntimeVersion = (manifest) => manifest?.runtimeVersion
  || manifest?.runtime?.version
  || manifest?.runtime?.runtimeVersion
  || manifest?.version;

const manifestRuntimeMode = (manifest) => manifest?.mode || manifest?.runtime?.mode;

const v2ModeEnabled = (manifest) => {
  const version = String(manifestRuntimeVersion(manifest) || '').toUpperCase();
  const mode = String(manifestRuntimeMode(manifest) || '').toUpperCase();
  return manifest?.enabled !== false
    && !['DISABLED', 'LEGACY', 'SHADOW'].includes(mode)
    && (version === 'V2' || version.startsWith('2') || mode === 'READ_ONLY' || mode === 'ACTIVE');
};

export const fetchAgentCapabilities = (scope) => async (dispatch, getState) => {
  const generation = ++capabilityRequestGeneration;
  const explicitProjectIds = Array.isArray(scope)
    ? scope.filter((projectId) => projectId && projectId !== ALL_AGENT_PROJECTS)
    : scope ? [scope] : [];
  const allProjects = Array.isArray(scope) && scope.includes(ALL_AGENT_PROJECTS);
  const capabilityScopeKey = scopeKeyOf(
    allProjects || !explicitProjectIds.length ? 'ALL' : 'EXPLICIT',
    explicitProjectIds,
  );
  try {
    const response = await getAgentCapabilities(explicitProjectIds.length === 1 ? explicitProjectIds[0] : undefined);
    const capability = response?.succeed
      ? { checked: true, ...(response.data || {}) }
      : { checked: true, enabled: false, reason: response?.errorMessage };
    if (generation !== capabilityRequestGeneration) return getState().agent.capability;
    if (!capability.enabled) {
      dispatch(setCapability({ ...capability, capabilityScopeKey }));
      return capability;
    }
    let v2 = null;
    try {
      const manifestResponse = await getAgentV2Capabilities({
        selectionMode: allProjects || !explicitProjectIds.length ? 'ALL' : 'EXPLICIT',
        projectIds: explicitProjectIds,
      });
      if (manifestResponse?.succeed) v2 = manifestResponse.data || null;
    } catch (error) {
      // V1 remains available during a staggered frontend/backend rollout.
    }
    const merged = {
      ...capability,
      capabilityScopeKey,
      ...(v2 ? {
        v2,
        runtimeVersion: v2ModeEnabled(v2) ? 'V2' : manifestRuntimeVersion(v2),
        mode: manifestRuntimeMode(v2),
        v2Enabled: v2ModeEnabled(v2),
      } : { v2Enabled: false }),
    };
    if (generation !== capabilityRequestGeneration) return getState().agent.capability;
    dispatch(setCapability(merged));
    return merged;
  } catch (error) {
    const capability = { checked: true, enabled: false, reason: '小云能力检查失败' };
    if (generation !== capabilityRequestGeneration) return getState().agent.capability;
    dispatch(setCapability(capability));
    return capability;
  }
};

export const loadAgentProjects = () => async (dispatch, getState) => {
  dispatch(setBootstrapping(true));
  dispatch(setError(null));
  try {
    const projects = await listAgentProjects();
    dispatch(setProjects(projects));
    const { selectedProjectIds, workspace } = getState().agent;
    if (!selectedProjectIds?.length) {
      dispatch(setSelectedProjectIds([ALL_AGENT_PROJECTS]));
    }
    if (!workspace) {
      await dispatch(openUnifiedAgentWorkspace());
    }
    return projects;
  } catch (error) {
    dispatch(setError(error.message || '项目列表加载失败'));
    return [];
  } finally {
    dispatch(setBootstrapping(false));
  }
};

export const openUnifiedAgentWorkspace = () => async (dispatch) => {
  abortActiveRunSession();
  dispatch(resetWorkspaceView());
  dispatch(setBootstrapping(true));
  try {
    const capability = await dispatch(fetchAgentCapabilities());
    if (!capability.enabled) throw new Error('小云暂未开通');
    const workspace = dataOf(await createAgentWorkspace({ scopeType: 'UNIFIED' }), '工作空间创建失败');
    dispatch(setWorkspace(workspace));
    let threads = dataOf(await listAgentThreads(workspace.workspaceId), '会话列表加载失败') || [];
    if (!threads.length) {
      const thread = dataOf(await createAgentThread(workspace.workspaceId, '新对话'), '会话创建失败');
      threads = [thread];
    }
    dispatch(setThreads(threads));
    await dispatch(selectAgentThread(threads[0].threadId));
    return workspace;
  } catch (error) {
    dispatch(setError(error.message || '小云工作空间加载失败'));
    return null;
  } finally {
    dispatch(setBootstrapping(false));
  }
};

export const refreshAgentThreads = () => async (dispatch, getState) => {
  const generation = activeRunSessionId;
  const workspaceId = getState().agent.workspace?.workspaceId;
  if (!workspaceId) return [];
  const threads = dataOf(await listAgentThreads(workspaceId), '会话列表加载失败') || [];
  if (activeRunSessionId !== generation || getState().agent.workspace?.workspaceId !== workspaceId) return [];
  dispatch(setThreads(threads));
  return threads;
};

const recoveredRunPending = ({ activeTask, retained, threadId }) => {
  const task = activeTask?.task || {};
  const run = activeTask?.run || {};
  const scope = task.scopeSnapshot || task.scope || {};
  const selectionMode = String(
    scope.effectiveSelectionMode || scope.selectionMode || scope.requestedSelectionMode || 'ALL',
  ).toUpperCase() === 'EXPLICIT' ? 'EXPLICIT' : 'ALL';
  const projectIds = selectionMode === 'EXPLICIT' && Array.isArray(scope.projectIds)
    ? scope.projectIds : [];
  const clientRequestId = retained?.clientRequestId || `agent_recover_${task.taskId}`;
  const message = retained?.payload?.message
    || task.taskSpec?.resolvedGoal
    || task.resolvedGoal
    || task.goal
    || '恢复当前任务';
  return {
    ...(retained || {}),
    threadId,
    runId: run.runId,
    taskId: task.taskId,
    runtimeVersion: 'V2',
    // An active run was already accepted by the server. Recovery only monitors that
    // run; it must never replay interaction submission semantics or clear its task.
    requestKind: retained?.requestKind === 'RETRY' ? 'RETRY' : 'TURN',
    clientRequestId,
    createdAt: retained?.createdAt || Date.now(),
    scopeOverride: retained?.scopeOverride === true,
    payload: {
      ...(retained?.payload || {}),
      message,
      clientRequestId,
      selectionMode: retained?.payload?.selectionMode || selectionMode,
      projectIds: retained?.payload?.projectIds || projectIds,
      context: retained?.payload?.context || { taskId: task.taskId, recoveredActiveTask: true },
    },
  };
};

export const selectAgentThread = (threadId) => async (dispatch, getState) => {
  if (!threadId) return;
  abortActiveRunSession();
  const selectionSession = { sessionId: activeRunSessionId, threadId };
  dispatch(setSending(false));
  dispatch(setSelectedThreadId(threadId));
  dispatch(resetRunView());
  dispatch(setLoadingMessages(true));
  dispatch(setError(null));
  let recoveredActiveTask = null;
  try {
    const [messages, artifacts, activeTask] = await Promise.all([
      listAgentMessages(threadId),
      listAgentArtifacts(threadId),
      loadActiveAgentTask(threadId),
    ]);
    if (!isCurrentRunSession(getState, selectionSession)) return;
    dispatch(setMessages(dataOf(messages, '消息加载失败') || []));
    dispatch(setArtifacts(dataOf(artifacts, '产物加载失败') || []));
    recoveredActiveTask = activeTask;
    if (activeTask?.task) dispatch(setCurrentTask(activeTask.task));
    if (activeTask?.interaction) dispatch(setCurrentInteraction(activeTask.interaction));
    if (activeTask?.run) dispatch(setCurrentRun(activeTask.run));
  } catch (error) {
    if (isCurrentRunSession(getState, selectionSession)) {
      dispatch(setError(error.message || '会话加载失败'));
    }
  } finally {
    if (isCurrentRunSession(getState, selectionSession)) dispatch(setLoadingMessages(false));
  }
  if (!isCurrentRunSession(getState, selectionSession)) return;
  let pending = readPendingAgentRun({ threadId });
  if (recoveredActiveTask?.run) {
    if (isAgentRunTerminal(recoveredActiveTask.run.status)) {
      clearPendingAgentRun(threadId);
      pending = null;
    } else {
      pending = recoveredRunPending({ activeTask: recoveredActiveTask, retained: pending, threadId });
      writePendingAgentRun(pending);
    }
  }
  if (pending) dispatch(resumePendingAgentRun(pending));
};

export const addAgentThread = () => async (dispatch, getState) => {
  const workspaceId = getState().agent.workspace?.workspaceId;
  if (!workspaceId) return null;
  try {
    const thread = dataOf(await createAgentThread(workspaceId), '会话创建失败');
    await dispatch(refreshAgentThreads());
    await dispatch(selectAgentThread(thread.threadId));
    return thread;
  } catch (error) {
    dispatch(setError(error.message));
    return null;
  }
};

export const renameAgentThread = (threadId, title) => async (dispatch) => {
  try {
    await updateAgentThread(threadId, title);
    await dispatch(refreshAgentThreads());
    return true;
  } catch (error) {
    dispatch(setError(error.message || '会话重命名失败'));
    return false;
  }
};

export const removeAgentThread = (threadId) => async (dispatch, getState) => {
  try {
    await archiveAgentThread(threadId);
    const threads = await dispatch(refreshAgentThreads());
    const current = getState().agent.selectedThreadId;
    if (current === threadId) {
      if (threads.length) await dispatch(selectAgentThread(threads[0].threadId));
      else await dispatch(addAgentThread());
    }
    return true;
  } catch (error) {
    dispatch(setError(error.message || '会话归档失败'));
    return false;
  }
};

const eventRunStatus = (item) => {
  if (item.event === 'run.completed') return 'COMPLETED';
  if (item.event === 'run.failed') return 'FAILED';
  if (item.event === 'run.status') return item.payload?.status;
  return null;
};

const processRunEvent = (dispatch, item, statusLatch) => {
  const seq = Number(item.id) || 0;
  const event = { seq, type: item.event, payload: item.payload, receivedAt: Date.now() };
  dispatch(appendRunEvent(event));
  if (item.event === 'assistant.delta') dispatch(appendStreamingContent(item.payload?.content || ''));
  if (item.event === 'assistant.status') dispatch(setStatusMessage(item.payload?.message));
  if (['task.resolved', 'task.updated', 'task.interpreted', 'understanding.completed'].includes(item.event)) {
    const payload = item.payload || {};
    const source = payload.task || (payload.taskSpec ? {} : payload);
    const taskSpec = payload.taskSpec || source.taskSpec;
    const interpretation = payload.interpretation || source.interpretation;
    const scopeSnapshot = payload.scopeSnapshot || payload.scope || source.scopeSnapshot || source.scope;
    const taskId = payload.taskId || source.taskId || source.id;
    dispatch(mergeCurrentTask({
      ...source,
      ...(taskSpec ? { ...taskSpec, taskSpec } : {}),
      ...(interpretation ? { interpretation } : {}),
      ...(scopeSnapshot ? { scopeSnapshot } : {}),
      ...(taskId ? { taskId } : {}),
    }));
  }
  if (item.event === 'task.status') {
    const source = item.payload?.task || {};
    const taskId = item.payload?.taskId || source.taskId || source.id;
    const taskStatus = item.payload?.taskStatus || item.payload?.status || source.taskStatus || source.status || source.state;
    dispatch(mergeCurrentTask({
      ...source,
      ...(taskId ? { taskId } : {}),
      ...(taskStatus ? { status: taskStatus } : {}),
      ...((item.payload?.scopeSnapshot || source.scopeSnapshot)
        ? { scopeSnapshot: item.payload?.scopeSnapshot || source.scopeSnapshot } : {}),
    }));
  }
  if (['task.plan', 'plan.created'].includes(item.event)) {
    dispatch(mergeCurrentTask({ plan: item.payload?.plan || item.payload }));
  }
  if (['clarification.required', 'clarification.requested', 'approval.requested'].includes(item.event)) {
    dispatch(setCurrentInteraction(item.payload?.interaction || item.payload));
  }
  if (['clarification.resolved', 'approval.resolved', 'interaction.resolved'].includes(item.event)) {
    dispatch(setCurrentInteraction(null));
  }
  if (item.event === 'capability.resolved') {
    dispatch(mergeCurrentTask({ capability: item.payload?.capability || item.payload }));
  }
  const nextStatus = eventRunStatus(item);
  if (nextStatus && statusLatch.accept(nextStatus)) dispatch(setRunStatus(nextStatus));
  if (item.event === 'run.failed' && statusLatch.getStatus() === 'FAILED') {
    dispatch(setError(item.payload?.message || '小云执行失败'));
  }
  return event;
};

const presentTerminalRunError = (dispatch, run) => {
  if (run?.status === 'FAILED') {
    dispatch(setError(run.errorMessage || '小云执行失败'));
  } else if (run?.status === 'INTERRUPTED') {
    dispatch(setError(run.errorMessage || '小云任务已中断'));
  }
};

const refreshRunThread = async (dispatch, getState, threadId, session) => {
  const isCurrent = () => session
    ? isCurrentRunSession(getState, session)
    : getState().agent.selectedThreadId === threadId;
  if (!isCurrent()) return false;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const [messages, artifacts, activeTask] = await Promise.all([
        listAgentMessages(threadId),
        listAgentArtifacts(threadId),
        loadActiveAgentTask(threadId),
      ]);
      if (!isCurrent()) return false;
      dispatch(setMessages(dataOf(messages, '消息加载失败') || []));
      dispatch(setArtifacts(dataOf(artifacts, '产物加载失败') || []));
      if (activeTask?.task) dispatch(setCurrentTask(activeTask.task));
      if (activeTask?.interaction) dispatch(setCurrentInteraction(activeTask.interaction));
      // Messages and artifacts are the durable terminal result. Thread-list metadata is
      // secondary and must never keep the run UI locked if that independent request stalls.
      Promise.resolve(dispatch(refreshAgentThreads())).catch(() => {});
      return true;
    } catch (error) {
      if (!isCurrent()) return false;
      if (attempt < 2) await abortableDelay(500 * (attempt + 1));
    }
  }
  if (isCurrent()) dispatch(setStatusMessage('任务已结束，回答列表暂未同步；重新进入当前会话会自动恢复'));
  return false;
};

const reconcileTerminalRun = async ({ dispatch, getState, run, threadId, session }) => {
  if (!isCurrentSessionIdentity(getState, session)) return false;
  const alreadyPending = getState().agent.terminalReconcilePending;
  dispatch(setCurrentRun(run));
  dispatch(setTerminalReconcilePending(true));
  dispatch(setSending(false));
  dispatch(setCancelling(false));
  if (!alreadyPending) dispatch(setStatusMessage('任务已结束，正在同步最终回答'));

  const reconciled = await refreshRunThread(dispatch, getState, threadId, session);
  if (!isCurrentSessionIdentity(getState, session)) return false;
  if (reconciled) {
    clearPendingAgentRun(threadId);
    dispatch(settleTerminalRunView(run));
  }
  // On failure refreshRunThread leaves the explicit recovery notice in statusMessage;
  // pending, terminal currentRun and any streamed content remain available for retry.
  presentTerminalRunError(dispatch, run);
  return reconciled;
};

const monitorAgentRun = async ({ dispatch, getState, run, controller, sessionId, threadId }) => {
  const session = { sessionId, threadId, controller };
  const isCurrent = () => isCurrentRunSession(getState, session);
  const latch = createAgentRunStatusLatch(run.status);
  const seenEventSeq = new Set();
  let afterSeq = 0;
  let latestRun = run;
  const handleStreamEvent = (item) => {
    if (!isCurrent()) return;
    // SSE 层心跳只用于保活，不落库、无序号，不进入运行事件视图。
    if (item.event === 'heartbeat') return;
    const seq = Number(item.id) || 0;
    if (seq && (seq <= afterSeq || seenEventSeq.has(seq))) return;
    if (seq) {
      seenEventSeq.add(seq);
      afterSeq = Math.max(afterSeq, seq);
    }
    processRunEvent(dispatch, item, latch);
  };
  const streamTask = (async () => {
    while (isCurrent() && !latch.isTerminal()) {
      try {
        await streamAgentRun(run.runId, {
          afterSeq,
          signal: controller.signal,
          onEvent: handleStreamEvent,
        });
      } catch (error) {
        if (error?.name === 'AbortError') return;
        if (isCurrent()) dispatch(setStatusMessage('事件流暂时中断，正在通过任务状态恢复'));
      }
      if (!latch.isTerminal()) await abortableDelay(500, controller.signal);
    }
  })();

  try {
    while (isCurrent() && !latch.isTerminal()) {
      try {
        const snapshot = dataOf(await getAgentRun(run.runId), '任务状态加载失败');
        if (!isCurrent()) throw new DOMException('Aborted', 'AbortError');
        if (latch.accept(snapshot.status)) {
          latestRun = snapshot;
          dispatch(setCurrentRun(snapshot));
        }
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
        if (isCurrent()) dispatch(setStatusMessage('任务状态连接不稳定，正在自动重试'));
      }
      if (!latch.isTerminal()) await abortableDelay(1200, controller.signal);
    }
    if (!isCurrent()) throw new DOMException('Aborted', 'AbortError');
    try {
      const terminalSnapshot = dataOf(await getAgentRun(run.runId), '任务终态加载失败');
      if (!isCurrent()) throw new DOMException('Aborted', 'AbortError');
      if (latch.accept(terminalSnapshot.status)) {
        latestRun = terminalSnapshot;
        dispatch(setCurrentRun(terminalSnapshot));
      }
    } catch (error) {
      // The terminal SSE event remains authoritative until the next explicit refresh.
    }
    if (isCurrent() && latch.isTerminal() && latestRun?.status !== latch.getStatus()) {
      latestRun = { ...latestRun, status: latch.getStatus() };
      dispatch(setCurrentRun(latestRun));
    }
    return latestRun;
  } finally {
    controller.abort();
    await Promise.allSettled([streamTask]);
  }
};

const executePendingAgentRun = (pending) => async (dispatch, getState) => {
  abortActiveRunSession();
  const sessionId = activeRunSessionId;
  const controller = new AbortController();
  const session = { sessionId, threadId: pending.threadId, controller };
  activeStreamController = controller;
  const current = getState().agent;
  const restoringTerminalResult = current.terminalReconcilePending
    && isAgentRunTerminal(current.currentRun?.status)
    && (!pending.runId || current.currentRun?.runId === pending.runId);
  dispatch(setSending(!restoringTerminalResult));
  dispatch(setError(null));
  let requestAccepted = !!pending.runId;
  try {
    const interactionRequest = pending.requestKind === 'INTERACTION' && !pending.runId;
    const retryRequest = pending.requestKind === 'RETRY' && !pending.runId;
    const v2Payload = {
      message: pending.payload.message,
      clientRequestId: pending.clientRequestId,
      scopeSelection: {
        selectionMode: pending.payload.selectionMode,
        projectIds: pending.payload.projectIds || [],
        explicitOverride: pending.scopeOverride === true,
      },
      context: pending.payload.context || {},
      attachmentIds: pending.payload.attachmentIds || [],
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
    };
    const interactionPayload = {
      answer: pending.interactionAnswer,
      clientRequestId: pending.clientRequestId,
      context: pending.payload.context || {},
    };
    const createRunRequest = retryRequest
      ? (_, payload) => retryAgentTask(pending.retryTaskId, payload)
      : interactionRequest
      ? (_, payload) => answerAgentInteraction(pending.interactionId, payload)
      : pending.runtimeVersion === 'V2'
        ? (threadId, payload) => createAgentTurn(threadId, payload)
        : createAgentRun;
    const createPayload = retryRequest
      ? { clientRequestId: pending.clientRequestId, expectedTaskVersion: pending.expectedTaskVersion }
      : interactionRequest
      ? interactionPayload
      : pending.runtimeVersion === 'V2' ? v2Payload : pending.payload;
    const run = pending.runId
      ? await getRunWithRecoveryRetry({
        runId: pending.runId,
        getRun: getAgentRun,
        unwrap: (response) => dataOf(response, '任务状态加载失败'),
      })
      : await createRunWithIdempotentRetry({
        threadId: pending.threadId,
        payload: createPayload,
        createRun: createRunRequest,
        unwrap: (response) => dataOf(response, '小云任务创建失败'),
      });
    requestAccepted = true;
    if (pending.scopeOverride === true) {
      dispatch(consumeScopeOverridePending({
        threadId: pending.threadId,
        scopeKey: scopeKeyOf(pending.payload.selectionMode, pending.payload.projectIds),
      }));
    }
    dispatch(setMessageDeliveryStatus({ clientRequestId: pending.clientRequestId, status: 'SENT' }));
    const taskSnapshot = normalizeRunTask(run, pending.runtimeVersion);
    writePendingAgentRun({
      ...pending,
      runId: run.runId,
      taskId: taskSnapshot?.taskId || run.taskId || pending.taskId,
    });
    if (!isCurrentRunSession(getState, session)) return run;
    if (interactionRequest || retryRequest) dispatch(resetRunView());
    if (taskSnapshot) dispatch(setCurrentTask({ ...taskSnapshot, threadId: pending.threadId }));
    if (isAgentRunTerminal(run.status)) {
      await reconcileTerminalRun({
        dispatch, getState, run, threadId: pending.threadId, session,
      });
      return run;
    }
    dispatch(setCurrentRun(run));
    const latestRun = await monitorAgentRun({
      dispatch, getState, run, controller, sessionId, threadId: pending.threadId,
    });
    if (!isCurrentSessionIdentity(getState, session)) return latestRun;
    const reconciliationSession = { sessionId, threadId: pending.threadId };
    if (isAgentRunTerminal(latestRun?.status)) {
      await reconcileTerminalRun({
        dispatch, getState, run: latestRun, threadId: pending.threadId, session: reconciliationSession,
      });
    }
    return latestRun;
  } catch (error) {
    if (error?.name !== 'AbortError') {
      if (pending.runtimeVersion === 'V2' && isV2DisabledError(error)) {
        const currentSession = isCurrentRunSession(getState, session);
        const refreshScope = pending.payload.selectionMode === 'ALL'
          ? [ALL_AGENT_PROJECTS] : pending.payload.projectIds;
        if (['INTERACTION', 'RETRY'].includes(pending.requestKind) && !pending.runId && !requestAccepted) {
          clearPendingAgentRun(pending.threadId);
          if (currentSession) {
            dispatch(setCapability({
              ...getState().agent.capability,
              runtimeVersion: 'V1',
              mode: 'LEGACY',
              v2Enabled: false,
            }));
            dispatch(setMessageDeliveryStatus({ clientRequestId: pending.clientRequestId, status: 'FAILED' }));
            dispatch(setStatusMessage('V2 灰度已关闭，请取消当前任务后在兼容模式重新发起'));
            dispatch(setError('V2 灰度已关闭，可取消当前任务后在 V1 兼容模式重新发起'));
            await dispatch(fetchAgentCapabilities(refreshScope));
          }
          return null;
        }
        if (pending.requestKind === 'TURN'
          && !pending.runId
          && !pending.taskId
          && !requestAccepted) {
          const fallbackPending = {
            ...pending,
            runtimeVersion: 'V1',
            requestKind: 'TURN',
          };
          // The V2 backend checks an existing clientRequestId before its dynamic gate.
          // Therefore a definitive DISABLED response means this exact id was not accepted
          // and can be reused safely by V1 without opening a duplicate-request window.
          writePendingAgentRun(fallbackPending);
          if (!currentSession) return null;
          dispatch(setCapability({
            ...getState().agent.capability,
            runtimeVersion: 'V1',
            mode: 'LEGACY',
            v2Enabled: false,
          }));
          dispatch(setStatusMessage('V2 灰度已关闭，正在切换兼容模式继续当前请求'));
          await dispatch(fetchAgentCapabilities(refreshScope));
          if (!isCurrentRunSession(getState, session)) return null;
          return dispatch(executePendingAgentRun(fallbackPending));
        }
        if (currentSession) {
          dispatch(setStatusMessage('已受理的 V2 任务不会降级重发，正在保留原任务恢复信息'));
          await dispatch(fetchAgentCapabilities(refreshScope));
        }
        return null;
      }
      if (pending.requestKind === 'INTERACTION'
        && !pending.runId
        && !requestAccepted
        && endpointUnavailable(error)) {
        const fallbackPending = {
          ...pending,
          requestKind: 'TURN',
          interactionId: null,
          interactionAnswer: null,
          runtimeVersion: 'V2',
          payload: {
            ...pending.payload,
            context: {
              ...(pending.payload.context || {}),
              answer: pending.interactionAnswer,
            },
          },
        };
        writePendingAgentRun(fallbackPending);
        if (isCurrentRunSession(getState, session)) {
          return dispatch(executePendingAgentRun(fallbackPending));
        }
        return null;
      }
      if (!isCurrentRunSession(getState, session)) return null;
      if (isRetryableRunCreationError(error)) {
        dispatch(setStatusMessage('连接暂时中断，任务信息已保留；稍后将自动恢复'));
        setTimeout(() => {
          const state = getState().agent;
          if (!isCurrentRunSession(getState, session) || state.sending) return;
          const retained = readPendingAgentRun({ threadId: pending.threadId });
          if (retained) dispatch(resumePendingAgentRun(retained));
        }, 1500);
      } else if (isUnrecoverableAgentRunError(error)) {
        clearPendingAgentRun(pending.threadId);
        if (!requestAccepted) {
          dispatch(setMessageDeliveryStatus({ clientRequestId: pending.clientRequestId, status: 'FAILED' }));
        }
        dispatch(setError(error.message || '小云服务暂不可用'));
      } else {
        dispatch(setStatusMessage('任务状态暂时无法确认，任务信息已保留；重新进入当前会话会自动恢复'));
      }
    }
    return null;
  } finally {
    if (isCurrentSessionIdentity(getState, session)) {
      activeStreamController = null;
      dispatch(setSending(false));
    }
  }
};

export function resumePendingAgentRun(pending) {
  return async (dispatch, getState) => {
    if (!pending || getState().agent.sending || getState().agent.selectedThreadId !== pending.threadId) return null;
    return dispatch(executePendingAgentRun(pending));
  };
}

/** Retry is a separate idempotent request, never a replay of the original turn. */
export const retryAgentWorkspaceTask = () => async (dispatch, getState) => {
  const state = getState().agent;
  const originalTask = state.currentTask;
  const threadId = state.selectedThreadId;
  if (!threadId || !originalTask?.taskId || state.sending || state.cancelling || state.terminalReconcilePending
      || !['BLOCKED', 'CANCELLED'].includes(originalTask.status)
      || originalTask.runtimeVersion !== 'V2') return null;
  const retained = readPendingAgentRun({ threadId });
  if (retained) return dispatch(executePendingAgentRun(retained));
  dispatch(setSending(true));
  dispatch(setError(null));
  try {
    const fresh = normalizeTaskSnapshot(dataOf(await getAgentTask(originalTask.taskId), '任务状态加载失败'));
    if (getState().agent.selectedThreadId !== threadId) return null;
    if (!fresh || !['BLOCKED', 'CANCELLED'].includes(fresh.status)
        || !Number.isInteger(fresh.version) || fresh.version < 1) {
      throw new Error('任务状态已变化，请刷新后重试');
    }
    const scope = fresh.scopeSnapshot || {};
    const selectionMode = (scope.effectiveSelectionMode || scope.selectionMode) === 'EXPLICIT' ? 'EXPLICIT' : 'ALL';
    const clientRequestId = newClientRequestId();
    const pending = {
      threadId, taskId: originalTask.taskId, retryTaskId: originalTask.taskId,
      expectedTaskVersion: fresh.version, requestKind: 'RETRY', runtimeVersion: 'V2',
      clientRequestId, createdAt: Date.now(), scopeOverride: false,
      payload: {
        message: fresh.taskSpec?.resolvedGoal || fresh.resolvedGoal || fresh.goal || '重新尝试原任务',
        clientRequestId, selectionMode,
        projectIds: selectionMode === 'EXPLICIT' ? (scope.projectIds || []) : [],
      },
    };
    writePendingAgentRun(pending);
    return await dispatch(executePendingAgentRun(pending));
  } catch (error) {
    if (getState().agent.selectedThreadId === threadId) dispatch(setError(error.message || '无法重新尝试任务'));
    return null;
  } finally {
    if (getState().agent.selectedThreadId === threadId) dispatch(setSending(false));
  }
};

export const sendAgentWorkspaceMessage = (value, context = {}, attachmentIds = []) => async (dispatch, getState) => {
  const content = (value || '').trim();
  const {
    selectedThreadId, selectedProjectIds, scopeOverridePending, sending, terminalReconcilePending, capability,
  } = getState().agent;
  if (!content || !selectedThreadId || sending || terminalReconcilePending) return null;
  const scope = normalizedScopeParts(selectedProjectIds);
  const scopeKey = scopeKeyOf(scope.selectionMode, scope.projectIds);
  const ownedScopeOverride = scopeOverridePending === true || (
    scopeOverridePending?.threadId === selectedThreadId
    && scopeOverridePending?.scopeKey === scopeKey
  );
  const clientRequestId = newClientRequestId();
  dispatch(resetRunView());
  dispatch(addMessage({
    role: 'user',
    content,
    clientRequestId,
    createdAt: new Date().toISOString(),
    optimistic: true,
    deliveryStatus: 'SENDING',
  }));
  const payload = {
    message: content,
    clientRequestId,
    selectionMode: scope.selectionMode,
    projectIds: scope.projectIds,
    attachmentIds: Array.isArray(attachmentIds) ? attachmentIds : [],
    context,
  };
  const pending = {
    threadId: selectedThreadId,
    clientRequestId,
    createdAt: Date.now(),
    payload,
    requestKind: 'TURN',
    runtimeVersion: capability?.v2Enabled ? 'V2' : 'V1',
    scopeOverride: ownedScopeOverride || context?.explicitScopeOverride === true,
  };
  writePendingAgentRun(pending);
  return dispatch(executePendingAgentRun(pending));
};

export const answerAgentWorkspaceInteraction = (option) => async (dispatch, getState) => {
  const state = getState().agent;
  const interaction = state.currentInteraction || {};
  const interactionId = interaction.interactionId || interaction.id;
  const label = typeof option === 'string' ? option : option?.label;
  const value = typeof option === 'string' ? option : (option?.value || option?.label);
  if (!interactionId || !label || !state.selectedThreadId || state.sending || state.terminalReconcilePending) return null;

  const clientRequestId = newClientRequestId();
  const selectionMode = state.selectedProjectIds?.includes(ALL_AGENT_PROJECTS) ? 'ALL' : 'EXPLICIT';
  const projectIds = selectionMode === 'ALL' ? [] : (state.selectedProjectIds || []);
  const context = {
    interactionId,
    parentTaskId: state.currentTask?.taskId,
    taskId: state.currentTask?.taskId,
  };
  const pending = {
    threadId: state.selectedThreadId,
    taskId: state.currentTask?.taskId,
    interactionId,
    interactionAnswer: { value, label },
    requestKind: 'INTERACTION',
    clientRequestId,
    createdAt: Date.now(),
    runtimeVersion: 'V2',
    scopeOverride: false,
    payload: {
      message: label,
      clientRequestId,
      selectionMode,
      projectIds,
      context,
    },
  };
  dispatch(addMessage({
    role: 'user',
    content: label,
    clientRequestId,
    createdAt: new Date().toISOString(),
    optimistic: true,
    deliveryStatus: 'SENDING',
  }));
  writePendingAgentRun(pending);
  return dispatch(executePendingAgentRun(pending));
};

const AGENT_TASK_TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED']);
const isAgentTaskTerminal = (status) => AGENT_TASK_TERMINAL_STATUSES.has(String(status || '').toUpperCase());

const runFromTaskCancelEnvelope = (value, currentRun) => {
  if (value?.run && typeof value.run === 'object') return value.run;
  if (value?.currentRun && typeof value.currentRun === 'object') return value.currentRun;
  if (!value?.runId) return currentRun || null;
  const envelopeCarriesTask = !!value.task || !!value.taskId || !!value.taskStatus;
  return {
    ...(currentRun || {}),
    runId: value.runId,
    ...(value.threadId ? { threadId: value.threadId } : {}),
    status: value.runStatus || (!envelopeCarriesTask ? value.status : currentRun?.status) || 'FINALIZING',
  };
};

export const stopAgentRun = () => async (dispatch, getState) => {
  const initialState = getState().agent;
  const currentRun = initialState.currentRun;
  const currentTask = initialState.currentTask;
  const runId = currentRun?.runId;
  const taskId = currentTask?.taskId;
  const threadIdAtRequest = initialState.selectedThreadId;
  const stopSession = { sessionId: activeRunSessionId, threadId: threadIdAtRequest };
  const preferTaskCancel = !!taskId
    && !isAgentTaskTerminal(currentTask?.status)
    && (String(currentTask?.runtimeVersion || '').toUpperCase() === 'V2' || initialState.capability?.v2Enabled);
  if ((!runId && !preferTaskCancel) || initialState.cancelling) return;
  if (!preferTaskCancel && currentRun?.status === 'FINALIZING') {
    dispatch(setStatusMessage('任务正在收尾，已无法安全取消，将继续跟踪最终状态'));
    return;
  }
  dispatch(setCancelling(true));
  dispatch(setStatusMessage('正在请求停止任务'));
  try {
    let cancelValue;
    let usedTaskCancel = false;
    if (preferTaskCancel) {
      try {
        cancelValue = dataOf(await cancelAgentTask(taskId), '取消任务失败');
        usedTaskCancel = true;
      } catch (error) {
        if (!endpointUnavailable(error) || !runId) throw error;
      }
    }
    if (!usedTaskCancel) cancelValue = dataOf(await cancelAgentRun(runId), '取消任务失败');
    if (!isCurrentRunSession(getState, stopSession)) return;
    if (usedTaskCancel) {
      const taskSnapshot = normalizeTaskSnapshot(cancelValue, currentTask?.runtimeVersion || 'V2');
      if (taskSnapshot) dispatch(setCurrentTask(taskSnapshot));
      if (isAgentTaskTerminal(taskSnapshot?.status)) dispatch(setCurrentInteraction(null));
    }
    const run = usedTaskCancel ? runFromTaskCancelEnvelope(cancelValue, currentRun) : cancelValue;
    if (!run) {
      if (isAgentTaskTerminal(getState().agent.currentTask?.status)) {
        clearPendingAgentRun(threadIdAtRequest);
        dispatch(setStatusMessage('当前任务已取消'));
      }
      return;
    }
    dispatch(setCurrentRun(run));
    if (isAgentRunTerminal(run.status)) {
      if (activeStreamController) activeStreamController.abort();
      const threadId = run.threadId || currentRun?.threadId || getState().agent.selectedThreadId;
      await reconcileTerminalRun({ dispatch, getState, run, threadId, session: stopSession });
    } else {
      dispatch(setStatusMessage('任务已进入收尾阶段，将继续跟踪到最终状态'));
      if (!activeStreamController || activeStreamController.signal.aborted) {
        const threadId = run.threadId || currentRun?.threadId || getState().agent.selectedThreadId;
        const pending = readPendingAgentRun({ threadId });
        if (pending) {
          dispatch(setCancelling(false));
          dispatch(executePendingAgentRun({ ...pending, runId }));
        }
      }
    }
  } catch (error) {
    if (!isCurrentRunSession(getState, stopSession)) return;
    dispatch(setStatusMessage('停止请求暂未确认，将继续跟踪任务最终状态'));
    if (!activeStreamController || activeStreamController.signal.aborted) {
      const threadId = currentRun?.threadId || getState().agent.selectedThreadId;
      const pending = readPendingAgentRun({ threadId });
      if (pending) {
        dispatch(setCancelling(false));
        dispatch(executePendingAgentRun({ ...pending, runId }));
      }
    }
  } finally {
    if (isCurrentRunSession(getState, stopSession)) dispatch(setCancelling(false));
  }
};

export default agentSlice.reducer;
