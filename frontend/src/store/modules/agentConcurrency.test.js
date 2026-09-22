import { configureStore } from '@reduxjs/toolkit';
import {
  cancelAgentRun,
  getAgentRun,
  listAgentArtifacts,
  listAgentMessages,
  listAgentThreads,
  streamAgentRun,
} from '@/api/agent';
import agentReducer, {
  refreshAgentThreads,
  resumePendingAgentRun,
  selectAgentThread,
  setCurrentRun,
  setSelectedThreadId,
  setSending,
  setStatusMessage,
  setStreamingContent,
  setTerminalReconcilePending,
  setThreads,
  setWorkspace,
  stopAgentRun,
} from './agent';
import { readPendingAgentRun, writePendingAgentRun } from './agentRunReliability';

jest.mock('@/api/agent', () => ({
  archiveAgentThread: jest.fn(),
  cancelAgentRun: jest.fn(),
  createAgentRun: jest.fn(),
  createAgentThread: jest.fn(),
  createAgentWorkspace: jest.fn(),
  getAgentCapabilities: jest.fn(),
  getAgentRun: jest.fn(),
  listAgentArtifacts: jest.fn(),
  listAgentMessages: jest.fn(),
  listAgentProjects: jest.fn(),
  listAgentThreads: jest.fn(),
  streamAgentRun: jest.fn(),
  updateAgentThread: jest.fn(),
}), { virtual: true });

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

const pending = (threadId, runId) => ({
  threadId,
  runId,
  clientRequestId: `request-${threadId}`,
  createdAt: Date.now(),
  payload: {
    message: `查询${threadId}`,
    clientRequestId: `request-${threadId}`,
    selectionMode: 'ALL',
    projectIds: [],
  },
});

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  jest.clearAllMocks();
  window.sessionStorage.clear();
});

test('late thread A run cannot overwrite B and stop cancels only B', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const lateA = deferred();
  const laterBPoll = deferred();
  let firstBLookup = true;
  getAgentRun.mockImplementation((runId) => {
    if (runId === 'run-a') return lateA.promise;
    if (firstBLookup) {
      firstBLookup = false;
      return Promise.resolve({ succeed: true, data: { runId: 'run-b', threadId: 'thread-b', status: 'RUNNING' } });
    }
    return laterBPoll.promise;
  });
  listAgentMessages.mockResolvedValue({ succeed: true, data: [{ role: 'user', content: 'B消息' }] });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [{ artifactId: 'artifact-b' }] });
  streamAgentRun.mockImplementation((runId, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  }));
  cancelAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-b', threadId: 'thread-b', status: 'CANCELLED' },
  });

  store.dispatch(setSelectedThreadId('thread-a'));
  const aExecution = store.dispatch(resumePendingAgentRun(pending('thread-a', 'run-a')));
  writePendingAgentRun(pending('thread-b', 'run-b'));
  await store.dispatch(selectAgentThread('thread-b'));
  await flushPromises();
  expect(store.getState().agent.currentRun?.runId).toBe('run-b');

  lateA.resolve({ succeed: true, data: { runId: 'run-a', threadId: 'thread-a', status: 'COMPLETED' } });
  await aExecution;
  expect(store.getState().agent.selectedThreadId).toBe('thread-b');
  expect(store.getState().agent.currentRun?.runId).toBe('run-b');
  expect(store.getState().agent.messages).toEqual([{ role: 'user', content: 'B消息' }]);

  await store.dispatch(stopAgentRun());
  expect(cancelAgentRun).toHaveBeenCalledTimes(1);
  expect(cancelAgentRun).toHaveBeenCalledWith('run-b');
  expect(store.getState().agent.currentRun?.status).toBe('CANCELLED');
});

