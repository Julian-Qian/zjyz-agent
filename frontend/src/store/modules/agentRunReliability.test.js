import {
  PENDING_AGENT_RUN_KEY_PREFIX,
  PENDING_AGENT_RUN_MAX_AGE_MS,
  createAgentRunStatusLatch,
  createRunWithIdempotentRetry,
  getRunWithRecoveryRetry,
  isAgentRunTerminal,
  isUnrecoverableAgentRunError,
  readPendingAgentRun,
  writePendingAgentRun,
} from './agentRunReliability';

const memoryStorage = () => {
  const values = new Map();
  return {
    get length() { return values.size; },
    key: (index) => Array.from(values.keys())[index] ?? null,
    getItem: (key) => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
  };
};

const pending = (threadId, createdAt = 1000) => ({
  threadId,
  clientRequestId: `request-${threadId}`,
  createdAt,
  payload: {
    message: `查询${threadId}库存`,
    clientRequestId: `request-${threadId}`,
    selectionMode: 'ALL',
    projectIds: [],
  },
});

test('terminal status latch never regresses to a non-terminal state', () => {
  const latch = createAgentRunStatusLatch('RUNNING');
  expect(latch.accept('COMPLETED')).toBe(true);
  expect(latch.accept('RUNNING')).toBe(false);
  expect(latch.getStatus()).toBe('COMPLETED');
});

test('FINALIZING remains non-terminal until the backend confirms a final state', () => {
  expect(isAgentRunTerminal('FINALIZING')).toBe(false);
});

test('run creation retry preserves the exact client request payload', async () => {
  const request = pending('thread-a').payload;
  const createRun = jest.fn()
    .mockRejectedValueOnce({ code: 'ECONNABORTED', request: {} })
    .mockResolvedValueOnce({ succeed: true, data: { runId: 'run-a' } });

  const run = await createRunWithIdempotentRetry({
    threadId: 'thread-a',
    payload: request,
    createRun,
    unwrap: (response) => response.data,
    wait: () => Promise.resolve(),
  });

  expect(run.runId).toBe('run-a');
  expect(createRun).toHaveBeenCalledTimes(2);
  expect(createRun.mock.calls[0][1]).toBe(request);
  expect(createRun.mock.calls[1][1]).toBe(request);
  expect(request.clientRequestId).toBe('request-thread-a');
});

test('active run recovery retries transient 5xx with the same run id', async () => {
  const getRun = jest.fn()
    .mockRejectedValueOnce({ response: { status: 503 } })
    .mockResolvedValueOnce({ succeed: true, data: { runId: 'run-a', status: 'RUNNING' } });

  const run = await getRunWithRecoveryRetry({
    runId: 'run-a',
    getRun,
    unwrap: (response) => response.data,
    wait: () => Promise.resolve(),
  });

  expect(run.status).toBe('RUNNING');
  expect(getRun).toHaveBeenNthCalledWith(1, 'run-a');
  expect(getRun).toHaveBeenNthCalledWith(2, 'run-a');
});

test('only deterministic unrecoverable run lookup failures are eligible for cleanup', () => {
  expect(isUnrecoverableAgentRunError({ response: { status: 404 } })).toBe(true);
  expect(isUnrecoverableAgentRunError({ returnCode: 'AGT409' })).toBe(true);
  expect(isUnrecoverableAgentRunError({ returnCode: 'AGT429' })).toBe(true);
  expect(isUnrecoverableAgentRunError({ response: { status: 503 } })).toBe(false);
  expect(isUnrecoverableAgentRunError({ code: 'ECONNABORTED', request: {} })).toBe(false);
});

