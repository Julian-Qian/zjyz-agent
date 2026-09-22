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
  || (Array.isArray(card.items) && card.items.length)
  || [card.aiSummary, card.scopeSummary, card.summary, card.suggestion]
    .some((value) => typeof value === 'string' && value.trim())
));

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
  return (Array.isArray(cards) ? cards : []).filter((card) => {
    const key = JSON.stringify(stableCard(card));
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
};
