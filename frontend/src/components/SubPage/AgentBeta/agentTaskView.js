const asArray = (value) => Array.isArray(value) ? value : value ? [value] : [];

const firstText = (...values) => values.find((value) => typeof value === 'string' && value.trim()) || '';

const normalizedEventType = (event) => event?.type || event?.event || '';

const assistantShapeLabel = (value) => {
  const raw = firstText(value);
  const labels = {
    RENTAL_OPERATIONS_COPILOT: '建材租赁经营副驾',
    RENTAL_OPERATION_COPILOT: '建材租赁经营副驾',
  };
  return labels[raw.toUpperCase()] || raw || '建材租赁经营副驾';
};

const eventPayload = (event) => event?.payload && typeof event.payload === 'object'
  ? event.payload
  : {};

const stableKey = (value, fallback) => firstText(
  value?.id,
  value?.factId,
  value?.evidenceId,
  value?.artifactId,
  value?.toolCallId,
  value?.callId,
  value?.stepId,
  value?.toolCode,
  value?.capabilityCode,
  fallback,
);

const readableStatus = (value) => {
  const statuses = {
    CREATED: '已创建',
    OPEN: '已创建',
    GATHERING_INPUT: '等待补充',
    WAITING_INPUT: '等待补充',
    WAITING_USER: '等待补充',
    READY: '已就绪',
    PLANNED: '已规划',
    PLANNING: '规划中',
    EXECUTING: '执行中',
    RUNNING: '执行中',
    WAITING_APPROVAL: '等待审批',
    VERIFYING: '核验中',
    COMPLETED: '已完成',
    FAILED: '失败',
    BLOCKED: '受阻',
    CANCELLED: '已取消',
    SUCCEEDED: '已完成',
    PENDING: '待执行',
    SKIPPED: '已跳过',
  };
  return statuses[String(value || '').toUpperCase()] || value || '';
};

const normalizeCapability = (item, index) => ({
  code: firstText(item?.code, item?.capabilityCode, `capability-${index}`),
  name: firstText(item?.name, item?.displayName, item?.title, item?.code, '业务能力'),
  description: firstText(item?.description, item?.summary),
  enabled: item?.enabled !== false && item?.available !== false,
  available: item?.available !== false,
  unavailableReason: firstText(item?.unavailableReason, item?.reason),
  readOnly: item?.readOnly !== false,
  riskLevel: firstText(item?.riskLevel, item?.risk, item?.actionType, 'READ'),
  examples: asArray(item?.examples || item?.starters || item?.examplePrompts)
    .map((example) => typeof example === 'string' ? example : firstText(example?.text, example?.prompt, example?.title))
    .filter(Boolean),
  roles: asArray(item?.roles || item?.recommendedRoles).map((role) => String(role).toUpperCase()),
  requiredScope: firstText(item?.requiredScope, item?.scopeType),
});

export const normalizeCapabilityManifest = (capability = {}) => {
  const source = capability?.manifest || capability?.v2 || capability;
  const items = asArray(source?.capabilities || source?.items)
    .map(normalizeCapability)
    .filter((item) => item.enabled);
  return {
    version: firstText(
      source?.runtimeVersion,
      source?.runtime?.version,
      source?.version,
      capability?.runtimeVersion,
    ),
    mode: firstText(source?.mode, source?.runtime?.mode, capability?.mode),
    assistantName: firstText(source?.assistant?.name, '小云'),
    assistantShape: assistantShapeLabel(firstText(source?.assistant?.shape, source?.assistant?.description)),
    conversationFeatures: source?.conversationFeatures || {},
    writeEnabled: source?.writeEnabled === true,
    capabilities: items,
  };
};

const ROLE_KEYWORDS = {
  OWNER: ['owner', 'risk', 'enterprise', 'contract', 'receivable', '经营', '老板', '风险'],
  FINANCE: ['finance', 'settlement', 'reconciliation', 'receivable', 'payable', '财务', '结算', '对账'],
  PROJECT: ['project', 'contract', 'document', 'material', '项目', '合同', '单据'],
  WAREHOUSE: ['inventory', 'material', 'document', 'occupancy', '库存', '材料', '仓库'],
};

export const buildRoleStarters = ({ capability, role = 'OWNER', fallback = [] } = {}) => {
  const manifest = normalizeCapabilityManifest(capability);
  const keywords = ROLE_KEYWORDS[role] || [];
  const scored = manifest.capabilities.map((item, index) => {
    const haystack = `${item.code} ${item.name} ${item.description}`.toLowerCase();
    const explicitRole = item.roles.includes(role);
    const keywordScore = keywords.reduce((score, keyword) => score + (haystack.includes(keyword) ? 1 : 0), 0);
    return { item, index, score: explicitRole ? 100 : keywordScore };
  });
  const examples = scored
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .flatMap(({ item }) => item.examples)
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(0, 4);
  return examples.length ? examples : fallback.slice(0, 4);
};

