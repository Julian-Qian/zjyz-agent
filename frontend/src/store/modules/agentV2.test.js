import { configureStore } from '@reduxjs/toolkit';
import {
  answerAgentInteraction,
  cancelAgentRun,
  cancelAgentTask,
  retryAgentTask,
  createAgentRun,
  createAgentTurn,
  getAgentActiveTask,
  getAgentCapabilities,
  getAgentRun,
  getAgentTask,
  getAgentV2Capabilities,
  listAgentArtifacts,
  listAgentMessages,
  listAgentThreads,
  streamAgentRun,
} from '@/api/agent';
import agentReducer, {
  ALL_AGENT_PROJECTS,
  answerAgentWorkspaceInteraction,
  consumeScopeOverridePending,
  fetchAgentCapabilities,
  resumePendingAgentRun,
  retryAgentWorkspaceTask,
  selectAgentThread,
  sendAgentWorkspaceMessage,
  setCapability,
  setCurrentInteraction,
  setCurrentRun,
  setCurrentTask,
  setSelectedProjectIds,
  setSelectedThreadId,
  setScopeOverridePending,
  setWorkspace,
  stopAgentRun,
} from './agent';

jest.mock('@/api/agent', () => ({
  answerAgentInteraction: jest.fn(),
  archiveAgentThread: jest.fn(),
  cancelAgentRun: jest.fn(),
  cancelAgentTask: jest.fn(),
  retryAgentTask: jest.fn(),
  createAgentRun: jest.fn(),
  createAgentTurn: jest.fn(),
  createAgentThread: jest.fn(),
  createAgentWorkspace: jest.fn(),
  getAgentActiveTask: jest.fn(),
  getAgentCapabilities: jest.fn(),
  getAgentRun: jest.fn(),
  getAgentTask: jest.fn(),
  getAgentV2Capabilities: jest.fn(),
  listAgentArtifacts: jest.fn(),
  listAgentMessages: jest.fn(),
  listAgentProjects: jest.fn(),
  listAgentThreads: jest.fn(),
  streamAgentRun: jest.fn(),
  updateAgentThread: jest.fn(),
}), { virtual: true });

const storeWithThread = () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  store.dispatch(setSelectedThreadId('thread-1'));
  store.dispatch(setSelectedProjectIds([ALL_AGENT_PROJECTS]));
  store.dispatch(setWorkspace({ workspaceId: 'workspace-1' }));
  store.dispatch(setCapability({
    checked: true,
    enabled: true,
    runtimeVersion: 'V2',
    mode: 'READ_ONLY',
    v2Enabled: true,
  }));
  return store;
};

beforeEach(() => {
  jest.clearAllMocks();
  window.sessionStorage.clear();
  listAgentMessages.mockResolvedValue({ succeed: true, data: [] });
  listAgentArtifacts.mockResolvedValue({ succeed: true, data: [] });
  listAgentThreads.mockResolvedValue({ succeed: true, data: [] });
  getAgentActiveTask.mockRejectedValue({ response: { status: 404 } });
  getAgentTask.mockRejectedValue({ response: { status: 404 } });
});

test('capability bootstrap prefers the canonical selectionMode and enables read-only V2', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  getAgentV2Capabilities.mockResolvedValue({
    succeed: true,
    data: { runtimeVersion: 'V2', mode: 'READ_ONLY', capabilities: [] },
  });

  await store.dispatch(fetchAgentCapabilities([ALL_AGENT_PROJECTS]));

  expect(getAgentV2Capabilities).toHaveBeenCalledWith({ selectionMode: 'ALL', projectIds: [] });
  expect(store.getState().agent.capability.v2Enabled).toBe(true);
});

