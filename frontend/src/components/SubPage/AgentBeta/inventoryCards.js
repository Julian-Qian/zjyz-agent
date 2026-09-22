// 库存运营卡（inventory-operations-summary）与库存台账卡（inventory-ledger-trace）的纯函数规整器。
// 与后端契约一致：全集计数 + 展示数 + 截断披露；数量为各材料自身计数单位，前端不做任何合计。

const toCount = (value) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
};

const toText = (value) => (typeof value === 'string' ? value : '');

const toOptionalNumber = (value) => {
  if (value == null || value === '' || typeof value === 'boolean') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
};

export const normalizeInventorySummaryCard = (card) => ({
  materialCount: toOptionalNumber(card?.materialCount),
  items: Array.isArray(card?.lowStockTop) ? card.lowStockTop.map((item) => ({
    materialId: toText(item?.materialId),
    materialName: toText(item?.materialName) || '未命名材料',
    materialSpecification: toText(item?.materialSpecification),
    inventoryUnit: toText(item?.inventoryUnit),
    inventoryQuantity: toOptionalNumber(item?.inventoryQuantity),
  })) : [],
});

// 仅移除与结构化卡片完全一致的旧版摘要，保留趋势、补充说明及其他回答。
export const inventoryMessageContent = (content, cards) => {
  if (typeof content !== 'string' || !Array.isArray(cards)) return content;
  const card = cards.slice(0, 8).find((item) => item?.type === 'inventory-summary');
  if (!card) return content;
  const view = normalizeInventorySummaryCard(card);
  if (view.materialCount == null || !view.items.length
    || view.items.some((item) => item.inventoryQuantity == null)) return content;
  const details = view.items.map((item) => `${item.materialName}(${item.inventoryQuantity})`).join('、');
  const prefix = `当前库存材料共 ${view.materialCount} 种。库存较低材料示例：${details}。`;
  return content.startsWith(prefix) ? content.slice(prefix.length).trim() : content;
};

export const INVENTORY_ANOMALY_LABELS = {
  NEGATIVE_INVENTORY: '库存为负',
  NEGATIVE_RENTED: '在租为负',
  NEGATIVE_LEASED: '租入未退为负',
  LONG_TIME_NO_FLOW: '长期无流水',
};

export const normalizeInventoryOperationsCard = (card) => {
  const source = card || {};
  const summarySource = source.anomalySummary || {};
  const items = Array.isArray(source.items)
    ? source.items.map((item) => ({
      materialName: toText(item?.materialName),
      materialSpecification: toText(item?.materialSpecification),
      anomalyName: toText(item?.anomalyName) || INVENTORY_ANOMALY_LABELS[item?.anomalyCode] || toText(item?.anomalyCode),
      severity: toText(item?.severity),
      currentValue: item?.currentValue == null ? null : Number(item.currentValue),
      suggestion: toText(item?.suggestion),
    }))
    : [];
  return {
    asOfDate: toText(source.asOfDate),
    summary: {
      totalAnomalyMaterials: toCount(summarySource.totalAnomalyMaterials),
      negativeInventoryCount: toCount(summarySource.negativeInventoryCount),
      negativeRentedCount: toCount(summarySource.negativeRentedCount),
      negativeLeasedCount: toCount(summarySource.negativeLeasedCount),
      stagnantCount: toCount(summarySource.stagnantCount),
    },
    filterLabel: source.anomalyCodeFilter
      ? (INVENTORY_ANOMALY_LABELS[source.anomalyCodeFilter] || toText(source.anomalyCodeFilter))
      : '全部异常',
    totalCount: toCount(source.totalCount),
    displayedCount: toCount(source.displayedCount),
    truncated: source.truncated === true,
    items,
    scopeNote: toText(source.scopeNote) || '企业全量库存口径，忽略项目多选。',
  };
};

export const normalizeInventoryLedgerCard = (card) => {
  const source = card || {};
  const spec = toText(source.materialSpecification);
  const items = Array.isArray(source.items)
    ? source.items.map((item) => ({
      eventTime: toText(item?.eventTime),
      behaviorName: toText(item?.behaviorName),
      quantity: item?.quantity == null ? null : Number(item.quantity),
      inventoryDelta: item?.inventoryDelta == null ? null : Number(item.inventoryDelta),
      afterInventory: item?.afterInventory == null ? null : Number(item.afterInventory),
      inventoryUnit: toText(item?.inventoryUnit),
      projectName: toText(item?.projectName),
      documentName: toText(item?.documentName),
    }))
    : [];
  return {
    materialLabel: `${toText(source.materialName)}${spec ? ` ${spec}` : ''}`.trim(),
    inventoryUnit: toText(source.inventoryUnit),
    currentInventoryQuantity: source.currentInventoryQuantity == null
      ? null : Number(source.currentInventoryQuantity),
    period: source.startDate || source.endDate
      ? `${toText(source.startDate)} ~ ${toText(source.endDate)}` : '',
    totalCount: toCount(source.totalCount),
    displayedCount: toCount(source.displayedCount),
    truncated: source.truncated === true,
    items,
    scopeNote: toText(source.scopeNote) || '库存台账为企业级材料维度，忽略项目多选。',
  };
};
