import { normalizeMaterialRankingGroups } from './materialRanking';

test('uses explicit unit groups without flattening heterogeneous quantities into one ranking', () => {
  const groups = normalizeMaterialRankingGroups({
    unitGroups: [
      { unit: '根', totalCount: 2, displayedCount: 1, truncated: true, items: [{ rank: 1, totalQuantity: 100 }] },
      { unit: '吨', totalCount: 1, displayedCount: 1, truncated: false, items: [{ rank: 1, totalQuantity: 2 }] },
      { unit: '件', totalCount: 1, displayedCount: 1, truncated: false, items: [{ rank: 1, totalQuantity: 300 }] },
    ],
    items: [{ rank: 99, totalQuantity: 999 }],
  });

  expect(groups.map((group) => group.unit)).toEqual(['根', '吨', '件']);
  expect(groups.every((group) => group.items[0].rank === 1)).toBe(true);
  expect(groups[0].truncated).toBe(true);
});

test('keeps old cards compatible by deriving groups from legacy items', () => {
  const groups = normalizeMaterialRankingGroups({
    items: [
      { materialName: '钢管', materialUnit: '根', totalQuantity: 10 },
      { materialName: '扣件', materialUnit: '件', totalQuantity: 20 },
      { materialName: '未知', materialUnit: null, totalQuantity: 1 },
    ],
  });

  expect(groups.map((group) => group.unit)).toEqual(['根', '件', '未标注单位']);
  expect(groups.find((group) => group.unit === '未标注单位').unitMissing).toBe(true);
});