test('V2 messages submit a scoped turn and consume canonical task events on the existing run stream', async () => {
  const store = storeWithThread();
  createAgentTurn.mockResolvedValue({
    succeed: true,
    data: {
      taskId: 'task-1',
      runId: 'run-1',
      threadId: 'thread-1',
      status: 'QUEUED',
      taskStatus: 'OPEN',
      runtimeVersion: 'V2',
      scopeSnapshot: { selectionMode: 'ALL', projectIds: ['project-1', 'project-2'] },
    },
  });
  streamAgentRun.mockImplementation(async (runId, { onEvent }) => {
    onEvent({ id: '1', event: 'task.resolved', payload: { taskId: 'task-1', goal: '核对未归还材料' } });
    onEvent({
      id: '2',
      event: 'task.status',
      payload: { task: { taskId: 'task-1', status: 'WAITING_INPUT' } },
    });
    onEvent({
      id: '3',
      event: 'clarification.required',
      payload: { interactionId: 'interaction-1', question: '按数量还是金额？', options: ['按数量', '按金额'] },
    });
    onEvent({ id: '4', event: 'run.completed', payload: {} });
  });
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-1', threadId: 'thread-1', status: 'COMPLETED' },
  });

  await store.dispatch(sendAgentWorkspaceMessage('帮我排个名'));

  expect(createAgentRun).not.toHaveBeenCalled();
  expect(createAgentTurn).toHaveBeenCalledWith('thread-1', expect.objectContaining({
    message: '帮我排个名',
    scopeSelection: { selectionMode: 'ALL', projectIds: [], explicitOverride: false },
  }));
  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-1',
    status: 'WAITING_INPUT',
    goal: '核对未归还材料',
    scopeSnapshot: { selectionMode: 'ALL', projectIds: ['project-1', 'project-2'] },
  }));
  expect(store.getState().agent.currentInteraction).toEqual(expect.objectContaining({
    interactionId: 'interaction-1',
  }));
});

test('task.resolved keeps a CONTINUE task specification and its inherited frozen scope', async () => {
  const store = storeWithThread();
  createAgentTurn.mockResolvedValue({
    succeed: true,
    data: { taskId: 'task-continue', runId: 'run-continue', threadId: 'thread-1', status: 'QUEUED' },
  });
  const inheritedScope = {
    requestedSelectionMode: 'INHERIT',
    effectiveSelectionMode: 'EXPLICIT',
    projectIds: ['project-parent'],
    projectCount: 1,
  };
  streamAgentRun.mockImplementation(async (runId, { onEvent }) => {
    onEvent({
      id: '1',
      event: 'task.resolved',
      payload: {
        taskId: 'task-continue',
        interpretation: { dialogueAct: 'CONTINUE', relationType: 'CONTINUATION' },
        taskSpec: { dialogueAct: 'CONTINUE', resolvedGoal: '继续按数量排行' },
        scope: inheritedScope,
      },
    });
    onEvent({ id: '2', event: 'run.completed', payload: {} });
  });
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-continue', threadId: 'thread-1', status: 'COMPLETED' },
  });

  await store.dispatch(sendAgentWorkspaceMessage('继续，按数量'));

  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-continue',
    dialogueAct: 'CONTINUE',
    resolvedGoal: '继续按数量排行',
    taskSpec: expect.objectContaining({ dialogueAct: 'CONTINUE' }),
    interpretation: expect.objectContaining({ relationType: 'CONTINUATION' }),
    scopeSnapshot: inheritedScope,
  }));
});

test('a clarification option uses the interaction endpoint and links the returned run', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'task-parent', status: 'WAITING_INPUT' }));
  store.dispatch(setCurrentInteraction({ interactionId: 'interaction-1', question: '按什么排行？' }));
  answerAgentInteraction.mockResolvedValue({
    succeed: true,
    data: { taskId: 'task-child', runId: 'run-child', threadId: 'thread-1', status: 'QUEUED' },
  });
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-child', threadId: 'thread-1', status: 'COMPLETED' },
  });

  await store.dispatch(answerAgentWorkspaceInteraction({ value: 'QUANTITY', label: '按数量' }));

  expect(answerAgentInteraction).toHaveBeenCalledWith('interaction-1', expect.objectContaining({
    answer: { value: 'QUANTITY', label: '按数量' },
    context: expect.objectContaining({ parentTaskId: 'task-parent', interactionId: 'interaction-1' }),
  }));
  expect(createAgentTurn).not.toHaveBeenCalled();
});

