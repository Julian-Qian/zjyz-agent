import { presentAssistantAnswer, hasVisibleResult, uniqueResultCards } from './answerPresentation';

it('removes internal verification notices anywhere while preserving facts and business gaps', () => {
  const text = '要求核验未通过，已保留查询结果。\n\n库存 -10 件。\n部分要求尚未通过独立证据核验，已有查询结果予以保留。\n未核验合同约定的付款期限；第 3 页无法读取。';
  expect(presentAssistantAnswer(text)).toBe('库存 -10 件。\n\n未核验合同约定的付款期限；第 3 页无法读取。');
});

it('expresses incomplete guidance, analysis and document review in business terms', () => {
  expect(presentAssistantAnswer('使用指导检索未通过校验。')).toBe('暂时无法提供可靠的操作步骤，请稍后重试。');
  expect(presentAssistantAnswer('分析审查尚未通过，以下仅展示已有查询事实。')).toBe('暂时无法给出综合分析，以下为已查到的数据。');
  expect(presentAssistantAnswer('跨批条款综合核验未完成。')).toBe('尚未完成不同段落之间的条款对照。');
});

it('holds incomplete internal streaming prefixes but keeps ordinary content', () => {
  expect(presentAssistantAnswer('要求核验未通', true)).toBe('');
  expect(presentAssistantAnswer('库存 -10 件。\n要求核验', true)).toBe('库存 -10 件。');
  expect(presentAssistantAnswer('库存 -10', true)).toBe('库存 -10');
});

it('preserves meaningful empty results and hides empty placeholder cards', () => {
  expect(hasVisibleResult({ type: 'unknown' })).toBe(false);
  expect(hasVisibleResult({ items: [], summary: '没有符合条件的材料' })).toBe(true);
  expect(hasVisibleResult({ type: 'inventory-summary', lowStockTop: [] })).toBe(true);
});

it('deduplicates only identical card payloads, preserving different scopes and dates', () => {
  const card = { type: 'project-list', items: [{ projectId: 'p1' }], scopeSummary: '本月' };
  expect(uniqueResultCards([card, { scopeSummary: '本月', items: [{ projectId: 'p1' }], type: 'project-list' }, { ...card, scopeSummary: '上月' }])).toEqual([card, { ...card, scopeSummary: '上月' }]);
});
