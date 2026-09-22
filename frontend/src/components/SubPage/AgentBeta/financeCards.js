const safeArray = (value) => (Array.isArray(value) ? value : []);

const moneyValue = (value) => {
  if (value === null || value === undefined || value === '') return null;
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : null;
};

const countValue = (value, fallback = 0) => {
  const count = Number(value);
  return Number.isFinite(count) && count >= 0 ? count : fallback;
};

const normalizeCash = (cash) => ({
  totalRegistered: moneyValue(cash?.totalRegistered),
  principalEligible: moneyValue(cash?.principalEligible),
  rent: moneyValue(cash?.rent),
  compensation: moneyValue(cash?.compensation),
  deposit: moneyValue(cash?.deposit),
  lateFee: moneyValue(cash?.lateFee),
  other: moneyValue(cash?.other),
  allocatedPrincipalAsOf: moneyValue(cash?.allocatedPrincipalAsOf),
  unallocatedPrincipalAsOf: moneyValue(cash?.unallocatedPrincipalAsOf),
});

const normalizeDirection = (direction) => ({
  businessDirection: direction?.businessDirection || '',
  postedPrincipal: moneyValue(direction?.postedPrincipal),
  allocatedPrincipalAsOf: moneyValue(direction?.allocatedPrincipalAsOf),
  outstandingPrincipalAsOf: moneyValue(direction?.outstandingPrincipalAsOf),
  dueAsOfOutstanding: moneyValue(direction?.dueAsOfOutstanding),
  overdueOutstanding: moneyValue(direction?.overdueOutstanding),
  dueTodayOutstanding: moneyValue(direction?.dueTodayOutstanding),
  cumulativeCash: normalizeCash(direction?.cumulativeCash),
  periodCash: normalizeCash(direction?.periodCash),
  projectCount: countValue(direction?.projectCount),
  periodCount: countValue(direction?.periodCount),
});

const normalizeMetadata = (card, items) => {
  const totalCount = countValue(card?.totalCount, items.length);
  const displayedCount = countValue(card?.displayedCount, items.length);
  return {
    currency: card?.currency || 'CNY',
    asOfDate: card?.asOfDate || '',
    dueDateBasis: card?.dueDateBasis || '',
    supplierNameSource: card?.supplierNameSource || '',
    items,
    totalCount,
    displayedCount,
    truncated: card?.truncated === true,
    limit: countValue(card?.limit),
    warnings: safeArray(card?.warnings).filter((warning) => typeof warning === 'string' && warning.trim()),
  };
};

export const normalizeFinanceEnterpriseCard = (card) => {
  const metadata = normalizeMetadata(card, safeArray(card?.items));
  const startDate = card?.startDate || '';
  const endDate = card?.endDate || '';
  const hasCashPeriod = Boolean(startDate && endDate);
  return {
    ...metadata,
    startDate,
    endDate,
    balanceLabel: `余额截至 ${metadata.asOfDate || '--'}`,
    hasCashPeriod,
    cashPeriodLabel: hasCashPeriod ? `登记收付期间 ${startDate} 至 ${endDate}` : '',
    rentOut: normalizeDirection(card?.rentOut),
    rentIn: normalizeDirection(card?.rentIn),
  };
};

export const normalizeSupplierPayableCard = (card) => {
  const metadata = normalizeMetadata(card, safeArray(card?.items));
  const startDate = card?.startDate || '';
  const endDate = card?.endDate || '';
  const hasCashPeriod = Boolean(startDate && endDate);
  return {
    ...metadata,
    startDate,
    endDate,
    requestedMetric: card?.requestedMetric || '',
    balanceLabel: `余额截至 ${metadata.asOfDate || '--'}`,
    hasCashPeriod,
    cashPeriodLabel: hasCashPeriod ? `登记付款期间 ${startDate} 至 ${endDate}` : '',
    summary: normalizeDirection(card?.summary),
  };
};
