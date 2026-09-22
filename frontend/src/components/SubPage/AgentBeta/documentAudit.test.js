import { normalizeDocumentAuditCard } from './documentAudit';

describe('normalizeDocumentAuditCard', () => {
  it('normalizes counts, items and issue labels', () => {
    const view = normalizeDocumentAuditCard({
      asOfDate: '2026-09-03',
      countsByIssue: { UNREVIEWED: 3, MISSING_DATE: 1 },
      countsByType: { RENT_OUT: 2, RETURN: 2 },
      totalCount: 4,
      displayedCount: 2,
      truncated: true,
      items: [
        {
          documentType: 'RENT_OUT',
          documentTypeLabel: '租出单',
          documentName: '租出单-001',
          projectName: '示例项目',
          businessDate: '2026-08-30',
          issues: ['UNREVIEWED'],
        },
        { documentType: 'RETURN', documentName: '归还单-002', issues: ['MISSING_DATE', 'UNKNOWN_RULE'] },
      ],
      scopeNote: 'v1 审核规则仅覆盖未复核与业务日期缺失。',
    });
    expect(view.issueSummary).toContain('未复核 3');
    expect(view.issueSummary).toContain('业务日期缺失 1');
    expect(view.truncated).toBe(true);
    expect(view.items[0].documentTypeLabel).toBe('租出单');
    expect(view.items[0].issueLabels).toEqual(['未复核']);
    expect(view.items[1].issueLabels).toEqual(['业务日期缺失', 'UNKNOWN_RULE']);
  });

  it('tolerates null and malformed input', () => {
    const view = normalizeDocumentAuditCard(null);
    expect(view.items).toEqual([]);
    expect(view.totalCount).toBe(0);
    expect(view.scopeNote).toContain('v1 审核规则');
    expect(normalizeDocumentAuditCard({ items: 'oops', countsByIssue: 3 }).items).toEqual([]);
  });
});
