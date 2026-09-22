// capability-boundary 卡的纯函数规整器：Guard 未通过时后端输出的能力边界说明。
// 该卡不包含业务数据，只声明缺什么、为什么不能下结论、下一步做什么。

export const normalizeCapabilityBoundaryCard = (card) => {
  const source = card || {};
  const status = source.status === 'UNSUPPORTED' ? 'UNSUPPORTED' : 'MISSING_REQUIRED_EVIDENCE';
  const missingTools = Array.isArray(source.missingTools)
    ? source.missingTools
      .map((item) => {
        const toolCode = typeof item?.toolCode === 'string' ? item.toolCode : '';
        const name = typeof item?.name === 'string' && item.name ? item.name : toolCode;
        return { toolCode, name };
      })
      .filter((item) => item.name)
    : [];
  return {
    status,
    statusLabel: status === 'UNSUPPORTED' ? '当前能力不支持' : '关键证据缺失',
    missingTools,
    nextStep: typeof source.nextStep === 'string' ? source.nextStep : '',
    scopeNote: typeof source.scopeNote === 'string' && source.scopeNote
      ? source.scopeNote
      : '本卡为能力边界说明，不包含业务数据。',
  };
};

export default normalizeCapabilityBoundaryCard;