test('switching threads while an interaction answer is in flight never mutates the new thread view', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'task-parent', status: 'WAITING_INPUT' }));
  store.dispatch(setCurrentInteraction({ interactionId: 'interaction-1', question: '按什么排行？' }));
  let resolveInteraction;
  answerAgentInteraction.mockImplementation(() => new Promise((resolve) => { resolveInteraction = resolve; }));

  const submission = store.dispatch(answerAgentWorkspaceInteraction({ value: 'QUANTITY', label: '按数量' }));
  await Promise.resolve();
  await store.dispatch(selectAgentThread('thread-2'));
  resolveInteraction({
    succeed: true,
    data: { taskId: 'task-child', runId: 'run-child', threadId: 'thread-1', status: 'QUEUED' },
  });
  await submission;

  expect(store.getState().agent.selectedThreadId).toBe('thread-2');
  expect(store.getState().agent.sending).toBe(false);
  expect(store.getState().agent.messages).toEqual([]);
  expect(JSON.parse(window.sessionStorage.getItem('zjyz.agent.pending-run.v1:thread-1'))).toEqual(
    expect.objectContaining({ runId: 'run-child', requestKind: 'INTERACTION' }),
  );
});

test('selecting a thread restores its durable waiting task and interaction when V2 endpoint exists', async () => {
  const store = storeWithThread();
  getAgentActiveTask.mockResolvedValue({
    succeed: true,
    data: {
      task: {
        taskId: 'task-waiting',
        status: 'WAITING_INPUT',
        scopeSnapshot: { selectionMode: 'EXPLICIT', projectIds: ['project-7'] },
      },
      interaction: { interactionId: 'interaction-7', question: '请补充统计口径', options: [] },
    },
  });
  getAgentTask.mockResolvedValue({
    succeed: true,
    data: {
      task: { taskId: 'task-waiting', status: 'WAITING_INPUT', taskSpec: { goal: '核对材料' } },
      interaction: { interactionId: 'interaction-7', question: '请补充统计口径', options: [] },
    },
  });

  await store.dispatch(selectAgentThread('thread-1'));

  expect(getAgentActiveTask).toHaveBeenCalledWith('thread-1');
  expect(getAgentTask).toHaveBeenCalledWith('task-waiting');
  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-waiting',
    status: 'WAITING_INPUT',
    taskSpec: { goal: '核对材料' },
    scopeSnapshot: { selectionMode: 'EXPLICIT', projectIds: ['project-7'] },
  }));
  expect(store.getState().agent.currentInteraction).toEqual(expect.objectContaining({
    interactionId: 'interaction-7',
  }));
});

test('selecting a thread restores and resumes its active run even when sessionStorage was lost', async () => {
  const store = storeWithThread();
  getAgentActiveTask.mockImplementation((threadId) => threadId === 'thread-1'
    ? Promise.resolve({
      succeed: true,
      data: {
        runtimeVersion: 'V2',
        taskId: 'task-active',
        taskStatus: 'EXECUTING',
        runId: 'run-active',
        threadId: 'thread-1',
        status: 'RUNNING',
        scopeSnapshot: {
          effectiveSelectionMode: 'EXPLICIT', projectIds: ['project-active'],
        },
        run: { runId: 'run-active', status: 'RUNNING' },
      },
    })
    : Promise.reject({ response: { status: 404 } }));
  getAgentTask.mockResolvedValue({
    succeed: true,
    data: {
      runtimeVersion: 'V2',
      taskId: 'task-active',
      taskStatus: 'EXECUTING',
      runId: 'run-active',
      threadId: 'thread-1',
      status: 'RUNNING',
      goal: '继续核对材料占用',
      scopeSnapshot: {
        effectiveSelectionMode: 'EXPLICIT', projectIds: ['project-active'],
      },
      run: { runId: 'run-active', status: 'RUNNING' },
    },
  });
  getAgentRun.mockImplementation(() => new Promise(() => {}));

  expect(window.sessionStorage.length).toBe(0);
  await store.dispatch(selectAgentThread('thread-1'));
  await Promise.resolve();

  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-active', status: 'EXECUTING',
  }));
  expect(store.getState().agent.currentRun).toEqual(expect.objectContaining({
    runId: 'run-active', status: 'RUNNING', threadId: 'thread-1',
  }));
  expect(store.getState().agent.sending).toBe(true);
  expect(getAgentRun).toHaveBeenCalledWith('run-active');
  expect(JSON.parse(window.sessionStorage.getItem('zjyz.agent.pending-run.v1:thread-1'))).toEqual(
    expect.objectContaining({ runId: 'run-active', taskId: 'task-active', runtimeVersion: 'V2' }),
  );

  await store.dispatch(selectAgentThread('thread-2'));
  expect(store.getState().agent.selectedThreadId).toBe('thread-2');
  expect(store.getState().agent.sending).toBe(false);
  expect(store.getState().agent.currentRun).toBeNull();
});

