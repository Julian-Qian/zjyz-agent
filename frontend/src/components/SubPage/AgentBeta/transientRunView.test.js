import {
  buildAssistantExceptionGuide,
  buildExecutionProgressView,
  buildTransientRunView,
} from './transientRunView';

test('terminal reconcile failure keeps its partial answer and recovery notice visible', () => {
  expect(buildTransientRunView({
    sending: false,
    terminalReconcilePending: true,
    streamingContent: '已收到的流式回答',
    statusMessage: '任务已结束，回答列表暂未同步',
  })).toEqual({
    visible: true,
    working: false,
    content: '已收到的流式回答',
    label: '任务已结束，回答列表暂未同步',
  });
});

test('successful or ordinary terminal state does not render a duplicate transient answer', () => {
  expect(buildTransientRunView({
    sending: false,
    terminalReconcilePending: false,
    streamingContent: '旧的临时回答',
    statusMessage: '已完成',
  }).visible).toBe(false);
});

test('active sending keeps the existing working presentation', () => {
  expect(buildTransientRunView({
    sending: true,
    terminalReconcilePending: false,
    streamingContent: '正在生成',
    statusMessage: '',
  })).toEqual({
    visible: true,
    working: true,
    content: '正在生成',
    label: '正在工作',
  });
});

test('terminal reconcile presentation wins even if a recovery thunk is marked sending', () => {
  expect(buildTransientRunView({
    sending: true,
    terminalReconcilePending: true,
    streamingContent: '已有回答',
    statusMessage: '回答列表暂未同步',
  })).toEqual({
    visible: true,
    working: false,
    content: '已有回答',
    label: '回答列表暂未同步',
  });
});

test('buildExecutionProgressView maps running status and live tools into multi-stage progress', () => {
  const progress = buildExecutionProgressView({
    status: 'RUNNING',
    statusMessage: '正在核对项目材料台账',
    working: true,
    tools: [
      { key: 'tool-1', title: '项目材料占用查询', status: 'COMPLETED' },
      { key: 'tool-2', title: '财务结算单预览', status: 'RUNNING' },
    ],
  });

  expect(progress.headline).toBe('正在核对项目材料台账');
  expect(progress.stages).toEqual([
    { key: 'understand', label: '理解诉求与范围', state: 'done' },
    { key: 'execute', label: '检索台账与业务核算', state: 'active' },
    { key: 'synthesize', label: '核验依据与生成结论', state: 'pending' },
  ]);
  expect(progress.activeItems).toEqual([
    { key: 'tool-1', title: '项目材料占用查询', detail: '', state: 'done', statusLabel: '已完成' },
    { key: 'tool-2', title: '财务结算单预览', detail: '', state: 'active', statusLabel: '执行中' },
  ]);
});

test('buildAssistantExceptionGuide identifies connection failures and boundary blocks', () => {
  const connGuide = buildAssistantExceptionGuide({
    content: '小云的智能理解服务暂时无法连接，本次没有执行任何业务变更。',
    metadata: { taskStatus: 'BLOCKED' },
    isAllProjects: true,
  });
  expect(connGuide?.type).toBe('connection');
  expect(connGuide?.canRetryLastQuestion).toBe(true);
  expect(connGuide?.suggestions.length).toBeGreaterThan(0);

  const boundaryGuide = buildAssistantExceptionGuide({
    content: '暂时没有找到能回答这个问题的可靠依据，请补充具体项目、单据或时间范围后再试。',
    metadata: { taskStatus: 'BLOCKED' },
    isAllProjects: false,
  });
  expect(boundaryGuide?.type).toBe('boundary');
  expect(boundaryGuide?.canRetryLastQuestion).toBe(false);
  expect(boundaryGuide?.suggestions[0].label).toBe('未归还材料盘点');

  expect(buildAssistantExceptionGuide({
    content: '正常业务结论',
    metadata: { taskStatus: 'COMPLETED' },
  })).toBeNull();
});