const normalizePlanSteps = (payload) => {
  const plan = payload?.plan || payload;
  return asArray(plan?.steps || plan?.items || payload?.planSteps).map((step, index) => {
    if (typeof step === 'string') {
      return { id: `plan-${index}`, title: step, status: '', statusLabel: '' };
    }
    const status = firstText(step?.status, step?.state);
    return {
      id: stableKey(step, `plan-${index}`),
      title: firstText(step?.title, step?.name, step?.description, step?.goal, `步骤 ${index + 1}`),
      status,
      statusLabel: readableStatus(status),
      capabilityCode: firstText(step?.capabilityCode, step?.toolCode),
    };
  });
};

const normalizeInteraction = (payload) => {
  const source = payload?.interaction || payload;
  if (['ANSWERED', 'RESOLVED', 'CANCELLED', 'EXPIRED'].includes(String(source?.status || '').toUpperCase())) return null;
  const question = firstText(
    source?.question,
    source?.prompt,
    source?.promptText,
    source?.message,
    source?.clarificationQuestion,
    source?.title,
    source?.summary,
  );
  if (!question) return null;
  return {
    interactionId: firstText(source?.interactionId, source?.id),
    taskId: firstText(source?.taskId, payload?.taskId),
    type: firstText(source?.type, source?.interactionType, 'CLARIFICATION'),
    question,
    helpText: firstText(source?.helpText, source?.description),
    options: asArray(source?.options || source?.choices).map((option, index) => {
      if (typeof option === 'string') return { value: option, label: option };
      return {
        value: firstText(option?.value, option?.code, option?.id, `${index}`),
        label: firstText(option?.label, option?.name, option?.text, option?.value, `选项 ${index + 1}`),
        description: firstText(option?.description, option?.helpText),
      };
    }),
  };
};

const normalizeEvidence = (source, index, origin = '事实依据') => {
  const value = source?.fact || source?.evidence || source;
  if (!value || typeof value !== 'object') return null;
  const title = firstText(
    value?.title,
    value?.name,
    value?.label,
    value?.factType,
    value?.metricCode,
    value?.capabilityCode,
    origin,
  );
  const description = firstText(
    value?.summary,
    value?.description,
    value?.valueLabel,
    value?.scopeNote,
  );
  const recordCount = value?.recordCount ?? value?.records ?? value?.count;
  const timeRange = firstText(value?.timeRange, value?.asOfDate, value?.asOf, value?.evidenceDate);
  const projectIds = asArray(value?.projectIds || value?.scope?.projectIds);
  if (!title && !description && recordCount === undefined && !timeRange && !projectIds.length) return null;
  const semanticId = [
    firstText(value?.toolCode, value?.factType, value?.metricCode, value?.capabilityCode),
    firstText(value?.selectionMode, value?.scopeType),
    [...projectIds].map(String).sort().join(','),
    timeRange,
    recordCount === undefined ? '' : String(recordCount),
  ].join('|');
  return {
    id: firstText(
      value?.id,
      value?.factId,
      value?.evidenceId,
      semanticId.replace(/\|/g, '') ? semanticId : `evidence-${index}`,
    ),
    toolCode: firstText(value?.toolCode, value?.capabilityCode),
    title: title || origin,
    description,
    recordCount,
    timeRange,
    projectCount: projectIds.length,
    completeness: firstText(value?.completeness, value?.dataStatus),
    source: firstText(value?.source, value?.sourceType, value?.skillName),
  };
};

const collectMessageEvidence = (messages) => asArray(messages).flatMap((message, messageIndex) => {
  const metadata = message?.metadata || {};
  const values = [
    ...asArray(metadata?.evidence),
    ...asArray(metadata?.evidences),
    ...asArray(metadata?.references),
    ...asArray(metadata?.facts).flatMap((fact) => asArray(fact?.evidence)),
  ];
  return values.map((value, index) => normalizeEvidence(value, `${messageIndex}-${index}`, '回答依据')).filter(Boolean);
});