test('an accepted interaction child run reloads as monitoring and keeps its restored task', async () => {
  const store = storeWithThread();
  window.sessionStorage.setItem('zjyz.agent.pending-run.v1:thread-1', JSON.stringify({
    threadId: 'thread-1',
    runId: 'run-interaction-child',
    taskId: 'task-interaction-child',
    runtimeVersion: 'V2',
    requestKind: 'INTERACTION',
    interactionId: 'interaction-parent',
    interactionAnswer: { value: 'QUANTITY', label: '按数量' },
    clientRequestId: 'request-interaction-child',
    createdAt: Date.now(),
    payload: {
      message: '按数量',
      clientRequestId: 'request-interaction-child',
      selectionMode: 'EXPLICIT',
      projectIds: ['project-active'],
      context: { interactionId: 'interaction-parent', parentTaskId: 'task-parent' },
    },
  }));
  getAgentActiveTask.mockImplementation((threadId) => threadId === 'thread-1'
    ? Promise.resolve({
      succeed: true,
      data: {
        runtimeVersion: 'V2',
        taskId: 'task-interaction-child',
        taskStatus: 'EXECUTING',
        runId: 'run-interaction-child',
        threadId: 'thread-1',
        status: 'RUNNING',
        goal: '按数量继续排行',
        scopeSnapshot: { effectiveSelectionMode: 'EXPLICIT', projectIds: ['project-active'] },
        run: { runId: 'run-interaction-child', status: 'RUNNING' },
      },
    })
    : Promise.reject({ response: { status: 404 } }));
  getAgentTask.mockResolvedValue({
    succeed: true,
    data: {
      runtimeVersion: 'V2',
      taskId: 'task-interaction-child',
      taskStatus: 'EXECUTING',
      runId: 'run-interaction-child',
      threadId: 'thread-1',
      status: 'RUNNING',
      goal: '按数量继续排行',
      run: { runId: 'run-interaction-child', status: 'RUNNING' },
    },
  });
  getAgentRun
    .mockResolvedValueOnce({
      succeed: true,
      data: { runId: 'run-interaction-child', threadId: 'thread-1', status: 'RUNNING' },
    })
    .mockImplementation(() => new Promise(() => {}));
  streamAgentRun.mockImplementation(() => new Promise(() => {}));

  await store.dispatch(selectAgentThread('thread-1'));
  await Promise.resolve();
  await Promise.resolve();

  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-interaction-child', status: 'EXECUTING', goal: '按数量继续排行',
  }));
  expect(store.getState().agent.currentRun).toEqual(expect.objectContaining({
    runId: 'run-interaction-child', status: 'RUNNING',
  }));
  expect(store.getState().agent.sending).toBe(true);
  expect(JSON.parse(window.sessionStorage.getItem('zjyz.agent.pending-run.v1:thread-1'))).toEqual(
    expect.objectContaining({ requestKind: 'TURN', runId: 'run-interaction-child' }),
  );

  await store.dispatch(selectAgentThread('thread-2'));
});

test('terminal polling reconciles a waiting interaction even when SSE finishes before emitting it', async () => {
  const store = storeWithThread();
  createAgentTurn.mockResolvedValue({
    succeed: true,
    data: { taskId: 'task-race', runId: 'run-race', threadId: 'thread-1', status: 'QUEUED' },
  });
  streamAgentRun.mockResolvedValue(undefined);
  getAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-race', threadId: 'thread-1', status: 'COMPLETED' },
  });
  getAgentActiveTask.mockResolvedValue({
    succeed: true,
    data: {
      task: { taskId: 'task-race', status: 'WAITING_USER' },
      interaction: { interactionId: 'interaction-race', question: '请选择统计口径', options: ['数量', '金额'] },
    },
  });
  getAgentTask.mockResolvedValue({
    succeed: true,
    data: {
      task: { taskId: 'task-race', status: 'WAITING_USER', taskSpec: { goal: '材料排行' } },
      interaction: { interactionId: 'interaction-race', question: '请选择统计口径', options: ['数量', '金额'] },
    },
  });

  await store.dispatch(sendAgentWorkspaceMessage('帮我排个名'));

  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({
    taskId: 'task-race', status: 'WAITING_USER', taskSpec: { goal: '材料排行' },
  }));
  expect(store.getState().agent.currentInteraction).toEqual(expect.objectContaining({
    interactionId: 'interaction-race',
  }));
});

