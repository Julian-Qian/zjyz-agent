import { normalizeCapabilityBoundaryCard } from './capabilityBoundary';

describe('normalizeCapabilityBoundaryCard', () => {
  it('normalizes an unsupported boundary card', () => {
    const view = normalizeCapabilityBoundaryCard({
      type: 'capability-boundary',
      status: 'UNSUPPORTED',
      missingTools: [],
      nextStep: '请改问系统当前支持的指标。',
    });
    expect(view.status).toBe('UNSUPPORTED');
    expect(view.statusLabel).toBe('当前能力不支持');
    expect(view.missingTools).toEqual([]);
    expect(view.nextStep).toBe('请改问系统当前支持的指标。');
    expect(view.scopeNote).toContain('不包含业务数据');
  });

  it('normalizes missing-evidence card with tool names and falls back to toolCode', () => {
    const view = normalizeCapabilityBoundaryCard({
      status: 'MISSING_REQUIRED_EVIDENCE',
      missingTools: [
        { toolCode: 'finance.receivable_collection_list', name: '应收催缴清单' },
        { toolCode: 'project.reconciliation_due' },
        { name: '' },
      ],
    });
    expect(view.statusLabel).toBe('关键证据缺失');
    expect(view.missingTools).toEqual([
      { toolCode: 'finance.receivable_collection_list', name: '应收催缴清单' },
      { toolCode: 'project.reconciliation_due', name: 'project.reconciliation_due' },
    ]);
  });

  it('tolerates null and malformed input', () => {
    const view = normalizeCapabilityBoundaryCard(null);
    expect(view.status).toBe('MISSING_REQUIRED_EVIDENCE');
    expect(view.missingTools).toEqual([]);
    expect(view.nextStep).toBe('');
    expect(normalizeCapabilityBoundaryCard({ missingTools: 'oops' }).missingTools).toEqual([]);
  });
});
