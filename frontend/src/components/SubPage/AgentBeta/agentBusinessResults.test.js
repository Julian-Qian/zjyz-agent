import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import BusinessRecordsCard from './BusinessRecordsCard';
import {
  businessValue,
  formatDimensionStatusLabel,
  formatDueDateBasisLabel,
  isAgentDebugMode,
  normalizeBusinessNavigation,
} from './agentBusinessResults';

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
  expect(normalizeBusinessNavigation({ target: 'PROJECT_WORKFLOW', filters: { projectId: 'p1', workflow: 'lateFeeDocument' } }).filters).toEqual({ projectId: 'p1', workflow: 'lateFeeDocument' });
});

test('deterministic metrics show all preview rows and unit groups without adding incompatible quantities', () => {
  const html = renderToStaticMarkup(<BusinessRecordsCard card={{ type: 'business-query-result', totalCount: 30, displayedCount: 2, unitGroups: { 根: 100, 套: 50 }, items: [{ materialName: '钢管', outstandingQuantity: 100, unit: '根' }, { materialName: '扣件', outstandingQuantity: 50, unit: '套' }] }} />);
  expect(html).toContain('匹配 30 项'); expect(html).toContain('本次展示 2 项');
  expect(html).toContain('100 根；50 套'); expect(html).toContain('未实物归还数量');
});

test('phase 2 and phase 3 copilot/advisor cards render 30s conclusion, HITL draft status and navigation button', () => {
  const html = renderToStaticMarkup(
    <BusinessRecordsCard
      card={{
        title: '财务结算单草稿试算预览',
        capabilityCode: 'settlement.preview_draft',
        navigationTarget: 'PROJECT_WORKFLOW',
        criteria: { projectId: 'p1', workflow: 'financeCheck' },
        data: {
          conclusion30s: '【30秒结论】已试算项目示范工地A财务结算草稿：本期应结总额 12,500.00 元。',
          draftStatus: 'DRAFT_PREVIEW_ONLY',
          actionHint: '请点击下方按钮打开财务对账页面复核。',
          periodTotalFee: 12500,
          bcgQuadrant: 'DOG_DEAD_STOCK',
          decisionVerdict: 'ACCEPT_WITH_MARKET_SOURCING',
        },
      }}
    />,
  );
  expect(html).toContain('【30秒结论】');
  expect(html).toContain('试算草稿预览（未正式入账，核对无误后请前往对应页面保存）');
  expect(html).toContain('12500');
  expect(html).toContain('利用率低（长期闲置）');
  expect(html).toContain('建议接单（缺口可商城寻源补货）');
  expect(html).toContain('打开项目业务页面');
  expect(html).not.toContain('HITL');
  expect(html).not.toContain('落库');

  const guidedHtml = renderToStaticMarkup(
    <BusinessRecordsCard
      card={{
        title: '业务单据建单核查与操作引导',
        capabilityCode: 'document.preview_draft',
        navigationTarget: 'PROJECT_WORKFLOW',
        criteria: { projectId: 'p1', workflow: 'rentOut' },
        data: {
          conclusion30s: '【30秒结论】已为项目「示范工地A」生成「租出单」建单核查与操作引导。',
          draftStatus: 'GUIDED_PRECHECK_AND_NAVIGATION',
          targetPageName: '租出单',
          operationSteps: ['第 1 步【直达建单页面】：点击下方按钮进入租出单页面。'],
          inventoryCheckList: [{ requestedMaterial: '十字扣件', requestedQty: 1000, inventoryQty: 42988, stockCheckStatus: '库存充足' }],
        },
      }}
    />,
  );
  expect(guidedHtml).toContain('建单前置核查与操作引导');
  expect(guidedHtml).toContain('建单操作步骤引导');
  expect(guidedHtml).toContain('基地库存充足度核查');
  expect(guidedHtml).toContain('打开项目业务页面（直达租出单）');
});

test('user vs debug mode gate and business label translators hide developer jargon by default', () => {
  expect(isAgentDebugMode({ search: '', hash: '' }, { getItem: () => null })).toBe(false);
  expect(isAgentDebugMode({ search: '?agentDebug=true', hash: '' }, { getItem: () => null })).toBe(true);
  expect(isAgentDebugMode({ search: '', hash: '#/agent?agentDebug=1' }, { getItem: () => null })).toBe(true);
  expect(isAgentDebugMode({ search: '', hash: '' }, { getItem: (k) => (k === 'ZJYZ_AGENT_DEBUG' ? 'true' : null) })).toBe(true);
  expect(formatDueDateBasisLabel('SETTLEMENT_PERIOD_END')).toBe('结算账期结束日');
  expect(formatDimensionStatusLabel('SUPPORTED')).toBe('已核算');
  expect(formatDimensionStatusLabel('FAILED')).toBe('核算异常');
});