test('late thread selection payload cannot overwrite the currently selected thread', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const lateAMessages = deferred();
  const lateAArtifacts = deferred();
  listAgentMessages.mockImplementation((threadId) => threadId === 'thread-a'
    ? lateAMessages.promise : Promise.resolve({ succeed: true, data: [{ content: 'B消息' }] }));
  listAgentArtifacts.mockImplementation((threadId) => threadId === 'thread-a'
    ? lateAArtifacts.promise : Promise.resolve({ succeed: true, data: [{ artifactId: 'artifact-b' }] }));

  const selectingA = store.dispatch(selectAgentThread('thread-a'));
  await store.dispatch(selectAgentThread('thread-b'));
  lateAMessages.resolve({ succeed: true, data: [{ content: 'A消息' }] });
  lateAArtifacts.resolve({ succeed: true, data: [{ artifactId: 'artifact-a' }] });
  await selectingA;

  expect(store.getState().agent.selectedThreadId).toBe('thread-b');
  expect(store.getState().agent.messages).toEqual([{ content: 'B消息' }]);
  expect(store.getState().agent.artifacts).toEqual([{ artifactId: 'artifact-b' }]);
});

test('FINALIZING run is not sent to the cancellation endpoint', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  store.dispatch(setSelectedThreadId('thread-b'));
  store.dispatch(setCurrentRun({ runId: 'run-b', threadId: 'thread-b', status: 'FINALIZING' }));

  await store.dispatch(stopAgentRun());

  expect(cancelAgentRun).not.toHaveBeenCalled();
  expect(store.getState().agent.statusMessage).toContain('正在收尾');
});

test('monitor self-abort after terminal status still reconciles messages and artifacts', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  getAgentRun
    .mockResolvedValueOnce({ succeed: true, data: { runId: 'run-a', threadId: 'thread-a', status: 'RUNNING' } })
    .mockResolvedValue({ succeed: true, data: { runId: 'run-a', threadId: 'thread-a', status: 'COMPLETED' } });
  streamAgentRun.mockImplementation((runId, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  }));
  listAgentMessages.mockResolvedValue({ succeed: true, data: [{ role: 'assistant', content: '终态回答' }] });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [{ artifactId: 'artifact-a' }] });

  store.dispatch(setSelectedThreadId('thread-a'));
  writePendingAgentRun(pending('thread-a', 'run-a'));
  await store.dispatch(resumePendingAgentRun(pending('thread-a', 'run-a')));

  expect(store.getState().agent.sending).toBe(false);
  expect(store.getState().agent.currentRun?.status).toBe('COMPLETED');
  expect(store.getState().agent.messages).toEqual([{ role: 'assistant', content: '终态回答' }]);
  expect(store.getState().agent.artifacts).toEqual([{ artifactId: 'artifact-a' }]);
  expect(window.sessionStorage.length).toBe(0);
});

test('refresh recovery unlocks a terminal run without waiting for thread metadata refresh', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const stalledThreadRefresh = deferred();
  const durableMessages = [
    { messageId: 'user-1', role: 'user', content: '列出当前选中的项目清单' },
    {
      messageId: 'assistant-1',
      role: 'assistant',
      content: '当前选中项目共 1 个。',
      metadata: { cards: [{ type: 'project-list', total: 1 }] },
    },
  ];
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-refresh', threadId: 'thread-refresh', status: 'COMPLETED' },
  });
  listAgentMessages.mockResolvedValue({ succeed: true, data: durableMessages });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });
  listAgentThreads.mockReturnValue(stalledThreadRefresh.promise);

  store.dispatch(setWorkspace({ workspaceId: 'workspace-1' }));
  store.dispatch(setSelectedThreadId('thread-refresh'));
  store.dispatch(setStreamingContent('正在分析任务并调用业务工具…'));
  store.dispatch(setStatusMessage('正在工作'));
  const retained = pending('thread-refresh', 'run-refresh');
  writePendingAgentRun(retained);

  await store.dispatch(resumePendingAgentRun(retained));

  const state = store.getState().agent;
  expect(state.currentRun).toEqual({
    runId: 'run-refresh', threadId: 'thread-refresh', status: 'COMPLETED',
  });
  expect(state.sending).toBe(false);
  expect(state.terminalReconcilePending).toBe(false);
  expect(state.streamingContent).toBe('');
  expect(state.statusMessage).toBe('');
  expect(state.messages).toEqual(durableMessages);
  expect(state.messages.filter((item) => item.role === 'assistant')).toHaveLength(1);
  expect(window.sessionStorage.length).toBe(0);

  stalledThreadRefresh.resolve({ succeed: true, data: [] });
  await flushPromises();
});

