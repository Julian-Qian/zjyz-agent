export const AGENT_RUN_TERMINAL_STATUSES = Object.freeze([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);

export const PENDING_AGENT_RUN_KEY_PREFIX = 'zjyz.agent.pending-run.v1:';
export const PENDING_AGENT_RUN_MAX_AGE_MS = 20 * 60 * 1000;
const MAX_PENDING_THREADS = 2;

const terminalStatuses = new Set(AGENT_RUN_TERMINAL_STATUSES);

export const isAgentRunTerminal = (status) => terminalStatuses.has(status);

export const createAgentRunStatusLatch = (initialStatus) => {
  let status = initialStatus || null;
  return {
    accept(nextStatus) {
      if (!nextStatus || (isAgentRunTerminal(status) && nextStatus !== status)) return false;
      status = nextStatus;
      return true;
    },
    getStatus() { return status; },
    isTerminal() { return isAgentRunTerminal(status); },
  };
};

const httpStatusOf = (error) => Number(error?.response?.status) || 0;

export const isRetryableRunRecoveryError = (error) => {
  const status = httpStatusOf(error);
  return error?.code === 'ECONNABORTED'
    || error?.code === 'ERR_NETWORK'
    || (!error?.response && !!error?.request)
    || status === 408
    || status === 429
    || status >= 500;
};

export const isRetryableRunCreationError = isRetryableRunRecoveryError;

export const isUnrecoverableAgentRunError = (error) => {
  const status = httpStatusOf(error);
  const businessCode = error?.returnCode || error?.errorCode || error?.code;
  if (['AGT403', 'AGT404', 'AGT409', 'AGT429'].includes(businessCode)) return true;
  return status >= 400 && status < 500 && status !== 408 && status !== 429;
};

export const getRunWithRecoveryRetry = async ({
  runId,
  getRun,
  unwrap,
  attempts = 3,
  wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
}) => {
  let lastError;
  for (let attempt = 0; attempt < Math.max(attempts, 1); attempt += 1) {
    try {
      return unwrap(await getRun(runId));
    } catch (error) {
      lastError = error;
      if (!isRetryableRunRecoveryError(error) || attempt >= attempts - 1) throw error;
      await wait(400 * (attempt + 1));
    }
  }
  throw lastError;
};

export const createRunWithIdempotentRetry = async ({
  threadId,
  payload,
  createRun,
  unwrap,
  attempts = 3,
  wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
}) => {
  let lastError;
  for (let attempt = 0; attempt < Math.max(attempts, 1); attempt += 1) {
    try {
      return unwrap(await createRun(threadId, payload));
    } catch (error) {
      lastError = error;
      if (!isRetryableRunCreationError(error) || attempt >= attempts - 1) throw error;
      await wait(400 * (attempt + 1));
    }
  }
  throw lastError;
};

const safeSessionStorage = () => {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch (error) {
    return null;
  }
};

const pendingKey = (threadId) => `${PENDING_AGENT_RUN_KEY_PREFIX}${threadId}`;

export const clearPendingAgentRun = (threadId, storage = safeSessionStorage()) => {
  if (!threadId) return;
  try { storage?.removeItem(pendingKey(threadId)); } catch (error) { /* storage unavailable */ }
};

export const writePendingAgentRun = (pending, storage = safeSessionStorage()) => {
  if (!storage || !pending?.threadId || !pending?.clientRequestId || !pending?.payload?.message) return false;
  const minimal = {
    threadId: pending.threadId,
    runId: pending.runId || null,
    taskId: pending.taskId || null,
    runtimeVersion: pending.runtimeVersion || null,
    requestKind: pending.requestKind || 'TURN',
    retryTaskId: pending.retryTaskId || null,
    expectedTaskVersion: pending.expectedTaskVersion ?? null,
    interactionId: pending.interactionId || null,
    interactionAnswer: pending.interactionAnswer && typeof pending.interactionAnswer === 'object'
      ? pending.interactionAnswer : null,
    scopeOverride: pending.scopeOverride === true,
    clientRequestId: pending.clientRequestId,
    createdAt: Number(pending.createdAt) || Date.now(),
    payload: {
      message: pending.payload.message,
      clientRequestId: pending.clientRequestId,
      selectionMode: pending.payload.selectionMode,
      projectIds: Array.isArray(pending.payload.projectIds) ? pending.payload.projectIds : [],
      attachmentIds: Array.isArray(pending.payload.attachmentIds) ? pending.payload.attachmentIds.filter((id) => typeof id === 'string') : [],
      context: pending.payload.context && typeof pending.payload.context === 'object'
        ? pending.payload.context : undefined,
    },
  };
  try {
    storage.setItem(pendingKey(pending.threadId), JSON.stringify(minimal));
    const entries = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (!key?.startsWith(PENDING_AGENT_RUN_KEY_PREFIX)) continue;
      try {
        const value = JSON.parse(storage.getItem(key));
        entries.push({ key, createdAt: Number(value?.createdAt) || 0, accepted: !!value?.runId });
      } catch (error) {
        storage.removeItem(key);
      }
    }
    entries.sort((left, right) => Number(right.accepted) - Number(left.accepted)
      || right.createdAt - left.createdAt)
      .slice(MAX_PENDING_THREADS)
      .forEach((entry) => storage.removeItem(entry.key));
    return true;
  } catch (error) {
    return false;
  }
};

export const readPendingAgentRun = ({
  threadId,
  now = Date.now(),
  storage = safeSessionStorage(),
} = {}) => {
  if (!storage || !threadId) return null;
  try {
    const raw = storage.getItem(pendingKey(threadId));
    if (!raw) return null;
    const pending = JSON.parse(raw);
    const valid = pending
      && typeof pending.threadId === 'string'
      && typeof pending.clientRequestId === 'string'
      && typeof pending.createdAt === 'number'
      && pending.payload
      && typeof pending.payload.message === 'string'
      && pending.payload.message.trim()
      && pending.payload.clientRequestId === pending.clientRequestId
      && ['ALL', 'EXPLICIT'].includes(pending.payload.selectionMode)
      && Array.isArray(pending.payload.projectIds)
      && (!pending.runtimeVersion || ['V1', 'V2'].includes(pending.runtimeVersion))
      && (!pending.requestKind || ['TURN', 'INTERACTION', 'RETRY'].includes(pending.requestKind))
      && (pending.requestKind !== 'RETRY' || (pending.runtimeVersion === 'V2'
        && typeof pending.retryTaskId === 'string' && pending.retryTaskId.trim()
        && Number.isInteger(pending.expectedTaskVersion) && pending.expectedTaskVersion > 0))
      && (pending.requestKind !== 'INTERACTION'
        || (typeof pending.interactionId === 'string'
          && pending.interactionId.trim()
          && pending.interactionAnswer
          && typeof pending.interactionAnswer === 'object'));
    const expired = valid && now - pending.createdAt > PENDING_AGENT_RUN_MAX_AGE_MS;
    const threadMismatch = valid && pending.threadId !== threadId;
    if (!valid || expired || threadMismatch) {
      clearPendingAgentRun(threadId, storage);
      return null;
    }
    return pending;
  } catch (error) {
    clearPendingAgentRun(threadId, storage);
    return null;
  }
};

export const abortableDelay = (milliseconds, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) {
    reject(new DOMException('Aborted', 'AbortError'));
    return;
  }
  const timer = setTimeout(resolve, milliseconds);
  signal?.addEventListener('abort', () => {
    clearTimeout(timer);
    reject(new DOMException('Aborted', 'AbortError'));
  }, { once: true });
});
