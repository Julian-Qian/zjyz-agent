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
