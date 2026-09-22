const safeArray = (value) => (Array.isArray(value) ? value : []);

const countValue = (value, fallback = 0) => {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : fallback;
};

const normalizeContribution = (item) => ({
  code: item?.code || '',
  label: item?.label || item?.code || '评分项',
  points: countValue(item?.points),
  rawValue: item?.rawValue,
});

const normalizeRisk = (risk) => {
  const scoreContributions = safeArray(risk?.scoreContributions).map(normalizeContribution);
  const score = countValue(risk?.score);
  return {
    ...risk,
    riskType: risk?.riskType || 'UNKNOWN',
    rawMetrics: risk?.rawMetrics && typeof risk.rawMetrics === 'object' ? risk.rawMetrics : {},
    score,
    scoreContributions,
    scoreReproducible: scoreContributions.reduce((sum, item) => sum + item.points, 0) === score,
  };
};

const normalizeProject = (item) => {
  const risks = safeArray(item?.risks).map(normalizeRisk);
  const scoreContributions = safeArray(item?.scoreContributions).map(normalizeContribution);
  const totalScore = countValue(item?.totalScore);
  return {
    ...item,
    risks,
    riskTypes: safeArray(item?.riskTypes),
    riskCount: countValue(item?.riskCount, risks.length),
    totalScore,
    scoreContributions,
    scoreReproducible: scoreContributions.reduce((sum, entry) => sum + entry.points, 0) === totalScore,
  };
};

export const OWNER_RISK_LABELS = {
  CONTRACT_EXPIRED: '合同风险',
  MATERIAL_OUTSTANDING: '材料未归还',
  UNRECONCILED: '长期未对账',
  OVERDUE_RECEIVABLE: '逾期应收',
  INACTIVE_PROJECT: '长期无活动',
  INVENTORY_ANOMALY: '库存异常',
  PENDING_REVIEW: '待审核单据',
};

export const ownerRiskMetricSummary = (risk) => {
  const metrics = risk?.rawMetrics || {};
  if (risk?.riskType === 'CONTRACT_EXPIRED') {
    const labels = {
      EXPIRED: `已逾期 ${countValue(metrics.overdueDays)} 天`,
      EXPIRING_SOON: `剩余 ${countValue(metrics.daysRemaining)} 天`,
      NO_END_DATE: '合同未填截止日期',
      NO_CONTRACT: '未找到合同',
      INVALID_END_DATE: '合同截止日期格式异常',
    };
    return labels[metrics.contractStatus] || '合同状态待核实';
  }
  if (risk?.riskType === 'MATERIAL_OUTSTANDING') {
    const groups = safeArray(metrics.unitGroups).map((group) => (
      `${group?.unit || '未填写单位'} ${group?.outstandingQuantity ?? '--'}`
    ));
    return `${countValue(metrics.outstandingMaterialKinds)} 种${groups.length ? `；按单位：${groups.join('，')}` : ''}`;
  }
  if (risk?.riskType === 'UNRECONCILED') return `${countValue(metrics.unreconciledDays)} 天未对账`;
  if (risk?.riskType === 'OVERDUE_RECEIVABLE') return `逾期本金 ¥${Number(metrics.overdueOutstanding || 0).toLocaleString('zh-CN')}`;
  if (risk?.riskType === 'INACTIVE_PROJECT') return `${countValue(metrics.inactiveDays)} 天无已复核活动`;
  if (risk?.riskType === 'PENDING_REVIEW') return `${countValue(metrics.pendingReviewCount)} 张待审核`;
  return '详见原始指标';
};

export const normalizeOwnerActionCenterCard = (card) => {
  const items = safeArray(card?.items).map(normalizeProject);
  const topActions = safeArray(card?.topActions).map(normalizeRisk);
  const failedDimensions = safeArray(card?.failedDimensions);
  return {
    schemaVersion: card?.schemaVersion || '1.0',
    scoringVersion: card?.scoringVersion || '',
    asOfDate: card?.asOfDate || '',
    countEntity: card?.countEntity || 'PROJECT',
    scoreIncomplete: card?.scoreIncomplete === true,
    rankingBasis: card?.rankingBasis || 'ALL_REQUESTED_DIMENSIONS',
    query: card?.query || {},
    totalProjectCount: countValue(card?.totalProjectCount),
    projectRiskCount: countValue(card?.projectRiskCount),
    projectTotalCount: countValue(card?.projectTotalCount, countValue(card?.totalCount, items.length)),
    displayedProjectCount: countValue(card?.displayedProjectCount, countValue(card?.displayedCount, items.length)),
    actionTotalCount: countValue(card?.actionTotalCount, topActions.length),
    actionDisplayedCount: countValue(card?.actionDisplayedCount, topActions.length),
    totalCount: countValue(card?.totalCount, items.length),
    displayedCount: countValue(card?.displayedCount, items.length),
    truncated: card?.truncated === true,
    actionTruncated: card?.actionTruncated === true,
    limit: countValue(card?.limit),
    items,
    topActions,
    failedDimensions,
    unsupportedDimensions: safeArray(card?.unsupportedDimensions),
    dimensionStatuses: safeArray(card?.dimensionStatuses),
    warnings: safeArray(card?.warnings).filter((item) => typeof item === 'string' && item.trim()),
    scopeNote: card?.scopeNote || '',
  };
};

export const ownerActionBoundaryView = (card) => {
  const failed = safeArray(card?.failedDimensions);
  const unsupported = safeArray(card?.unsupportedDimensions);
  if (failed.length) {
    return {
      title: '部分风险维度核算失败',
      description: '当前仅按可用维度排序（AVAILABLE_DIMENSIONS），不能视为完整或最高风险排名。',
      emptyText: '当前可评估维度中没有匹配项目；失败维度不能据此判断为无风险。',
    };
  }
  if (unsupported.length || card?.scoreIncomplete === true) {
    return {
      title: '部分风险维度暂不可评估',
      description: '当前仅按可用维度排序（AVAILABLE_DIMENSIONS），不能视为完整或最高风险排名。',
      emptyText: '当前可评估维度中没有匹配项目；未评估维度不能据此判断为无风险。',
    };
  }
  return {
    title: '',
    description: '',
    emptyText: '当前范围没有符合所选风险条件的项目。',
  };
};
