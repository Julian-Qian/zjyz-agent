export const buildTransientRunView = ({
  sending,
  terminalReconcilePending,
  streamingContent,
  statusMessage,
} = {}) => {
  if (terminalReconcilePending) {
    return {
      visible: true,
      working: false,
      content: streamingContent || '',
      label: statusMessage || '任务已结束，最终回答待同步',
    };
  }
  if (sending) {
    return {
      visible: true,
      working: true,
      content: streamingContent || '',
      label: statusMessage || '正在工作',
    };
  }
  return {
    visible: false,
    working: false,
    content: '',
    label: '',
  };
};

const normalizeStepState = (rawStatus = '') => {
  const value = String(rawStatus || '').toUpperCase();
  if (['COMPLETED', 'SUCCEEDED', 'SUCCESS', 'DONE'].includes(value)) return 'done';
  if (['FAILED', 'ERROR', 'BLOCKED', 'CANCELLED'].includes(value)) return 'error';
  return 'active';
};

export const buildExecutionProgressView = ({
  status = 'QUEUED',
  statusMessage = '',
  working = false,
  reconcilePending = false,
  plan = [],
  tools = [],
} = {}) => {
  const normalizedStatus = String(status || 'QUEUED').toUpperCase();
  const safeTools = Array.isArray(tools) ? tools : [];
  const safePlan = Array.isArray(plan) ? plan : [];

  let stageStates = ['active', 'pending', 'pending'];
  if (reconcilePending && !working) {
    stageStates = ['done', 'done', 'done'];
  } else if (normalizedStatus === 'FINALIZING') {
    stageStates = ['done', 'done', 'active'];
  } else if (
    normalizedStatus === 'RUNNING'
    || normalizedStatus === 'PLANNING'
    || safeTools.length > 0
    || safePlan.length > 0
  ) {
    stageStates = ['done', 'active', 'pending'];
  }

  const defaultHeadline = reconcilePending && !working
    ? '回答已生成，正在同步最终会话记录…'
    : normalizedStatus === 'FINALIZING'
      ? '正在核验业务依据并整理最终回答…'
      : normalizedStatus === 'RUNNING'
        ? '正在调取项目台账与执行业务核算…'
        : normalizedStatus === 'PLANNING'
          ? '正在解析问题意图并规划查询步骤…'
          : '已接收指令，正在准备项目上下文…';

  const sourceItems = safeTools.length ? safeTools : safePlan;
  const activeItems = sourceItems.slice(-4).map((item, index) => {
    const state = normalizeStepState(item?.status);
    return {
      key: item?.key || item?.stepId || `${item?.title || 'step'}-${index}`,
      title: item?.title || item?.toolName || '业务数据查询',
      detail: item?.detail || '',
      state,
      statusLabel: state === 'done' ? '已完成' : state === 'error' ? '异常' : '执行中',
    };
  });

  return {
    headline: (statusMessage && String(statusMessage).trim()) || defaultHeadline,
    stages: [
      { key: 'understand', label: '理解诉求与范围', state: stageStates[0] },
      { key: 'execute', label: '检索台账与业务核算', state: stageStates[1] },
      { key: 'synthesize', label: '核验依据与生成结论', state: stageStates[2] },
    ],
    activeItems,
  };
};

const ALL_PROJECTS_FALLBACK_SUGGESTIONS = [
  { label: '老板行动中心巡检', prompt: '请打开老板行动中心，列出当前风险最高的项目，说明每个项目的风险分、逾期应收、超期未归还材料和建议优先动作。' },
  { label: '逾期应收催缴清单', prompt: '截至今天，生成今年及历史结转的逾期未付款项目催缴清单' },
  { label: '全量库存与低库存预警', prompt: '查询企业当前库存概况和低库存材料' },
];

const SINGLE_PROJECT_FALLBACK_SUGGESTIONS = [
  { label: '未归还材料盘点', prompt: '查询当前项目仍在租或未归还的材料，按材料汇总应还数量和超期情况' },
  { label: '财务结算单试算', prompt: '帮我做一份当前项目截至今天的财务结算单预览，包含租金、赔偿金、历史已收和本期应收，并拆出结算差额五因子归因' },
  { label: '物料对账单预览', prompt: '帮我生成当前项目截至今天的物料对账单预览，核对发出、归还与在租结存明细' },
];

export const buildAssistantExceptionGuide = ({
  content = '',
  metadata = {},
  isAllProjects = true,
} = {}) => {
  const text = String(content || '').trim();
  const hasCards = Array.isArray(metadata?.cards) && metadata.cards.length > 0;
  if (hasCards) return null;

  if (text.includes('智能理解服务暂时无法连接') || text.includes('服务暂时不可用')) {
    return {
      type: 'connection',
      badge: '模型连接超时 · 未产生业务变更',
      summary: '跨项目或多步工具聚合耗时较长时可能偶发连接中断，可直接重新尝试或切换更聚焦的提问。',
      canRetryLastQuestion: true,
      suggestions: isAllProjects ? ALL_PROJECTS_FALLBACK_SUGGESTIONS : SINGLE_PROJECT_FALLBACK_SUGGESTIONS,
    };
  }

  if (
    text.includes('暂时没有找到能回答这个问题的可靠依据')
    || metadata?.taskStatus === 'BLOCKED'
  ) {
    return {
      type: 'boundary',
      badge: '超出当前工具边界或缺少范围依据',
      summary: '小云遵循零幻觉原则，对未接入台账的字段（如报停单明细、押金覆盖率）会主动拦截。可直接点击下方受支持的业务指令：',
      canRetryLastQuestion: false,
      suggestions: isAllProjects ? ALL_PROJECTS_FALLBACK_SUGGESTIONS : SINGLE_PROJECT_FALLBACK_SUGGESTIONS,
    };
  }

  return null;
};

