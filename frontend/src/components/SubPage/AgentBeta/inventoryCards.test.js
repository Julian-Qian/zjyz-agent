import {
  normalizeInventoryOperationsCard,
  normalizeInventoryLedgerCard,
  normalizeInventorySummaryCard,
  inventoryMessageContent,
} from './inventoryCards';

describe('inventory summary presentation', () => {
  const card = {
    type: 'inventory-summary', materialCount: 387,
    lowStockTop: [
      { materialName: '扣件', materialSpecification: '十字', inventoryQuantity: -1374, inventoryUnit: '个' },
      { materialName: '扣件', materialSpecification: '旋转', inventoryQuantity: -1130, inventoryUnit: '个' },
    ],
  };
  const content = '当前库存材料共 387 种。库存较低材料示例：扣件(-1374)、扣件(-1130)。';

  it('preserves separate specifications and does not turn missing quantities into zero', () => {
    expect(normalizeInventorySummaryCard(card).items.map((item) => item.materialSpecification)).toEqual(['十字', '旋转']);
    const view = normalizeInventorySummaryCard({ lowStockTop: [null, { inventoryQuantity: 0 }, { inventoryQuantity: 'invalid' }] });
    expect(view.materialCount).toBeNull();
    expect(view.items.map((item) => item.inventoryQuantity)).toEqual([null, 0, null]);
  });

  it('removes only the exact duplicate summary while preserving trend commentary', () => {
    expect(inventoryMessageContent(content, [card])).toBe('');
    expect(inventoryMessageContent(`${content} 近6个月下降较快材料：钢管(-20)。`, [card])).toBe('近6个月下降较快材料：钢管(-20)。');
  });

  it('retains the answer if cards are absent, incomplete, different, or outside the displayed limit', () => {
    expect(inventoryMessageContent(content, undefined)).toBe(content);
    expect(inventoryMessageContent(content, [{ ...card, lowStockTop: [] }])).toBe(content);
    expect(inventoryMessageContent(content, [{ ...card, materialCount: 100 }])).toBe(content);
    expect(inventoryMessageContent(content, [...Array(8).fill({ type: 'other' }), card])).toBe(content);
    expect(inventoryMessageContent('补充：' + content, [card])).toBe('补充：' + content);
  });
});

describe('normalizeInventoryOperationsCard', () => {
  it('normalizes summary, items and truncation disclosure', () => {
    const view = normalizeInventoryOperationsCard({
      asOfDate: '2026-09-03',
      anomalySummary: { totalAnomalyMaterials: 9, negativeInventoryCount: 7, stagnantCount: 2 },
      anomalyCodeFilter: 'NEGATIVE_INVENTORY',
      totalCount: 7,
      displayedCount: 2,
      truncated: true,
      items: [
        { materialName: '钢管', materialSpecification: '48-3', anomalyCode: 'NEGATIVE_INVENTORY', currentValue: -12, severity: 'HIGH' },
      ],
      scopeNote: '企业全量库存口径。',
    });
    expect(view.summary.totalAnomalyMaterials).toBe(9);
    expect(view.summary.negativeInventoryCount).toBe(7);
    expect(view.filterLabel).toBe('库存为负');
    expect(view.truncated).toBe(true);
    expect(view.items[0].anomalyName).toBe('库存为负');
    expect(view.items[0].currentValue).toBe(-12);
  });

  it('tolerates null input', () => {
    const view = normalizeInventoryOperationsCard(null);
    expect(view.items).toEqual([]);
    expect(view.summary.totalAnomalyMaterials).toBe(0);
    expect(view.filterLabel).toBe('全部异常');
    expect(view.scopeNote).toContain('企业全量');
  });
});

describe('normalizeInventoryLedgerCard', () => {
  it('normalizes material label, balances and truncation', () => {
    const view = normalizeInventoryLedgerCard({
      materialName: '钢管',
      materialSpecification: '48-3',
      inventoryUnit: '根',
      currentInventoryQuantity: 220,
      totalCount: 35,
      displayedCount: 1,
      truncated: true,
      items: [{ eventTime: '2026-08-30 10:00', behaviorName: '租出', quantity: 100, inventoryDelta: -100, afterInventory: 220, inventoryUnit: '根' }],
    });
    expect(view.materialLabel).toBe('钢管 48-3');
    expect(view.currentInventoryQuantity).toBe(220);
    expect(view.truncated).toBe(true);
    expect(view.items[0].afterInventory).toBe(220);
  });

  it('tolerates malformed input', () => {
    const view = normalizeInventoryLedgerCard({ items: 'oops' });
    expect(view.items).toEqual([]);
    expect(view.materialLabel).toBe('');
    expect(view.scopeNote).toContain('企业级材料维度');
  });
});