test('terminal reconcile failure keeps recovery state and a later resume completes it', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const retained = pending('thread-retry', 'run-retry');
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-retry', threadId: 'thread-retry', status: 'COMPLETED' },
  });
  listAgentMessages.mockRejectedValue(new Error('messages unavailable'));
  listAgentArtifacts.mockRejectedValue(new Error('artifacts unavailable'));

  store.dispatch(setSelectedThreadId('thread-retry'));
  store.dispatch(setStreamingContent('已收到的流式回答'));
  store.dispatch(setStatusMessage('正在工作'));
  writePendingAgentRun(retained);

  await store.dispatch(resumePendingAgentRun(retained));

  let state = store.getState().agent;
  expect(state.currentRun?.status).toBe('COMPLETED');
  expect(state.sending).toBe(false);
  expect(state.terminalReconcilePending).toBe(true);
  expect(state.streamingContent).toBe('已收到的流式回答');
  expect(state.statusMessage).toContain('回答列表暂未同步');
  expect(readPendingAgentRun({ threadId: 'thread-retry' })?.runId).toBe('run-retry');

  const durableMessages = [
    { messageId: 'user-retry', role: 'user', content: '查询项目清单' },
    { messageId: 'assistant-retry', role: 'assistant', content: '最终持久回答' },
  ];
  listAgentMessages.mockResolvedValue({ succeed: true, data: durableMessages });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [{ artifactId: 'artifact-retry' }] });

  await store.dispatch(resumePendingAgentRun(readPendingAgentRun({ threadId: 'thread-retry' })));

  state = store.getState().agent;
  expect(state.currentRun?.status).toBe('COMPLETED');
  expect(state.sending).toBe(false);
  expect(state.terminalReconcilePending).toBe(false);
  expect(state.streamingContent).toBe('');
  expect(state.statusMessage).toBe('');
  expect(state.messages).toEqual(durableMessages);
  expect(state.artifacts).toEqual([{ artifactId: 'artifact-retry' }]);
  expect(readPendingAgentRun({ threadId: 'thread-retry' })).toBeNull();
});

test.each([
  ['FAILED', '模型服务失败'],
  ['INTERRUPTED', '任务超时中断'],
])('direct terminal %s recovery presents its backend error', async (status, errorMessage) => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const threadId = `thread-${status.toLowerCase()}`;
  const runId = `run-${status.toLowerCase()}`;
  const retained = pending(threadId, runId);
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId, threadId, status, errorMessage },
  });
  listAgentMessages.mockResolvedValue({ succeed: true, data: [{ role: 'user', content: '原始问题' }] });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });

  store.dispatch(setSelectedThreadId(threadId));
  writePendingAgentRun(retained);
  await store.dispatch(resumePendingAgentRun(retained));

  expect(store.getState().agent.currentRun?.status).toBe(status);
  expect(store.getState().agent.error).toBe(errorMessage);
  expect(store.getState().agent.sending).toBe(false);
  expect(readPendingAgentRun({ threadId })).toBeNull();
});

test('same workspace id cannot accept a thread list from an older generation', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const staleThreads = deferred();
  listAgentThreads.mockReturnValue(staleThreads.promise);
  listAgentMessages.mockResolvedValue({ succeed: true, data: [] });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });

  store.dispatch(setWorkspace({ workspaceId: 'workspace-same' }));
  store.dispatch(setThreads([{ threadId: 'thread-current' }]));
  const oldRefresh = store.dispatch(refreshAgentThreads());
  await store.dispatch(selectAgentThread('thread-current'));

  staleThreads.resolve({ succeed: true, data: [{ threadId: 'thread-stale' }] });
  await oldRefresh;

  expect(store.getState().agent.workspace?.workspaceId).toBe('workspace-same');
  expect(store.getState().agent.threads).toEqual([{ threadId: 'thread-current' }]);
});

