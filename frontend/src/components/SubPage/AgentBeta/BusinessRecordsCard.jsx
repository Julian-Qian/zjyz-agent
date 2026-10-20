import React, { useState } from 'react';
import { Button, Empty } from 'antd';
import { BUSINESS_LABELS, BUSINESS_PAGES, businessValue, requestBusinessNavigation } from './agentBusinessResults';

const CURRENCY_TABLE_FIELDS = new Set([
  'discountedDailyRent', 'scenarioTotalRent', 'concessionAmount',
  'previousAmount', 'currentAmount', 'deltaAmount',
  'previousRent', 'currentRent', 'deltaRent',
  'estimatedDailySavings', 'estimatedMonthlySavings',
]);

const DAYS_TABLE_FIELDS = new Set([
  'freeRentDays', 'effectiveBillingDays', 'extraDaysToRecoverConcession', 'breakevenTotalLeaseDays',
]);

const isInternalHashProjectId = (obj, key, value) => (
  key === 'projectId' && (Boolean(obj?.projectName) || /^\d{14}[A-Za-z0-9]{4,}$/.test(String(value || '')))
);

const isVisibleField = (obj, key, value, depth = 0, parentField = '') => (
  Boolean(BUSINESS_LABELS[key])
  && value !== null
  && value !== undefined
  && !isInternalHashProjectId(obj, key, value)
  && !(depth === 0 && ['conclusion30s', 'draftStatus', 'actionHint', 'hitlConfirmationRequired', 'pyramidAnswer', 'pyramidSummary'].includes(key))
  && !['pyramidAnswer', 'pyramidSummary'].includes(key)
  && !(parentField === 'kpi' && ['expectedQuantity', 'rentedQuantity', 'leasedQuantity', 'inventoryQuantity', 'totalQuantity'].includes(key))
  && (!['contactPerson', 'contactPhone'].includes(key) || obj.contactVisibleFlag === undefined || Number(obj.contactVisibleFlag) === 1)
);

const formatTableCellValue = (key, value) => {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number' && Number.isFinite(value)) {
    if (CURRENCY_TABLE_FIELDS.has(key)) {
      return `${value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} 元`;
    }
    if (key === 'discountPercent') {
      return `${value}%`;
    }
    if (DAYS_TABLE_FIELDS.has(key)) {
      return key === 'extraDaysToRecoverConcession' && value > 0 ? `+${value} 天` : `${value} 天`;
    }
  }
  return businessValue(key, value);
};

const extractTableColumns = (rows, depth, parentField) => {
  if (!Array.isArray(rows) || !rows.length) return null;
  if (!rows.every((item) => item && typeof item === 'object' && !Array.isArray(item))) return null;
  const colSet = new Set();
  for (let i = 0; i < rows.length; i += 1) {
    const row = rows[i];
    const entries = Object.entries(row);
    for (let j = 0; j < entries.length; j += 1) {
      const [k, v] = entries[j];
      if (!isVisibleField(row, k, v, depth, parentField)) continue;
      if (typeof v === 'object' && v !== null) return null;
      colSet.add(k);
    }
  }
  const cols = Array.from(colSet);
  return cols.length >= 2 ? cols : null;
};