test('pending eviction preserves accepted runs ahead of a newer unaccepted request', () => {
  const storage = memoryStorage();
  writePendingAgentRun({ ...pending('thread-a', 1000), runId: 'run-a' }, storage);
  writePendingAgentRun({ ...pending('thread-b', 2000), runId: 'run-b' }, storage);
  writePendingAgentRun(pending('thread-c', 3000), storage);

  expect(readPendingAgentRun({ threadId: 'thread-a', now: 4000, storage }).runId).toBe('run-a');
  expect(readPendingAgentRun({ threadId: 'thread-b', now: 4000, storage }).runId).toBe('run-b');
  expect(readPendingAgentRun({ threadId: 'thread-c', now: 4000, storage })).toBeNull();
});

test('pending runs are isolated by thread and survive switching away and back', () => {
  const storage = memoryStorage();
  writePendingAgentRun(pending('thread-a', 1000), storage);
  writePendingAgentRun(pending('thread-b', 2000), storage);

  expect(readPendingAgentRun({ threadId: 'thread-b', now: 3000, storage }).threadId).toBe('thread-b');
  expect(readPendingAgentRun({ threadId: 'thread-a', now: 3000, storage }).threadId).toBe('thread-a');
  expect(storage.length).toBe(2);
});

test('pending interaction answers retain the endpoint identity needed for reload recovery', () => {
  const storage = memoryStorage();
  writePendingAgentRun({
    ...pending('thread-interaction', 1000),
    requestKind: 'INTERACTION',
    interactionId: 'interaction-1',
    interactionAnswer: { value: 'QUANTITY', label: '按数量' },
    runtimeVersion: 'V2',
  }, storage);

  expect(readPendingAgentRun({ threadId: 'thread-interaction', now: 2000, storage })).toEqual(
    expect.objectContaining({
      requestKind: 'INTERACTION',
      interactionId: 'interaction-1',
      interactionAnswer: { value: 'QUANTITY', label: '按数量' },
    }),
  );
});

test('corrupt expired and key-thread-mismatched records are cleared independently', () => {
  const storage = memoryStorage();
  storage.setItem(`${PENDING_AGENT_RUN_KEY_PREFIX}corrupt`, '{');
  writePendingAgentRun(pending('expired', 1000), storage);
  storage.setItem(`${PENDING_AGENT_RUN_KEY_PREFIX}mismatch`, JSON.stringify(pending('different', 2000)));
  writePendingAgentRun(pending('healthy', 3000), storage);

  expect(readPendingAgentRun({ threadId: 'corrupt', now: 4000, storage })).toBeNull();
  expect(readPendingAgentRun({
    threadId: 'expired', now: 1000 + PENDING_AGENT_RUN_MAX_AGE_MS + 1, storage,
  })).toBeNull();
  expect(readPendingAgentRun({ threadId: 'mismatch', now: 4000, storage })).toBeNull();
  expect(readPendingAgentRun({ threadId: 'healthy', now: 4000, storage }).threadId).toBe('healthy');
});

test('recovery preserves attachment IDs without persisting file text', () => {
  const storage = memoryStorage();
  const request = pending('attachment-thread');
  request.payload.attachmentIds = ['file-a', 'file-b'];
  request.payload.fileText = 'PRIVATE CONTRACT';
  writePendingAgentRun(request, storage);
  const restored = readPendingAgentRun({ threadId: request.threadId, now: 1001, storage });
  expect(restored.payload.attachmentIds).toEqual(['file-a', 'file-b']);
  expect(JSON.stringify(restored)).not.toContain('PRIVATE CONTRACT');
});


test('retry pending keeps source task/version and rejects corrupted retry records', () => {
  const storage = memoryStorage();
  const retry = { ...pending('thread-retry'), runtimeVersion: 'V2', requestKind: 'RETRY', retryTaskId: 'original-task', expectedTaskVersion: 6 };
  writePendingAgentRun(retry, storage);
  expect(readPendingAgentRun({ threadId: 'thread-retry', now: 2000, storage })).toMatchObject({ requestKind: 'RETRY', retryTaskId: 'original-task', expectedTaskVersion: 6 });
  writePendingAgentRun({ ...retry, expectedTaskVersion: null }, storage);
  expect(readPendingAgentRun({ threadId: 'thread-retry', now: 2000, storage })).toBeNull();
});