test('a waiting V2 task is cancellable after its originating run has completed', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'task-waiting', status: 'WAITING_USER', runtimeVersion: 'V2' }));
  store.dispatch(setCurrentInteraction({ interactionId: 'interaction-waiting', question: '请选择口径' }));
  store.dispatch(setCurrentRun({ runId: 'run-finished', threadId: 'thread-1', status: 'COMPLETED' }));
  cancelAgentTask.mockResolvedValue({
    succeed: true,
    data: { task: { taskId: 'task-waiting', status: 'CANCELLED', runtimeVersion: 'V2' } },
  });

  await store.dispatch(stopAgentRun());

  expect(cancelAgentTask).toHaveBeenCalledWith('task-waiting');
  expect(cancelAgentRun).not.toHaveBeenCalled();
  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({ status: 'CANCELLED' }));
  expect(store.getState().agent.currentInteraction).toBeNull();
});

test('active V2 cancellation falls back to the run endpoint when task cancel is not deployed', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'task-running', status: 'EXECUTING', runtimeVersion: 'V2' }));
  store.dispatch(setCurrentRun({ runId: 'run-running', threadId: 'thread-1', status: 'RUNNING' }));
  cancelAgentTask.mockRejectedValue({ response: { status: 404 } });
  cancelAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-running', threadId: 'thread-1', status: 'CANCELLED' },
  });

  await store.dispatch(stopAgentRun());

  expect(cancelAgentTask).toHaveBeenCalledWith('task-running');
  expect(cancelAgentRun).toHaveBeenCalledWith('run-running');
  expect(store.getState().agent.currentRun.status).toBe('CANCELLED');
});

test('an explicit project selector change overrides scope once and then returns to task inheritance', async () => {
  const store = storeWithThread();
  store.dispatch(setScopeOverridePending(true));
  createAgentTurn.mockResolvedValue({
    succeed: true,
    data: { taskId: 'task-scope', runId: 'run-scope', threadId: 'thread-1', status: 'COMPLETED' },
  });

  await store.dispatch(sendAgentWorkspaceMessage('改查当前选择的项目'));

  expect(createAgentTurn).toHaveBeenCalledWith('thread-1', expect.objectContaining({
    scopeSelection: expect.objectContaining({ explicitOverride: true }),
  }));
  expect(store.getState().agent.scopeOverridePending).toBe(false);
});

test('a run from another thread cannot consume the selected thread scope override', () => {
  const store = storeWithThread();
  store.dispatch(setSelectedProjectIds(['project-b']));
  store.dispatch(setScopeOverridePending({ threadId: 'thread-b', scopeKey: 'EXPLICIT:project-b' }));

  store.dispatch(consumeScopeOverridePending({ threadId: 'thread-a', scopeKey: 'EXPLICIT:project-a' }));

  expect(store.getState().agent.scopeOverridePending).toEqual({
    threadId: 'thread-b', scopeKey: 'EXPLICIT:project-b',
  });
});

test('a stale capability request cannot overwrite the manifest for the latest scope', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  let resolveFirst;
  let resolveSecond;
  getAgentV2Capabilities
    .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
    .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }));

  const first = store.dispatch(fetchAgentCapabilities(['project-a']));
  while (getAgentV2Capabilities.mock.calls.length < 1) await Promise.resolve();
  const second = store.dispatch(fetchAgentCapabilities(['project-b']));
  while (getAgentV2Capabilities.mock.calls.length < 2) await Promise.resolve();
  resolveSecond({ succeed: true, data: { runtimeVersion: 'V2', mode: 'READ_ONLY', marker: 'B' } });
  await second;
  resolveFirst({ succeed: true, data: { runtimeVersion: 'V2', mode: 'READ_ONLY', marker: 'A' } });
  await first;

  expect(store.getState().agent.capability.v2.marker).toBe('B');
  expect(store.getState().agent.capability.capabilityScopeKey).toBe('EXPLICIT:project-b');
});

