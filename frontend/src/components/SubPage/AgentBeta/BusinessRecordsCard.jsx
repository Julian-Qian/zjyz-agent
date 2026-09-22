import React, { useState } from 'react';
import { Button, Empty } from 'antd';
import { BUSINESS_LABELS, BUSINESS_PAGES, businessValue, requestBusinessNavigation } from './agentBusinessResults';

const BusinessData = ({ data, depth = 0, field = '' }) => {
  const [expanded, setExpanded] = useState(false);
  if (data === null || data === undefined) return <span>未填写</span>;
  if (depth > 6) return <span>明细较多，请到业务页面查看</span>;
  if (Array.isArray(data)) {
    if (!data.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的记录" />;
    return <div>{data.slice(0, expanded ? data.length : 10).map((item, index) => (
      <div key={index} style={{ borderTop: '1px solid #e6edf7', padding: '10px 0' }}>
        {typeof item === 'object' ? <small>第 {index + 1} 项</small> : null}
        <BusinessData data={item} depth={depth + 1} field={field} />
      </div>
    ))}{data.length > 10 ? <Button type="link" onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : `展开本页全部 ${data.length} 项`}</Button> : null}</div>;
  }
  if (typeof data !== 'object') return <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{businessValue(field, data)}</span>;
  const fields = Object.entries(data).filter(([key, value]) => BUSINESS_LABELS[key] && value !== null && value !== undefined
    && !(field === 'kpi' && ['expectedQuantity', 'rentedQuantity', 'leasedQuantity', 'inventoryQuantity', 'totalQuantity'].includes(key))
    && (!['contactPerson', 'contactPhone'].includes(key) || data.contactVisibleFlag === undefined || Number(data.contactVisibleFlag) === 1));
  return fields.length ? <dl style={{ margin: 0 }}>{fields.map(([key, value]) => (
    <div key={key} style={{ margin: '5px 0', display: typeof value === 'object' ? 'block' : 'flex', gap: 12 }}>
      <dt style={{ color: '#667894', flex: '0 0 110px' }}>{BUSINESS_LABELS[key]}</dt>
      <dd style={{ margin: 0, minWidth: 0 }}><BusinessData data={value} depth={depth + 1} field={key} /></dd>
    </div>
  ))}</dl> : <span>暂无可展示的业务字段，请到对应业务页面查看详情。</span>;
};

export default function BusinessRecordsCard({ card }) {
  const target = card.navigationTarget || (card.capabilityCode?.startsWith('market.') ? 'MARKET'
    : card.capabilityCode?.startsWith('inventory.') ? 'INVENTORY'
      : card.capabilityCode?.startsWith('estimate.') ? 'ESTIMATE' : null);
  return <div>
    <div className="agent-result-title">{card.title || '业务查询结果'}</div>
    <p className="agent-operating-scope">{card.scopeNote}</p>
    {card.pageNum ? <p>第 {card.pageNum} 页 · 每页最多 {card.pageSize || 20} 项</p> : null}
    {card.totalCount !== undefined ? <p>匹配 {card.totalCount} 项 · 本次展示 {card.displayedCount ?? card.items?.length ?? 0} 项</p> : null}
    {card.unitGroups ? <p>{Object.entries(card.unitGroups).map(([unit, qty]) => `${qty} ${unit}`).join('；')}</p> : null}
    <BusinessData data={card.data ?? card.items} />
    {target && BUSINESS_PAGES[target] ? <Button style={{ marginTop: 12 }} onClick={() => requestBusinessNavigation({ target, filters: card.criteria })}>
      打开{BUSINESS_PAGES[target][1]}
    </Button> : null}
  </div>;
}
