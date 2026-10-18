import {
  buildAgentTaskView,
  buildRoleStarters,
  normalizeCapabilityManifest,
} from './agentTaskView';

test('a queued child never replays its parent clarification or status', () => {
  const view = buildAgentTaskView({
    currentTask: { taskId: 'child', status: 'QUEUED', goal: '小何负责哪些项目' },
    runEvents: [
      { type: 'task.status', payload: { taskId: 'parent', status: 'WAITING_USER' } },
      { type: 'clarification.required', payload: { taskId: 'parent', interactionId: 'old', prompt: '系统是否支持？' } },
    ],
    currentInteraction: { taskId: 'parent', interactionId: 'old', question: '系统是否支持？' },
  });
  expect(view.task.taskId).toBe('child');
  expect(view.task.status).toBe('QUEUED');
  expect(view.interaction).toBeNull();
});

test('answered interactions and terminal task clarifications are hidden', () => {
  expect(buildAgentTaskView({ currentInteraction: { status: 'ANSWERED', prompt: '姓名？' } }).interaction).toBeNull();
  expect(buildAgentTaskView({
    currentTask: { taskId: 't-1', status: 'COMPLETED' },
    currentInteraction: { taskId: 't-1', status: 'PENDING', prompt: '姓名？' },
  }).interaction).toBeNull();
});

test('the current pending clarification remains visible with persisted promptText', () => {
  const view = buildAgentTaskView({
    currentTask: { taskId: 't-1', status: 'WAITING_USER' },
    currentInteraction: { taskId: 't-1', status: 'PENDING', promptText: '小何在系统登记的姓名是什么？' },
  });
  expect(view.interaction.question).toContain('登记的姓名');
});

test('normalizes a v2 capability manifest and uses backend examples for role starters', () => {
  const capability = {
    v2: {
      version: '2.0',
      assistant: { name: '小云', shape: 'RENTAL_OPERATIONS_COPILOT' },
      capabilities: [
        { code: 'inventory.snapshot', name: '库存概况', enabled: true, examples: ['查询企业库存概况'] },
        { code: 'finance.receivable', name: '应收分析', enabled: true, examples: ['列出逾期未收项目'] },
        { code: 'finance.collection', name: '应收催缴', enabled: true, available: false, examples: ['生成催缴清单'] },
        { code: 'write.payment', name: '登记收款', enabled: false, examples: ['登记一笔收款'] },
      ],
    },
  };

  expect(normalizeCapabilityManifest(capability).capabilities).toHaveLength(2);
  expect(normalizeCapabilityManifest(capability).assistantShape).toBe('建材租赁经营副驾');
  expect(buildRoleStarters({ capability, role: 'FINANCE' })[0]).toBe('列出逾期未收项目');
});

test('deprioritizes single-project starter prompts when isAllProjects is true', () => {
  const capability = {
    v2: {
      version: '2.0',
      capabilities: [
        { code: 'owner.action_center', name: '老板行动中心', enabled: true, roles: ['OWNER'], requiredScope: 'ALL_OR_SELECTED', examples: ['今天最需要我处理什么'] },
        { code: 'copilot.contract_execution_audit', name: '合同合规审查', enabled: true, roles: ['OWNER'], requiredScope: 'PROJECT_SELECTION', examples: ['核查当前项目的合同定价与实际发料合规性'] },
        { code: 'advisor.counterparty_credit_profile', name: '合作方信用画像', enabled: true, roles: ['OWNER'], requiredScope: 'ALL_OR_SELECTED', examples: ['生成客户与供应商360度信用风控画像'] },
        { code: 'copilot.order_fulfillment_eval', name: '接单评估', enabled: true, roles: ['OWNER'], requiredScope: 'ALL_OR_SELECTED', examples: ['评估这个工程的材料需求本企业库存够不够，缺口推荐商城供应商'] },
        { code: 'advisor.sublease_arbitrage_radar', name: '外租止损雷达', enabled: true, roles: ['OWNER'], requiredScope: 'ALL_OR_SELECTED', examples: ['检查哪些外租材料可以用仓库闲置库存替换退租省钱'] },
      ],
    },
  };

  const allProjectsStarters = buildRoleStarters({ capability, role: 'OWNER', isAllProjects: true });
  expect(allProjectsStarters).toHaveLength(4);
  expect(allProjectsStarters).not.toContain('核查当前项目的合同定价与实际发料合规性');

  const singleProjectStarters = buildRoleStarters({ capability, role: 'OWNER', isAllProjects: false });
  expect(singleProjectStarters[0]).toBe('核查当前项目的合同定价与实际发料合规性');
});