test('a dynamically disabled V2 manifest cannot remain enabled from its runtime version alone', async () => {
  const store = configureStore({ reducer: { agent: agentReducer } });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  getAgentV2Capabilities.mockResolvedValue({
    succeed: true,
    data: { enabled: false, runtimeVersion: 'V2', mode: 'READ_ONLY', capabilities: [] },
  });

  await store.dispatch(fetchAgentCapabilities([ALL_AGENT_PROJECTS]));

  expect(store.getState().agent.capability.v2Enabled).toBe(false);
});

test('a new ordinary turn reuses its exact clientRequestId when V2 is dynamically downgraded to V1', async () => {
  const store = storeWithThread();
  createAgentTurn.mockResolvedValue({
    succeed: false,
    returnCode: 'AGT_V2_DISABLED',
    errorMessage: '小云V2暂未开放',
  });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  getAgentV2Capabilities.mockResolvedValue({
    succeed: false, returnCode: 'AGT_V2_DISABLED', errorMessage: '小云V2暂未开放',
  });
  createAgentRun.mockResolvedValue({
    succeed: true,
    data: { runId: 'run-v1-fallback', threadId: 'thread-1', status: 'COMPLETED' },
  });

  await store.dispatch(sendAgentWorkspaceMessage('查询当前库存'));

  const v2Payload = createAgentTurn.mock.calls[0][1];
  const v1Payload = createAgentRun.mock.calls[0][1];
  expect(createAgentTurn).toHaveBeenCalledTimes(1);
  expect(createAgentRun).toHaveBeenCalledWith('thread-1', expect.objectContaining({
    message: '查询当前库存', clientRequestId: v2Payload.clientRequestId,
  }));
  expect(v1Payload.clientRequestId).toBe(v2Payload.clientRequestId);
  expect(store.getState().agent.capability.v2Enabled).toBe(false);
  expect(window.sessionStorage.getItem('zjyz.agent.pending-run.v1:thread-1')).toBeNull();
});

test('a disabled V2 interaction is not replayed as an unrelated V1 turn', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'task-waiting', status: 'WAITING_USER', runtimeVersion: 'V2' }));
  store.dispatch(setCurrentInteraction({ interactionId: 'interaction-disabled', question: '请补充统计口径' }));
  answerAgentInteraction.mockResolvedValue({
    succeed: false,
    returnCode: 'AGT_V2_DISABLED',
    errorMessage: '小云V2暂未开放',
  });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  getAgentV2Capabilities.mockResolvedValue({
    succeed: false, returnCode: 'AGT_V2_DISABLED', errorMessage: '小云V2暂未开放',
  });

  await store.dispatch(answerAgentWorkspaceInteraction('按数量'));

  expect(answerAgentInteraction).toHaveBeenCalledTimes(1);
  expect(createAgentTurn).not.toHaveBeenCalled();
  expect(createAgentRun).not.toHaveBeenCalled();
  expect(store.getState().agent.currentTask).toEqual(expect.objectContaining({ taskId: 'task-waiting' }));
  expect(store.getState().agent.currentInteraction).toEqual(expect.objectContaining({
    interactionId: 'interaction-disabled',
  }));
  expect(store.getState().agent.messages).toEqual([
    expect.objectContaining({ content: '按数量', deliveryStatus: 'FAILED' }),
  ]);
  expect(store.getState().agent.error).toContain('取消当前任务');
  expect(window.sessionStorage.getItem('zjyz.agent.pending-run.v1:thread-1')).toBeNull();
});