const BusinessData = ({ data, depth = 0, field = '' }) => {
  const [expanded, setExpanded] = useState(false);
  if (data === null || data === undefined) return <span>未填写</span>;
  if (depth > 6) return <span>明细较多，请到业务页面查看</span>;
  if (Array.isArray(data)) {
    if (!data.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的记录" />;
    const visibleRows = data.slice(0, expanded ? data.length : 10);
    const tableColumns = extractTableColumns(visibleRows, depth + 1, field);
    if (tableColumns) {
      return (
        <div className="agent-markdown-table-wrap" style={{ overflowX: 'auto', margin: '6px 0' }}>
          <table className="agent-markdown-table" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                {tableColumns.map((col) => (
                  <th key={col} style={{ textAlign: 'left', padding: '8px 10px', background: '#f4f7fc', color: '#4e5969', fontWeight: 600, borderBottom: '1px solid #e5e9f2', whiteSpace: 'nowrap' }}>
                    {BUSINESS_LABELS[col]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {visibleRows.map((item, rowIdx) => (
                <tr key={rowIdx} style={{ borderBottom: '1px solid #eef2f8' }}>
                  {tableColumns.map((col) => (
                    <td key={col} style={{ padding: '7px 10px', color: '#1d2129', verticalAlign: 'top' }}>
                      {formatTableCellValue(col, item[col])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {data.length > 10 ? <Button type="link" onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : `展开本页全部 ${data.length} 项`}</Button> : null}
        </div>
      );
    }
    return <div>{visibleRows.map((item, index) => (
      <div key={index} style={{ borderTop: '1px solid #e6edf7', padding: '10px 0' }}>
        {typeof item === 'object' ? <small>第 {index + 1} 项</small> : null}
        <BusinessData data={item} depth={depth + 1} field={field} />
      </div>
    ))}{data.length > 10 ? <Button type="link" onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : `展开本页全部 ${data.length} 项`}</Button> : null}</div>;
  }
  if (typeof data !== 'object') return <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{businessValue(field, data)}</span>;
  const fields = Object.entries(data).filter(([key, value]) => isVisibleField(data, key, value, depth, field));
  return fields.length ? <dl style={{ margin: 0 }}>{fields.map(([key, value]) => (
    <div key={key} style={{ margin: '5px 0', display: typeof value === 'object' ? 'block' : 'flex', gap: 12 }}>
      <dt style={{ color: '#667894', flex: '0 0 130px' }}>{BUSINESS_LABELS[key]}</dt>
      <dd style={{ margin: 0, minWidth: 0, flex: 1 }}><BusinessData data={value} depth={depth + 1} field={key} /></dd>
    </div>
  ))}</dl> : <span>暂无可展示的业务字段，请到对应业务页面查看详情。</span>;
};

export default function BusinessRecordsCard({ card, answerText = '' }) {
  const target = card.navigationTarget || (card.capabilityCode?.startsWith('market.') ? 'MARKET'
    : card.capabilityCode?.startsWith('inventory.') ? 'INVENTORY'
      : card.capabilityCode?.startsWith('estimate.') ? 'ESTIMATE' : null);
  const payload = card.data && typeof card.data === 'object' && !Array.isArray(card.data) ? card.data : null;
  const conclusion30s = payload?.conclusion30s;
  const draftStatus = payload?.draftStatus;
  const actionHint = payload?.actionHint;
  const strippedConclusion = typeof conclusion30s === 'string'
    ? conclusion30s.replace(/^【(?:30秒|核心)结论】\s*/, '').trim()
    : '';
  const hideDuplicateConclusion = Boolean(
    answerText && strippedConclusion && (
      answerText.includes(strippedConclusion.slice(0, Math.min(24, strippedConclusion.length)))
      || answerText.includes('【核心结论】')
      || answerText.includes('**分析**')
    )
  );
  const hideDuplicateActionHint = Boolean(
    answerText && typeof actionHint === 'string' && actionHint.trim()
    && answerText.includes(actionHint.trim().slice(0, Math.min(18, actionHint.trim().length)))
  );
  return <div>
    <div className="agent-result-title">{card.title || '业务查询结果'}</div>
    <p className="agent-operating-scope">{card.scopeNote}</p>
    {draftStatus ? (
      <div style={{ margin: '8px 0', padding: '6px 10px', background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 6, color: '#ad4e00', fontSize: 12 }}>
        <strong>试算预览提示：</strong>{businessValue('draftStatus', draftStatus)}
      </div>
    ) : null}
    {conclusion30s && !hideDuplicateConclusion ? (
      <div style={{ margin: '8px 0', padding: '8px 12px', background: '#f0f5ff', borderLeft: '3px solid #1677ff', borderRadius: 4, color: '#1d39c4', fontSize: 13 }}>
        {conclusion30s}
      </div>
    ) : null}
    {actionHint && !hideDuplicateActionHint ? <p style={{ color: '#4e5969', fontSize: 12, margin: '6px 0' }}>{actionHint}</p> : null}
    {card.pageNum ? <p>第 {card.pageNum} 页 · 每页最多 {card.pageSize || 20} 项</p> : null}
    {card.totalCount !== undefined ? <p>匹配 {card.totalCount} 项 · 本次展示 {card.displayedCount ?? card.items?.length ?? 0} 项</p> : null}
    {card.unitGroups && Object.keys(card.unitGroups).length > 0 ? <p>{Object.entries(card.unitGroups).map(([unit, qty]) => `${qty} ${unit}`).join('；')}</p> : null}
    <BusinessData data={card.data ?? card.items} />
    {target && BUSINESS_PAGES[target] ? <Button type="primary" ghost style={{ marginTop: 12 }} onClick={() => requestBusinessNavigation({ target, filters: card.criteria })}>
      打开{BUSINESS_PAGES[target][1]}{payload?.targetPageName ? `（直达${payload.targetPageName}）` : ''}
    </Button> : null}
  </div>;
}