test('cancel terminal reconcile failure keeps a visible recoverable result', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const retained = pending('thread-cancel-fail', 'run-cancel-fail');
  cancelAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-cancel-fail', threadId: 'thread-cancel-fail', status: 'CANCELLED' },
  });
  listAgentMessages.mockRejectedValue(new Error('messages unavailable'));
  listAgentArtifacts.mockRejectedValue(new Error('artifacts unavailable'));

  store.dispatch(setSelectedThreadId('thread-cancel-fail'));
  store.dispatch(setCurrentRun({
    runId: 'run-cancel-fail', threadId: 'thread-cancel-fail', status: 'RUNNING',
  }));
  store.dispatch(setSending(true));
  store.dispatch(setStreamingContent('取消前已有的回答'));
  writePendingAgentRun(retained);

  await store.dispatch(stopAgentRun());

  const state = store.getState().agent;
  expect(state.currentRun?.status).toBe('CANCELLED');
  expect(state.sending).toBe(false);
  expect(state.cancelling).toBe(false);
  expect(state.terminalReconcilePending).toBe(true);
  expect(state.streamingContent).toBe('取消前已有的回答');
  expect(state.statusMessage).toContain('回答列表暂未同步');
  expect(readPendingAgentRun({ threadId: 'thread-cancel-fail' })?.runId).toBe('run-cancel-fail');
});

test('cancel terminal reconcile success fully settles and clears pending', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const retained = pending('thread-cancel-success', 'run-cancel-success');
  const durableMessages = [{ messageId: 'cancel-user', role: 'user', content: '停止任务' }];
  cancelAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-cancel-success', threadId: 'thread-cancel-success', status: 'CANCELLED' },
  });
  listAgentMessages.mockResolvedValue({ succeed: true, data: durableMessages });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });

  store.dispatch(setSelectedThreadId('thread-cancel-success'));
  store.dispatch(setCurrentRun({
    runId: 'run-cancel-success', threadId: 'thread-cancel-success', status: 'RUNNING',
  }));
  store.dispatch(setSending(true));
  store.dispatch(setStreamingContent('临时内容'));
  writePendingAgentRun(retained);

  await store.dispatch(stopAgentRun());

  const state = store.getState().agent;
  expect(state.currentRun?.status).toBe('CANCELLED');
  expect(state.sending).toBe(false);
  expect(state.terminalReconcilePending).toBe(false);
  expect(state.streamingContent).toBe('');
  expect(state.messages).toEqual(durableMessages);
  expect(readPendingAgentRun({ threadId: 'thread-cancel-success' })).toBeNull();
});

test('resuming a known terminal pending result never returns to working state', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  const runLookup = deferred();
  const retained = pending('thread-terminal-resume', 'run-terminal-resume');
  getAgentRun.mockReturnValue(runLookup.promise);
  listAgentMessages.mockResolvedValue({
    succeed: true, data: [{ messageId: 'assistant-final', role: 'assistant', content: '最终回答' }],
  });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });

  store.dispatch(setSelectedThreadId('thread-terminal-resume'));
  store.dispatch(setCurrentRun({
    runId: 'run-terminal-resume', threadId: 'thread-terminal-resume', status: 'COMPLETED',
  }));
  store.dispatch(setTerminalReconcilePending(true));
  store.dispatch(setStreamingContent('已有临时回答'));
  writePendingAgentRun(retained);

  const recovery = store.dispatch(resumePendingAgentRun(retained));
  expect(store.getState().agent.sending).toBe(false);
  expect(store.getState().agent.terminalReconcilePending).toBe(true);

  runLookup.resolve({
    succeed: true,
    data: { runId: 'run-terminal-resume', threadId: 'thread-terminal-resume', status: 'COMPLETED' },
  });
  await recovery;

  expect(store.getState().agent.sending).toBe(false);
  expect(store.getState().agent.terminalReconcilePending).toBe(false);
  expect(store.getState().agent.streamingContent).toBe('');
  expect(readPendingAgentRun({ threadId: 'thread-terminal-resume' })).toBeNull();
});
