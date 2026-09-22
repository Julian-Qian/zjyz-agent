import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import BusinessRecordsCard from './BusinessRecordsCard';
import { businessValue, normalizeBusinessNavigation } from './agentBusinessResults';

test('business cards show quantities and units without dumping technical fields or hidden contacts', () => {
  const html = renderToStaticMarkup(<BusinessRecordsCard card={{ title: '商城材料', data: { list: [{ materialName: '钢管', materialSpecification: '6米', countingUnit: '根', marketPrice: 0, marketPriceUnit: '元/根/天', availableQty: 200, contactVisibleFlag: 0, contactPhone: 'PRIVATE_PHONE', dueDateBasis: 'SETTLEMENT_PERIOD_END' }] } }} />);
  expect(html).toContain('钢管'); expect(html).toContain('元/根/天'); expect(html).toContain('200');
  expect(html).not.toContain('PRIVATE_PHONE'); expect(html).not.toContain('SETTLEMENT_PERIOD_END');
  expect(businessValue('marketPrice', 0)).toBe('0');
});
test('business cards retain actual list containers returned by project and personnel services', () => {
  const html = renderToStaticMarkup(<BusinessRecordsCard card={{ data: { documentBriefInfoList: [{ documentName: '9月租出单' }], personnelInfos: [{ personName: '小何' }] } }} />);
  expect(html).toContain('9月租出单'); expect(html).toContain('小何');
});
test('navigation only accepts fixed business pages and safe filtering fields', () => {
  expect(normalizeBusinessNavigation({ target: 'https://evil.example' })).toBeNull();
  expect(normalizeBusinessNavigation({ target: '__proto__' })).toBeNull();
  const result = normalizeBusinessNavigation({ target: 'MARKET', url: 'javascript:alert(1)', filters: { keyword: '钢管', city: '杭州', cid: 'other', minAvailableQty: 100, pageSize: 99999, sortField: 'PRICE_ASC' } });
  expect(result).toEqual({ target: 'MARKET', menuKey: '5-3', filters: { keyword: '钢管', city: '杭州', minAvailableQty: 100 } });
});
test('price-sorted navigation preserves both comparison units', () => {
  const result = normalizeBusinessNavigation({ target: 'MARKET', filters: { countingUnit: '根', marketPriceUnit: '元/根/天', sortField: 'PRICE_ASC' } });
  expect(result.filters.sortField).toBe('PRICE_ASC');
});
test('project workflows reject injected paths and unsupported menu names', () => {
  expect(normalizeBusinessNavigation({ target: 'PROJECT_WORKFLOW', filters: { projectId: '../../admin', workflow: 'contract' } })).toBeNull();
  expect(normalizeBusinessNavigation({ target: 'PROJECT_WORKFLOW', filters: { projectId: 'p1', workflow: 'deleteEverything' } })).toBeNull();
  expect(normalizeBusinessNavigation({ target: 'PROJECT_WORKFLOW', filters: { projectId: 'p1', workflow: 'rentInReturn' } }).filters).toEqual({ projectId: 'p1', workflow: 'rentInReturn' });
});

test('deterministic metrics show all preview rows and unit groups without adding incompatible quantities', () => {
  const html = renderToStaticMarkup(<BusinessRecordsCard card={{ type: 'business-query-result', totalCount: 30, displayedCount: 2, unitGroups: { 根: 100, 套: 50 }, items: [{ materialName: '钢管', outstandingQuantity: 100, unit: '根' }, { materialName: '扣件', outstandingQuantity: 50, unit: '套' }] }} />);
  expect(html).toContain('匹配 30 项'); expect(html).toContain('本次展示 2 项');
  expect(html).toContain('100 根；50 套'); expect(html).toContain('未实物归还数量');
});
