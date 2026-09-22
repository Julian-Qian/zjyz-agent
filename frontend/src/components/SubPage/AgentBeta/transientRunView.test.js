import { buildTransientRunView } from './transientRunView';

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
