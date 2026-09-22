const safeItems = (value) => (Array.isArray(value) ? value : []);

export const normalizeMaterialRankingGroups = (card) => {
  const explicitGroups = safeItems(card?.unitGroups)
    .filter((group) => group && typeof group === 'object')
    .map((group) => ({
      ...group,
      unit: group.unit || '未标注单位',
      unitMissing: group.unitMissing === true || !group.unit,
      items: safeItems(group.items),
      totalCount: Number.isFinite(Number(group.totalCount)) ? Number(group.totalCount) : safeItems(group.items).length,
      displayedCount: Number.isFinite(Number(group.displayedCount))
        ? Number(group.displayedCount) : safeItems(group.items).length,
      truncated: group.truncated === true,
    }));
  if (explicitGroups.length) return explicitGroups;

  const legacyGroups = new Map();
  safeItems(card?.items).forEach((item) => {
    const unit = item?.materialUnit || '未标注单位';
    if (!legacyGroups.has(unit)) legacyGroups.set(unit, []);
    legacyGroups.get(unit).push(item);
  });
  return Array.from(legacyGroups.entries()).map(([unit, items]) => ({
    unit,
    unitMissing: unit === '未标注单位',
    items,
    totalCount: items.length,
    displayedCount: items.length,
    truncated: false,
  }));
};
