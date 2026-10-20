import rules from './answerPresentationRules.json';

// Shared presentation policy for persisted answers, streaming text and result notices.
// Match known diagnostics, not general words such as 核验: source/data caveats must survive.
export const presentAssistantAnswer = (content, streaming = false) => {
  if (typeof content !== 'string') return content;
  let text = content;
  Object.entries(rules).forEach(([internal, business]) => {
    text = text.split(internal).join(business);
  });
  if (streaming) {
    // Hold an unfinished diagnostic prefix until it can be translated as a whole.
    const start = text.lastIndexOf('\n') + 1;
    const tail = text.slice(start);
    if (tail && Object.keys(rules).some((phrase) => phrase.startsWith(tail))) text = text.slice(0, start);
  }
  return text.replace(/\n{3,}/g, '\n\n').trim();
};

const SPECIALIZED_CARDS = new Set([
  'business-records', 'business-query-result', 'project-operating-report',
  'receivable-collection-list', 'material-transaction-ranking', 'finance-enterprise-kpi',
  'supplier-payable-summary', 'owner-action-center', 'capability-boundary',
  'inventory-operations-summary', 'inventory-summary', 'inventory-ledger-trace',
  'document-audit-list', 'contract-commercial-analytics', 'material-lifecycle-analytics',
]);

export const hasVisibleResult = (card) => Boolean(card && (
  SPECIALIZED_CARDS.has(card.type)
  || (card.type === 'help-knowledge' && Array.isArray(card.articles) && card.articles.length > 0)
  || (Array.isArray(card.items) && card.items.length)
  || [card.aiSummary, card.scopeSummary, card.summary, card.suggestion]
    .some((value) => typeof value === 'string' && value.trim())
));

export const dedupeHelpArticles = (articles, seenSet = new Set()) => (
  (Array.isArray(articles) ? articles : []).filter((article) => {
    if (!article || typeof article !== 'object') return false;
    const titleKey = String(article.title || '').trim();
    const idKey = String(article.articleId ?? article.sourceId ?? '').trim();
    const routeKey = String(article.routeScope || 'all').trim();
    const key = titleKey ? `title:${titleKey}|route:${routeKey}` : `id:${idKey}|route:${routeKey}`;
    if (!titleKey && !idKey) return true;
    if (seenSet.has(key)) return false;
    seenSet.add(key);
    return true;
  })
);

// Compare full payloads: equal rows in different scopes/periods must not be collapsed.
const stableCard = (value) => {
  if (Array.isArray(value)) return value.map(stableCard);
  if (value && typeof value === 'object') return Object.keys(value).sort().reduce((out, key) => {
    out[key] = stableCard(value[key]);
    return out;
  }, {});
  return value;
};
export const uniqueResultCards = (cards) => {
  const seen = new Set();
  const seenHelpArticles = new Set();
  const output = [];
  (Array.isArray(cards) ? cards : []).forEach((rawCard) => {
    if (rawCard && rawCard.type === 'help-knowledge' && Array.isArray(rawCard.articles)) {
      const uniqueArticles = dedupeHelpArticles(rawCard.articles, seenHelpArticles);
      if (!uniqueArticles.length) return;
      const normalized = { ...rawCard, articles: uniqueArticles };
      const key = JSON.stringify(stableCard(normalized));
      if (!seen.has(key)) {
        seen.add(key);
        output.push(normalized);
      }
      return;
    }
    const key = JSON.stringify(stableCard(rawCard));
    if (seen.has(key)) return;
    seen.add(key);
    output.push(rawCard);
  });
  return output;
};