test('an already accepted V2 run is never replayed through V1 when the gate changes', async () => {
  const store = storeWithThread();
  getAgentRun.mockResolvedValue({
    succeed: false,
    returnCode: 'AGT_V2_DISABLED',
    errorMessage: '小云V2暂未开放',
  });
  getAgentCapabilities.mockResolvedValue({ succeed: true, data: { enabled: true } });
  getAgentV2Capabilities.mockResolvedValue({
    succeed: false, returnCode: 'AGT_V2_DISABLED', errorMessage: '小云V2暂未开放',
  });
  const pending = {
    threadId: 'thread-1',
    runId: 'run-already-accepted',
    taskId: 'task-already-accepted',
    runtimeVersion: 'V2',
    requestKind: 'TURN',
    clientRequestId: 'request-already-accepted',
    createdAt: Date.now(),
    payload: {
      message: '查询库存', clientRequestId: 'request-already-accepted', selectionMode: 'ALL', projectIds: [],
    },
  };

  await store.dispatch(resumePendingAgentRun(pending));

  expect(createAgentRun).not.toHaveBeenCalled();
  expect(createAgentTurn).not.toHaveBeenCalled();
  expect(store.getState().agent.statusMessage).toContain('不会降级重发');
});

test('an unrecoverable turn creation failure marks the optimistic message as not delivered', async () => {
  const store = storeWithThread();
  createAgentTurn.mockRejectedValue({ response: { status: 400 } });

  await store.dispatch(sendAgentWorkspaceMessage('无效请求'));

  expect(store.getState().agent.messages).toEqual([
    expect.objectContaining({ content: '无效请求', deliveryStatus: 'FAILED' }),
  ]);
});


test('retry creates a child task through retry endpoint and never resends the turn', async () => {
  const store = storeWithThread();
  store.dispatch(setCurrentTask({ taskId: 'old-task', threadId: 'thread-1', status: 'BLOCKED', runtimeVersion: 'V2' }));
  getAgentTask.mockResolvedValue({ succeed: true, data: { taskId: 'old-task', status: 'BLOCKED', version: 4, goal: '查询未归还材料' } });
  retryAgentTask.mockResolvedValue({ succeed: true, data: { taskId: 'child-task', runId: 'child-run', status: 'COMPLETED', taskStatus: 'BLOCKED', runtimeVersion: 'V2' } });
  await store.dispatch(retryAgentWorkspaceTask());
  expect(retryAgentTask).toHaveBeenCalledWith('old-task', { clientRequestId: expect.any(String), expectedTaskVersion: 4 });
  expect(createAgentTurn).not.toHaveBeenCalled();
  expect(createAgentRun).not.toHaveBeenCalled();
});

test('unaccepted retry recovery preserves original task/version/key and does not post a turn', async () => {
  const store = storeWithThread();
  retryAgentTask.mockResolvedValue({ succeed: true, data: { taskId: 'child-task', runId: 'child-run', status: 'COMPLETED', taskStatus: 'BLOCKED', runtimeVersion: 'V2' } });
  await store.dispatch(resumePendingAgentRun({
    threadId: 'thread-1', taskId: 'old-task', retryTaskId: 'old-task', expectedTaskVersion: 7,
    requestKind: 'RETRY', runtimeVersion: 'V2', clientRequestId: 'retry-key', createdAt: Date.now(),
    payload: { message: '原任务', clientRequestId: 'retry-key', selectionMode: 'ALL', projectIds: [] },
  }));
  expect(retryAgentTask).toHaveBeenCalledWith('old-task', { clientRequestId: 'retry-key', expectedTaskVersion: 7 });
  expect(createAgentTurn).not.toHaveBeenCalled();
});

test('accepted retry recovery only loads its child run', async () => {
  const store = storeWithThread();
  getAgentRun.mockResolvedValue({ succeed: true, data: { runId: 'child-run', taskId: 'child-task', status: 'COMPLETED', runtimeVersion: 'V2' } });
  await store.dispatch(resumePendingAgentRun({
    threadId: 'thread-1', taskId: 'child-task', runId: 'child-run', retryTaskId: 'old-task', expectedTaskVersion: 7,
    requestKind: 'RETRY', runtimeVersion: 'V2', clientRequestId: 'retry-key', createdAt: Date.now(),
    payload: { message: '原任务', clientRequestId: 'retry-key', selectionMode: 'ALL', projectIds: [] },
  }));
  expect(getAgentRun).toHaveBeenCalledWith('child-run');
  expect(retryAgentTask).not.toHaveBeenCalled();
  expect(createAgentTurn).not.toHaveBeenCalled();
});
