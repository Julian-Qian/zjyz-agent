import {
  normalizeContractCommercialCard,
  normalizeMaterialLifecycleCard,
} from './analyticsCards';

describe('normalizeContractCommercialCard', () => {
  it('normalizes sections with issue labels and price groups', () => {
    const view = normalizeContractCommercialCard({
      projectCount: 5,
      completeness: {
        totalCount: 2,
        displayedCount: 2,
        truncated: false,
        items: [{ projectName: '项目二', issues: ['NO_CONTRACT'] }],
      },
      priceComparison: {
        totalCount: 1,
        displayedCount: 1,
        items: [{
          materialName: '钢管', materialSpecification: '48-3', countingUnit: '根',
          projectCount: 2, minDailyRent: 1.2, minProjectName: '项目一',
          maxDailyRent: 1.5, maxProjectName: '项目二',
        }],
      },
    });
    expect(view.completeness.items[0].issueLabels).toEqual(['未录入合同']);
    expect(view.priceComparison.items[0].materialLabel).toBe('钢管 48-3');
    expect(view.priceComparison.items[0].maxDailyRent).toBe(1.5);
    expect(view.scopeNote).toContain('执行价');
  });

  it('tolerates null input', () => {
    const view = normalizeContractCommercialCard(null);
    expect(view.completeness.items).toEqual([]);
    expect(view.priceComparison.totalCount).toBe(0);
  });
});

describe('normalizeMaterialLifecycleCard', () => {
  it('normalizes three sections preserving negative over-return values', () => {
    const view = normalizeMaterialLifecycleCard({
      minStagnantDays: 60,
      overReturn: {
        totalCount: 1,
        displayedCount: 1,
        items: [{ projectName: '项目一', materialName: '扣件', materialUnit: '只', outstandingQuantity: -20 }],
      },
      staleOccupancy: {
        totalCount: 1,
        displayedCount: 1,
        items: [{ projectName: '项目一', outstandingMaterialGroups: 3, staleDays: 90 }],
      },
      compensationRate: {
        totalCount: 1,
        displayedCount: 1,
        items: [{ materialName: '扣件', materialUnit: '只', rentedQuantity: 200, compensatedQuantity: 20, compensationRate: 0.1 }],
      },
    });
    expect(view.overReturn.items[0].outstandingQuantity).toBe(-20);
    expect(view.staleOccupancy.items[0].staleDays).toBe(90);
    expect(view.compensationRate.items[0].compensationRate).toBe(0.1);
    expect(view.scopeNote).toContain('租出方向');
  });

  it('tolerates malformed input', () => {
    const view = normalizeMaterialLifecycleCard({ overReturn: 'oops' });
    expect(view.overReturn.items).toEqual([]);
    expect(view.compensationRate.totalCount).toBe(0);
  });
});