export const buildAgentTaskView = ({
  runEvents = [], currentTask, currentInteraction, messages = [], artifacts = [], capability,
} = {}) => {
  const capabilityNames = new Map(normalizeCapabilityManifest(capability).capabilities
    .map((item) => [item.code, item.name]));
  let task = currentTask ? { ...currentTask } : null;
  let plan = normalizePlanSteps(currentTask?.plan || {});
  let interaction = currentInteraction
    ? normalizeInteraction(currentInteraction)
    : currentTask?.interaction ? normalizeInteraction(currentTask.interaction) : null;
  const toolMap = new Map();
  const evidence = collectMessageEvidence(messages);
  const eventArtifacts = [];

  asArray(runEvents).forEach((event, index) => {
    const type = normalizedEventType(event);
    const payload = eventPayload(event);
    const eventTaskId = firstText(payload?.taskId, payload?.task?.taskId);
    if (currentTask?.taskId && eventTaskId && currentTask.taskId !== eventTaskId) return;
    if (['task.created', 'task.resolved', 'task.updated', 'task.interpreted', 'understanding.completed', 'turn.accepted'].includes(type)) {
      const source = payload?.task || (payload?.taskSpec ? {} : payload);
      const taskSpec = payload?.taskSpec || source?.taskSpec;
      const interpretation = payload?.interpretation || source?.interpretation;
      const scopeSnapshot = payload?.scopeSnapshot || payload?.scope || source?.scopeSnapshot || source?.scope;
      task = {
        ...(task || {}),
        ...source,
        ...(taskSpec ? { ...taskSpec, taskSpec } : {}),
        ...(interpretation ? { interpretation } : {}),
        ...(scopeSnapshot ? { scopeSnapshot } : {}),
        ...(payload?.taskId ? { taskId: payload.taskId } : {}),
      };
    }
    if (type === 'task.status') {
      task = {
        ...(task || {}),
        ...(payload?.task || {}),
        taskId: firstText(payload?.taskId, task?.taskId),
        status: firstText(payload?.status, payload?.state, task?.status),
        completionStatus: payload?.completionStatus || task?.completionStatus,
        requirementOutcomes: payload?.requirementOutcomes || task?.requirementOutcomes,
      };
    }
    if (['task.completed', 'task.blocked', 'task.failed'].includes(type)) {
      const status = type === 'task.completed' ? 'COMPLETED' : type === 'task.blocked' ? 'BLOCKED' : 'FAILED';
      task = { ...(task || {}), ...(payload?.task || payload), status };
    }
    if (type === 'task.plan' || type === 'plan.created') plan = normalizePlanSteps(payload);
    if (type === 'step.started' || type === 'step.completed' || type === 'step.failed') {
      const source = payload?.step || payload;
      const stepId = stableKey(source, `step-${index}`);
      const nextStatus = type === 'step.started' ? 'RUNNING'
        : type === 'step.completed' ? 'COMPLETED' : 'FAILED';
      const existingIndex = plan.findIndex((step) => step.id === stepId);
      const nextStep = {
        id: stepId,
        title: firstText(source?.title, source?.name, source?.description, `步骤 ${existingIndex >= 0 ? existingIndex + 1 : plan.length + 1}`),
        status: nextStatus,
        statusLabel: readableStatus(nextStatus),
        capabilityCode: firstText(source?.capabilityCode, source?.toolCode),
      };
      if (existingIndex >= 0) plan = plan.map((step, stepIndex) => stepIndex === existingIndex ? { ...step, ...nextStep } : step);
      else plan = [...plan, nextStep];
    }
    if (['clarification.required', 'clarification.requested', 'interaction.required', 'approval.requested'].includes(type)) {
      interaction = normalizeInteraction(payload);
    }
    if (['interaction.resolved', 'clarification.resolved', 'approval.resolved'].includes(type)) interaction = null;
    if (type.startsWith('tool.')) {
      const key = stableKey(payload, `${firstText(payload?.toolCode, payload?.name, 'tool')}-${index}`);
      const previous = toolMap.get(key) || {};
      const status = type === 'tool.started' ? 'RUNNING'
        : type === 'tool.completed' ? 'COMPLETED'
          : type === 'tool.failed' ? 'FAILED' : firstText(payload?.status, previous.status);
      toolMap.set(key, {
        ...previous,
        id: key,
        name: firstText(
          payload?.displayName,
          payload?.toolName,
          payload?.name,
          payload?.capabilityName,
          capabilityNames.get(firstText(payload?.toolCode, payload?.capabilityCode)),
          payload?.toolCode,
          previous.name,
          '业务能力',
        ),
        code: firstText(payload?.toolCode, payload?.capabilityCode, previous.code),
        status,
        statusLabel: readableStatus(status),
        summary: firstText(payload?.summary, payload?.message, payload?.resultSummary, payload?.errorMessage, previous.summary),
        seq: Number(event?.seq || event?.id || index),
      });
      if (type === 'tool.completed' && payload?.evidence) {
        asArray(payload.evidence).forEach((value, evidenceIndex) => {
          const item = normalizeEvidence(value, `${index}-tool-${evidenceIndex}`);
          if (item) evidence.push(item);
        });
      }
    }
    if (['fact.available', 'evidence.available', 'evidence.updated'].includes(type)) {
      const item = normalizeEvidence(payload, index);
      if (item) evidence.push(item);
    }
    if (type === 'artifact.created') {
      eventArtifacts.push(payload?.artifact || payload);
    }
  });

  const uniqueEvidence = Array.from(new Map(evidence.map((item) => [item.id, item])).values())
    .map((item) => ({
      ...item,
      title: capabilityNames.get(item.toolCode) || item.title,
    }));
  const normalizedArtifacts = [...asArray(artifacts), ...eventArtifacts].map((item, index) => ({
    ...item,
    artifactId: firstText(item?.artifactId, item?.id, `artifact-${index}`),
    title: firstText(item?.title, item?.name, '小云产物'),
  }));
  const latestAnswer = [...asArray(messages)].reverse().find((message) => message?.role === 'assistant'
    && message?.metadata?.taskId === task?.taskId)?.metadata;
  const completionStatus = firstText(task?.completionStatus, task?.outcome?.completionStatus, latestAnswer?.completionStatus);
  const requirementOutcomes = asArray(task?.requirementOutcomes || task?.outcome?.requirementOutcomes || latestAnswer?.requirementOutcomes);
  const taskSpec = task?.taskSpec || task?.spec || task || {};
  const scopeSnapshot = task?.scopeSnapshot || taskSpec?.scopeSnapshot || task?.scope || taskSpec?.scope;
  const scopeProjectIds = asArray(scopeSnapshot?.effectiveProjectIds || scopeSnapshot?.projectIds);
  const activeTaskId = firstText(currentTask?.taskId, task?.taskId);
  const activeTaskStatus = firstText(task?.taskStatus, task?.status, task?.state).toUpperCase();
  const snapshotStatus = firstText(currentTask?.taskStatus, currentTask?.status, currentTask?.state).toUpperCase();
  if ((interaction?.taskId && activeTaskId && interaction.taskId !== activeTaskId)
      || [activeTaskStatus, snapshotStatus].some((status) => ['COMPLETED', 'BLOCKED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status))) interaction = null;
  const selectionMode = firstText(
    scopeSnapshot?.effectiveSelectionMode,
    scopeSnapshot?.selectionMode,
    scopeSnapshot?.requestedSelectionMode,
    scopeSnapshot?.mode,
  ).toUpperCase();
  const scopeProjectCount = Number(scopeSnapshot?.projectCount) || scopeProjectIds.length;
  const scopeLabel = firstText(scopeSnapshot?.label, scopeSnapshot?.scopeLabel, scopeSnapshot?.summary)
    || (scopeSnapshot && selectionMode === 'ALL'
      ? `任务冻结范围：全部授权项目${scopeProjectCount ? `（${scopeProjectCount} 个）` : ''}`
      : scopeSnapshot && scopeProjectCount
        ? `任务冻结范围：${scopeProjectCount} 个项目`
        : '');
  return {
    task: task ? {
      ...task,
      taskId: firstText(task?.taskId, task?.id),
      goal: firstText(
        taskSpec?.resolvedGoal,
        task?.resolvedGoal,
        task?.goal,
        taskSpec?.goal,
        task?.interpretation?.taskSpec?.resolvedGoal,
        task?.interpretation?.goal,
        task?.summary,
      ),
      status: firstText(task?.taskStatus, task?.status, task?.state),
      statusLabel: completionStatus === 'PARTIAL' ? '部分完成' : readableStatus(firstText(task?.taskStatus, task?.status, task?.state)),
      completionStatus,
      requirementOutcomes,
      dialogueAct: firstText(taskSpec?.dialogueAct, task?.dialogueAct),
      scopeSnapshot,
      scopeLabel,
    } : null,
    plan,
    interaction,
    tools: Array.from(toolMap.values()).sort((left, right) => left.seq - right.seq),
    evidence: uniqueEvidence,
    artifacts: Array.from(new Map(normalizedArtifacts.map((item) => [item.artifactId, item])).values()),
  };
};

export const agentTaskStatusLabel = readableStatus;
