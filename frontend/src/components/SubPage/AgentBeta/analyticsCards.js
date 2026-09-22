// contract-commercial-analytics 与 material-lifecycle-analytics 卡的纯函数规整器。
// 两卡均为分区结构：每个分区带 totalCount/displayedCount/truncated，前端只展示不计算。

const toCount = (value) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
};

const toText = (value) => (typeof value === 'string' ? value : '');

const toSection = (value, mapItem) => {
  const source = value && typeof value === 'object' ? value : {};
  return {
    totalCount: toCount(source.totalCount),
    displayedCount: toCount(source.displayedCount),
    truncated: source.truncated === true,
    items: Array.isArray(source.items) ? source.items.map(mapItem) : [],
  };
};

export const CONTRACT_ISSUE_LABELS = {
  NO_CONTRACT: '未录入合同',
  MISSING_END_DATE: '缺结束日期',
  MISSING_RECONCILIATION_PERIOD: '缺结算周期',
  MISSING_TAX_RATE: '缺税率',
  MISSING_OVERDUE_INCREASE_RATE: '缺超期递增率',
};

export const normalizeContractCommercialCard = (card) => {
  const source = card || {};
  return {
    asOfDate: toText(source.asOfDate),
    projectCount: toCount(source.projectCount),
    completeness: toSection(source.completeness, (item) => ({
      projectName: toText(item?.projectName),
      contractName: toText(item?.contractName),
      issueLabels: Array.isArray(item?.issues)
        ? item.issues.map((issue) => CONTRACT_ISSUE_LABELS[issue] || toText(issue)).filter(Boolean)
        : [],
    })),
    priceComparison: toSection(source.priceComparison, (item) => ({
      materialLabel: `${toText(item?.materialName)}${item?.materialSpecification ? ` ${toText(item.materialSpecification)}` : ''}`.trim(),
      countingUnit: toText(item?.countingUnit),
      projectCount: toCount(item?.projectCount),
      minDailyRent: item?.minDailyRent == null ? null : Number(item.minDailyRent),
      minProjectName: toText(item?.minProjectName),
      maxDailyRent: item?.maxDailyRent == null ? null : Number(item.maxDailyRent),
      maxProjectName: toText(item?.maxProjectName),
    })),
    scopeNote: toText(source.scopeNote)
      || '价格比较按材料名称+规格+计数单位分组；v1 不含合同价与单据执行价偏差核对。',
  };
};

export const normalizeMaterialLifecycleCard = (card) => {
  const source = card || {};
  return {
    asOfDate: toText(source.asOfDate),
    minStagnantDays: toCount(source.minStagnantDays),
    overReturn: toSection(source.overReturn, (item) => ({
      projectName: toText(item?.projectName),
      materialLabel: `${toText(item?.materialName)}${item?.materialSpecification ? ` ${toText(item.materialSpecification)}` : ''}`.trim(),
      materialUnit: toText(item?.materialUnit),
      outstandingQuantity: item?.outstandingQuantity == null ? null : Number(item.outstandingQuantity),
    })),
    staleOccupancy: toSection(source.staleOccupancy, (item) => ({
      projectName: toText(item?.projectName),
      managerName: toText(item?.managerName),
      outstandingMaterialGroups: toCount(item?.outstandingMaterialGroups),
      lastReturnDate: toText(item?.lastReturnDate),
      staleDays: item?.staleDays == null ? null : Number(item.staleDays),
    })),
    compensationRate: toSection(source.compensationRate, (item) => ({
      materialLabel: `${toText(item?.materialName)}${item?.materialSpecification ? ` ${toText(item.materialSpecification)}` : ''}`.trim(),
      materialUnit: toText(item?.materialUnit),
      rentedQuantity: toCount(item?.rentedQuantity),
      compensatedQuantity: toCount(item?.compensatedQuantity),
      compensationRate: item?.compensationRate == null ? null : Number(item.compensationRate),
    })),
    scopeNote: toText(source.scopeNote)
      || '仅覆盖租出方向；数量按名称+规格+计数单位分组，不做跨单位合计。',
  };
};