test('builds task plan tool and evidence views only from real server data', () => {
  const view = buildAgentTaskView({
    capability: {
      runtimeVersion: 'V2',
      capabilities: [{ code: 'project.material.occupancy', name: '材料占用台账', enabled: true }],
    },
    currentTask: { taskId: 'task-1', status: 'EXECUTING', taskSpec: { goal: '核对未归还材料' } },
    runEvents: [
      { seq: 1, type: 'task.plan', payload: { steps: [{ id: 'step-1', title: '查询材料占用', status: 'RUNNING' }] } },
      { seq: 2, type: 'tool.started', payload: { toolCallId: 'call-1', displayName: '材料占用台账' } },
      { seq: 3, type: 'fact.available', payload: { factId: 'fact-1', title: '未归还材料', recordCount: 12, asOfDate: '2026-08-28' } },
      { seq: 4, type: 'tool.completed', payload: { toolCallId: 'call-1', displayName: '材料占用台账', summary: '已核对 12 条记录' } },
    ],
  });

  expect(view.task.goal).toBe('核对未归还材料');
  expect(view.plan[0].title).toBe('查询材料占用');
  expect(view.tools).toEqual([expect.objectContaining({ id: 'call-1', status: 'COMPLETED' })]);
  expect(view.evidence).toEqual([expect.objectContaining({ id: 'fact-1', recordCount: 12 })]);
});

test('collects and deduplicates tool and durable fact evidence', () => {
  const sharedEvidence = {
    toolCode: 'project.material.occupancy',
    selectionMode: 'EXPLICIT',
    projectIds: ['project-1'],
    timeRange: '截至 2026-08-29',
    recordCount: 8,
  };
  const view = buildAgentTaskView({
    currentTask: {
      taskId: 'task-evidence',
      status: 'COMPLETED',
      scopeSnapshot: {
        requestedSelectionMode: 'EXPLICIT',
        effectiveSelectionMode: 'EXPLICIT',
        projectIds: ['project-1'],
        projectCount: 1,
      },
    },
    runEvents: [{
      seq: 1,
      type: 'tool.completed',
      payload: { toolCallId: 'call-1', displayName: '材料占用台账', evidence: sharedEvidence },
    }],
    messages: [{
      role: 'assistant',
      metadata: { facts: [{ toolCode: 'project.material.occupancy', evidence: sharedEvidence }] },
    }],
  });

  expect(view.evidence).toHaveLength(1);
  expect(view.evidence[0]).toEqual(expect.objectContaining({ recordCount: 8, projectCount: 1 }));
  expect(view.task.scopeLabel).toBe('任务冻结范围：1 个项目');
});

test('keeps distinct evidence rows from the same capability while deduplicating identical facts', () => {
  const evidence = (projectId) => ({
    toolCode: 'project.material.occupancy',
    selectionMode: 'EXPLICIT',
    projectIds: [projectId],
    timeRange: '截至 2026-08-29',
    recordCount: 4,
  });
  const view = buildAgentTaskView({
    capability: {
      runtimeVersion: 'V2',
      capabilities: [{ code: 'project.material.occupancy', name: '材料占用台账', enabled: true }],
    },
    runEvents: [
      { seq: 1, type: 'tool.completed', payload: { toolCode: 'project.material.occupancy', evidence: evidence('project-a') } },
      { seq: 2, type: 'tool.completed', payload: { toolCode: 'project.material.occupancy', evidence: evidence('project-b') } },
    ],
    messages: [{ metadata: { facts: [{ evidence: evidence('project-a') }] } }],
  });

  expect(view.evidence).toHaveLength(2);
  expect(view.evidence.map((item) => item.title)).toEqual(['材料占用台账', '材料占用台账']);
});

test('uses the backend effective ALL scope and project count for the frozen task label', () => {
  const view = buildAgentTaskView({
    currentTask: {
      taskId: 'task-all',
      scopeSnapshot: {
        requestedSelectionMode: 'ALL',
        effectiveSelectionMode: 'ALL',
        projectIds: [],
        projectCount: 9,
      },
    },
  });

  expect(view.task.scopeLabel).toBe('任务冻结范围：全部授权项目（9 个）');
});

test('prefers the resolved multi-turn goal over the short follow-up utterance', () => {
  const view = buildAgentTaskView({
    currentTask: {
      taskId: 'task-follow-up',
      goal: '继续',
      taskSpec: { goal: '继续', resolvedGoal: '继续核对上一轮项目，并按材料数量排行' },
    },
  });

  expect(view.task.goal).toBe('继续核对上一轮项目，并按材料数量排行');
});

test('normalizes a structured clarification without inventing choices', () => {
  const view = buildAgentTaskView({
    runEvents: [{
      seq: 1,
      type: 'clarification.required',
      payload: {
        interactionId: 'interaction-1',
        question: '你想按数量还是金额排行？',
        options: [{ value: 'QUANTITY', label: '按数量' }, { value: 'AMOUNT', label: '按金额' }],
      },
    }],
  });

  expect(view.interaction.question).toContain('数量');
  expect(view.interaction.options.map((item) => item.label)).toEqual(['按数量', '按金额']);
});

test('empty event history produces no fake plan tool or evidence rows', () => {
  const view = buildAgentTaskView({ currentTask: { taskId: 'task-1', status: 'OPEN' } });
  expect(view.plan).toEqual([]);
  expect(view.tools).toEqual([]);
  expect(view.evidence).toEqual([]);
});
