import {
  normalizeOwnerActionCenterCard,
  ownerActionBoundaryView,
  ownerRiskMetricSummary,
} from './riskCards';

test('keeps project and action counts distinct and verifies score contributions', () => {
  const card = normalizeOwnerActionCenterCard({
    projectTotalCount: 8,
    displayedProjectCount: 2,
    actionTotalCount: 17,
    actionDisplayedCount: 5,
    totalCount: 8,
    displayedCount: 2,
    truncated: true,
    actionTruncated: true,
    limit: 5,
    items: [{
      projectId: 'P1',
      totalScore: 65,
      scoreContributions: [{ code: 'PRIMARY', points: 55 }, { code: 'DIVERSITY', points: 10 }],
      risks: [{ riskType: 'UNRECONCILED', score: 55, scoreContributions: [{ code: 'A', points: 20 }, { code: 'B', points: 35 }] }],
    }],
  });

  expect(card).toMatchObject({ projectTotalCount: 8, displayedProjectCount: 2, actionTotalCount: 17, actionDisplayedCount: 5, truncated: true, actionTruncated: true });
  expect(card.items[0].scoreReproducible).toBe(true);
  expect(card.items[0].risks[0].scoreReproducible).toBe(true);
});

test('keeps project truncation independent from action truncation', () => {
  const card = normalizeOwnerActionCenterCard({
    projectTotalCount: 2,
    displayedProjectCount: 2,
    actionTotalCount: 9,
    actionDisplayedCount: 5,
    totalCount: 2,
    displayedCount: 2,
    truncated: false,
    actionTruncated: true,
    limit: 5,
  });

  expect(card.totalCount).toBe(card.displayedCount);
  expect(card.truncated).toBe(false);
  expect(card.actionTruncated).toBe(true);
});

test('keeps failed dimensions visible and marks available-dimension ranking incomplete', () => {
  const card = normalizeOwnerActionCenterCard({
    scoreIncomplete: true,
    rankingBasis: 'AVAILABLE_DIMENSIONS',
    failedDimensions: [{ riskType: 'OVERDUE_RECEIVABLE', errorCode: 'FIN409', reason: 'orphan ledger' }],
  });

  expect(card.scoreIncomplete).toBe(true);
  expect(card.rankingBasis).toBe('AVAILABLE_DIMENSIONS');
  expect(card.failedDimensions).toHaveLength(1);
  expect(ownerActionBoundaryView(card).title).toBe('部分风险维度核算失败');
  expect(ownerActionBoundaryView(card).emptyText).toContain('不能据此判断为无风险');
});

test('unsupported dimensions are disclosed as unevaluable instead of failed or no-risk', () => {
  const card = normalizeOwnerActionCenterCard({
    scoreIncomplete: true,
    rankingBasis: 'AVAILABLE_DIMENSIONS',
    unsupportedDimensions: [{ riskType: 'INVENTORY_ANOMALY', reason: 'cannot attribute to project' }],
  });

  const boundary = ownerActionBoundaryView(card);
  expect(boundary.title).toBe('部分风险维度暂不可评估');
  expect(boundary.description).toContain('AVAILABLE_DIMENSIONS');
  expect(boundary.emptyText).toContain('未评估维度不能据此判断为无风险');
});

test('material metric summary renders root ton and piece as separate unit groups', () => {
  const summary = ownerRiskMetricSummary({
    riskType: 'MATERIAL_OUTSTANDING',
    rawMetrics: {
      outstandingMaterialKinds: 3,
      crossUnitTotal: null,
      unitGroups: [
        { unit: '根', outstandingQuantity: '10.00' },
        { unit: '吨', outstandingQuantity: '2.00' },
        { unit: '件', outstandingQuantity: '7.00' },
      ],
    },
  });

  expect(summary).toContain('根 10.00');
  expect(summary).toContain('吨 2.00');
  expect(summary).toContain('件 7.00');
  expect(summary).not.toContain('合计');
});

test('uses safe defaults for malformed optional fields', () => {
  const card = normalizeOwnerActionCenterCard({ items: null, warnings: [null, ''] });

  expect(card.items).toEqual([]);
  expect(card.warnings).toEqual([]);
  expect(card.projectTotalCount).toBe(0);
  expect(card.failedDimensions).toEqual([]);
});
