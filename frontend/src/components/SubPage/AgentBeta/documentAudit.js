// document-audit-list 卡的纯函数规整器：跨类型单据审核清单。
// v1 规则仅覆盖未复核与业务日期缺失；卡片必须披露该口径边界。

const toCount = (value) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
};

const toText = (value) => (typeof value === 'string' ? value : '');

export const DOCUMENT_AUDIT_ISSUE_LABELS = {
  UNREVIEWED: '未复核',
  MISSING_DATE: '业务日期缺失',
};

export const normalizeDocumentAuditCard = (card) => {
  const source = card || {};
  const countsByIssue = source.countsByIssue && typeof source.countsByIssue === 'object'
    ? source.countsByIssue : {};
  const countsByType = source.countsByType && typeof source.countsByType === 'object'
    ? source.countsByType : {};
  const items = Array.isArray(source.items)
    ? source.items.map((item) => ({
      documentTypeLabel: toText(item?.documentTypeLabel) || toText(item?.documentType),
      documentName: toText(item?.documentName) || toText(item?.documentId),
      projectName: toText(item?.projectName),
      counterpartyName: toText(item?.counterpartyName),
      businessDate: toText(item?.businessDate),
      issueLabels: Array.isArray(item?.issues)
        ? item.issues.map((issue) => DOCUMENT_AUDIT_ISSUE_LABELS[issue] || toText(issue)).filter(Boolean)
        : [],
    }))
    : [];
  return {
    asOfDate: toText(source.asOfDate),
    issueSummary: Object.keys(countsByIssue)
      .map((key) => `${DOCUMENT_AUDIT_ISSUE_LABELS[key] || key} ${toCount(countsByIssue[key])}`)
      .join(' · '),
    typeSummary: Object.keys(countsByType)
      .map((key) => `${key} ${toCount(countsByType[key])}`)
      .join(' · '),
    totalCount: toCount(source.totalCount),
    displayedCount: toCount(source.displayedCount),
    truncated: source.truncated === true,
    items,
    scopeNote: toText(source.scopeNote)
      || 'v1 审核规则仅覆盖未复核与业务日期缺失，不能据此判断单据完全合规。',
  };
};

export default normalizeDocumentAuditCard;
