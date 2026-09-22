import {
  normalizeFinanceEnterpriseCard,
  normalizeSupplierPayableCard,
} from './financeCards';

test('keeps posted, allocated, outstanding and registered cash as separate ledger measures', () => {
  const card = normalizeFinanceEnterpriseCard({
    currency: 'CNY',
    rentOut: {
      postedPrincipal: '12000.00',
      allocatedPrincipalAsOf: '5500.00',
      outstandingPrincipalAsOf: '6500.00',
      cumulativeCash: {
        totalRegistered: '7000.00',
        deposit: '700.00',
        lateFee: '300.00',
      },
    },
    rentIn: {
      postedPrincipal: '15000.00',
      allocatedPrincipalAsOf: '4500.00',
      outstandingPrincipalAsOf: '10500.00',
      cumulativeCash: { totalRegistered: '5200.00' },
    },
  });

  expect(card.rentOut).toMatchObject({
    postedPrincipal: 12000,
    allocatedPrincipalAsOf: 5500,
    outstandingPrincipalAsOf: 6500,
  });
  expect(card.rentOut.cumulativeCash).toMatchObject({
    totalRegistered: 7000,
    deposit: 700,
    lateFee: 300,
  });
  expect(card.rentIn).toMatchObject({
    postedPrincipal: 15000,
    allocatedPrincipalAsOf: 4500,
    outstandingPrincipalAsOf: 10500,
  });
});

test('does not derive outstanding principal from registered cash', () => {
  const card = normalizeSupplierPayableCard({
    summary: {
      postedPrincipal: '15000.00',
      allocatedPrincipalAsOf: '4500.00',
      outstandingPrincipalAsOf: '10500.00',
      cumulativeCash: { totalRegistered: '5200.00' },
    },
  });

  expect(card.summary.outstandingPrincipalAsOf).toBe(10500);
  expect(card.summary.postedPrincipal - card.summary.cumulativeCash.totalRegistered).toBe(9800);
});

test('keeps current balance, cumulative cash and historical period cash visibly separate', () => {
  const card = normalizeFinanceEnterpriseCard({
    asOfDate: '2026-08-31',
    startDate: '2026-07-01',
    endDate: '2026-07-31',
    rentOut: {
      outstandingPrincipalAsOf: '6500.00',
      cumulativeCash: { totalRegistered: '7000.00' },
      periodCash: { totalRegistered: '1234.56', rent: '1000.00', deposit: '234.56' },
    },
  });

  expect(card.balanceLabel).toBe('余额截至 2026-08-31');
  expect(card.cashPeriodLabel).toBe('登记收付期间 2026-07-01 至 2026-07-31');
  expect(card.hasCashPeriod).toBe(true);
  expect(card.rentOut.cumulativeCash.totalRegistered).toBe(7000);
  expect(card.rentOut.periodCash.totalRegistered).toBe(1234.56);
  expect(card.rentOut.outstandingPrincipalAsOf).toBe(6500);
});

test('preserves supplier truncation metadata and backend row order', () => {
  const card = normalizeSupplierPayableCard({
    items: [{ projectId: 'RI-A' }, { projectId: 'RI-B' }],
    totalCount: 7,
    displayedCount: 2,
    truncated: true,
    limit: 2,
    warnings: ['到期日口径：SETTLEMENT_PERIOD_END'],
  });

  expect(card.items.map((item) => item.projectId)).toEqual(['RI-A', 'RI-B']);
  expect(card).toMatchObject({ totalCount: 7, displayedCount: 2, truncated: true, limit: 2 });
  expect(card.warnings).toHaveLength(1);
});

test('keeps supplier current balance, cumulative payment and period payment separate', () => {
  const card = normalizeSupplierPayableCard({
    asOfDate: '2026-08-31',
    startDate: '2026-07-01',
    endDate: '2026-07-31',
    requestedMetric: 'SUPPLIER_REGISTERED_PAYMENTS',
    summary: {
      outstandingPrincipalAsOf: '10500.00',
      cumulativeCash: { totalRegistered: '5200.00' },
      periodCash: { totalRegistered: '2345.67', rent: '2100.00', other: '245.67' },
    },
    items: [
      { projectId: 'RI-A', counterpartyName: '同名供应商', registeredCash: '3000.00', periodRegisteredCash: '1200.00' },
      { projectId: 'RI-B', counterpartyName: '同名供应商', registeredCash: '2200.00', periodRegisteredCash: '1145.67' },
    ],
  });

  expect(card.balanceLabel).toBe('余额截至 2026-08-31');
  expect(card.cashPeriodLabel).toBe('登记付款期间 2026-07-01 至 2026-07-31');
  expect(card.hasCashPeriod).toBe(true);
  expect(card.summary.cumulativeCash.totalRegistered).toBe(5200);
  expect(card.summary.periodCash.totalRegistered).toBe(2345.67);
  expect(card.summary.outstandingPrincipalAsOf).toBe(10500);
  expect(card.items.map((item) => item.projectId)).toEqual(['RI-A', 'RI-B']);
  expect(card.items.map((item) => item.periodRegisteredCash)).toEqual(['1200.00', '1145.67']);
});

test('uses safe empty values for malformed optional card fields', () => {
  const card = normalizeFinanceEnterpriseCard({ items: null, warnings: [null, ''] });

  expect(card.items).toEqual([]);
  expect(card.warnings).toEqual([]);
  expect(card.rentOut.postedPrincipal).toBeNull();
  expect(card.currency).toBe('CNY');
});
