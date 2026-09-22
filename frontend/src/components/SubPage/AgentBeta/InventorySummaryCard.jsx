import React from 'react';
import { normalizeInventorySummaryCard } from './inventoryCards';

const formatQuantity = (value) => (value == null ? '--' : value.toLocaleString('zh-CN', { maximumFractionDigits: 20 }));

export default function InventorySummaryCard({ card }) {
  const view = normalizeInventorySummaryCard(card);
  return (
    <section className="agent-inventory-summary" aria-label="库存概览">
      <div className="agent-inventory-summary-total">
        <span>当前库存材料</span>
        <div><strong>{formatQuantity(view.materialCount)}</strong><span>种</span></div>
      </div>
      <div className="agent-inventory-summary-heading">
        <strong>库存较低材料</strong>
        <span>展示 {view.items.length} 项</span>
      </div>
      {view.items.length ? (
        <div className="agent-inventory-summary-table-wrap">
          <table className="agent-inventory-summary-table" aria-label="库存较低材料明细">
            <thead><tr><th scope="col">材料 / 规格</th><th scope="col">当前库存</th><th scope="col">单位</th></tr></thead>
            <tbody>
              {view.items.map((item, index) => (
                <tr key={`${item.materialId || item.materialName}-${index}`}>
                  <td><strong>{item.materialName}</strong><small>{item.materialSpecification || '未填写规格'}</small></td>
                  <td className={item.inventoryQuantity < 0 ? 'is-negative' : ''}>{formatQuantity(item.inventoryQuantity)}</td>
                  <td>{item.inventoryUnit || '--'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <div className="agent-result-summary">暂无材料明细</div>}
      <div className="agent-inventory-summary-note">
        {view.items.some((item) => item.inventoryQuantity < 0) ? '红色数值表示负库存。' : ''}
        按库存数量从低到高展示，数量单位以各材料为准。
      </div>
    </section>
  );
}
