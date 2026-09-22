import AgentAttachmentPicker, { ContractReviewCard } from './AgentAttachmentPicker';
import AgentLearning, { LearningCards } from './AgentLearning';
import BusinessArtifactDownloads from './BusinessArtifactDownloads';
import BusinessRecordsCard from './BusinessRecordsCard';
import InventorySummaryCard from './InventorySummaryCard';
import { presentAssistantAnswer, hasVisibleResult, uniqueResultCards } from './answerPresentation';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Empty,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import {
  ArrowUpOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  StopOutlined,
  UploadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useDispatch, useSelector } from 'react-redux';
import TopBar from '@/components/TopBar/TopBar';
import AgentKnowledge from '@/components/SubPage/AgentKnowledge';
import XiaoYunAvatar from '@/components/XiaoYunAvatar';
import {
  ALL_AGENT_PROJECTS,
  addAgentThread,
  answerAgentWorkspaceInteraction,
  fetchAgentCapabilities,
  loadAgentProjects,
  removeAgentThread,
  renameAgentThread,
  resetRunView,
  retryAgentWorkspaceTask,
  selectAgentThread,
  setScopeOverridePending,
  setSelectedProjectIds,
  sendAgentWorkspaceMessage,
  stopAgentRun,
} from '@/store/modules/agent';
import {
  confirmRentOutDocumentFromImage,
  previewRentOutDocumentFromImage,
} from '@/api/agentDocumentIntake';
import { normalizeMaterialRankingGroups } from './materialRanking';
import { normalizeCapabilityBoundaryCard } from './capabilityBoundary';
import {
  normalizeInventoryOperationsCard,
  normalizeInventoryLedgerCard,
  inventoryMessageContent,
} from './inventoryCards';
import { normalizeDocumentAuditCard } from './documentAudit';
import {
  normalizeContractCommercialCard,
  normalizeMaterialLifecycleCard,
} from './analyticsCards';
import {
  normalizeFinanceEnterpriseCard,
  normalizeSupplierPayableCard,
} from './financeCards';
import {
  normalizeOwnerActionCenterCard,
  ownerActionBoundaryView,
  ownerRiskMetricSummary,
  OWNER_RISK_LABELS,
} from './riskCards';
import { buildTransientRunView } from './transientRunView';
import { AgentInteractionCard } from './AgentTaskPanel';
import { buildAgentTaskView, buildRoleStarters, normalizeCapabilityManifest } from './agentTaskView';
import './AgentBeta.scss';

const { TextArea } = Input;
const INPUT_LIMIT = 4000;
const SHOW_AGENT_ROLE_SWITCH = false;
const AGENT_ROLES = [
  { value: 'OWNER', label: '老板' },
  { value: 'FINANCE', label: '财务' },
  { value: 'PROJECT', label: '项目' },
  { value: 'WAREHOUSE', label: '仓库' },
];
const scopeOverrideKey = (projectIds = []) => {
  const explicit = projectIds
    .filter((projectId) => projectId && projectId !== ALL_AGENT_PROJECTS)
    .sort();
  return projectIds.includes(ALL_AGENT_PROJECTS) || !explicit.length
    ? 'ALL:'
    : `EXPLICIT:${explicit.join(',')}`;
};
const SINGLE_PROJECT_QUICK_PROMPTS = [
  '总结当前项目本月的经营情况、风险和建议，并说明数据依据',
  '查询当前项目仍在租或未归还的材料',
  '检索当前项目最近的业务单据',
  '这个项目的材料预估需要补充哪些参数？',
];
const ALL_PROJECTS_QUICK_PROMPTS = [
  '今年我新建和录入了哪些项目？',
  '统计当前全部项目中租入、租出、进行中和已完成的数量',
  '截至今天，生成今年及历史结转的逾期未付款项目催缴清单',
  '查询企业当前库存概况和低库存材料',
];
const MULTI_PROJECT_QUICK_PROMPTS = [
  '列出当前选中项目的名称、负责人、业务类型和录入时间',
  '统计当前选中项目中租入、租出、进行中和已完成的数量',
  '当前选中的项目分别是什么时候录入的？',
  '查询企业当前库存概况和低库存材料',
];

const STATUS_META = {
  QUEUED: ['排队中', 'default'],
  PLANNING: ['规划中', 'processing'],
  RUNNING: ['执行中', 'processing'],
  FINALIZING: ['收尾中', 'processing'],
  COMPLETED: ['已完成', 'success'],
  FAILED: ['失败', 'error'],
  CANCELLED: ['已取消', 'warning'],
  INTERRUPTED: ['已中断', 'warning'],
  WAITING_INPUT: ['待补充', 'warning'],
  WAITING_USER: ['待补充', 'warning'],
  WAITING_APPROVAL: ['待审批', 'warning'],
  BLOCKED: ['受阻', 'error'],
};

const formatTime = (value) => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  });
};

const HEALTH_META = {
  RED: ['红色预警', 'red'],
  YELLOW: ['黄色预警', 'orange'],
  GREEN: ['正常', 'green'],
};

const RISK_META = {
  HIGH: ['高', 'red'],
  MEDIUM: ['中', 'orange'],
  INFO: ['提示', 'blue'],
};

const formatCardMoney = (value) => {
  if (value === null || value === undefined || value === '') return '--';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '--';
  return `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

const formatCardNumber = (value) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toLocaleString('zh-CN') : '--';
};

const dataOf = (response, fallback) => {
  if (!response?.succeed) throw new Error(response?.errorMessage || fallback);
  return response.data;
};

const formatPreviewValue = (value) => {
  if (value === null || value === undefined || value === '') return '--';
  return value;
};

const isMarkdownTableLine = (line) => {
  const value = (line || '').trim();
  return value.startsWith('|') && value.endsWith('|') && value.split('|').length > 3;
};

const isMarkdownTableDivider = (line) => /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line || '');

const splitTableRow = (line) => line
  .trim()
  .replace(/^\|/, '')
  .replace(/\|$/, '')
  .split('|')
  .map((cell) => cell.trim());

const renderInlineText = (text) => {
  const value = String(text || '');
  const segments = value.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).filter(Boolean);
  return segments.map((segment, index) => {
    if (segment.startsWith('**') && segment.endsWith('**')) {
      return <strong key={`${segment}-${index}`}>{segment.slice(2, -2)}</strong>;
    }
    if (segment.startsWith('`') && segment.endsWith('`')) {
      return <code key={`${segment}-${index}`}>{segment.slice(1, -1)}</code>;
    }
    return <React.Fragment key={`${segment}-${index}`}>{segment}</React.Fragment>;
  });
};

const parseMessageBlocks = (content) => {
  const lines = String(content || '').replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  let paragraph = [];
  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push({ type: 'paragraph', lines: paragraph });
      paragraph = [];
    }
  };

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const nextLine = lines[index + 1];
    if (isMarkdownTableLine(line) && isMarkdownTableDivider(nextLine)) {
      flushParagraph();
      const headers = splitTableRow(line);
      index += 2;
      const rows = [];
      while (index < lines.length && isMarkdownTableLine(lines[index])) {
        rows.push(splitTableRow(lines[index]));
        index += 1;
      }
      index -= 1;
      blocks.push({ type: 'table', headers, rows });
    } else if (!line.trim()) {
      flushParagraph();
    } else if (/^\s{0,3}#{1,6}\s+/.test(line)) {
      flushParagraph();
      blocks.push({ type: 'heading', text: line.replace(/^\s{0,3}#{1,6}\s+/, '').replace(/\s+#+\s*$/, '') });
    } else if (/^\s*[-*]\s+/.test(line) || /^\s*\d+[.)]\s+/.test(line)) {
      flushParagraph();
      const items = [];
      const ordered = /^\s*\d+[.)]\s+/.test(line);
      while (index < lines.length && (ordered ? /^\s*\d+[.)]\s+/.test(lines[index]) : /^\s*[-*]\s+/.test(lines[index]))) {
        items.push(lines[index].replace(/^\s*(?:[-*]|\d+[.)])\s+/, ''));
        index += 1;
      }
      index -= 1;
      blocks.push({ type: ordered ? 'ordered-list' : 'list', items });
    } else {
      paragraph.push(line);
    }
  }
  flushParagraph();
  return blocks;
};

const MessageContent = ({ content, isStreaming = false }) => {
  const blocks = useMemo(() => parseMessageBlocks(content), [content]);
  if (!content) return null;

  return (
    <div className={`agent-message-content ${isStreaming ? 'typing' : ''}`}>
      {blocks.map((block, index) => {
        if (block.type === 'heading') {
          return <h4 className="agent-markdown-heading" key={`heading-${index}`}>{renderInlineText(block.text)}</h4>;
        }
        if (block.type === 'table') {
          return (
            <div className="agent-markdown-table-wrap" key={`table-${index}`}>
              <table className="agent-markdown-table">
                <thead>
                  <tr>{block.headers.map((header, cellIndex) => <th key={`${header}-${cellIndex}`}>{renderInlineText(header)}</th>)}</tr>
                </thead>
                <tbody>
                  {block.rows.map((row, rowIndex) => (
                    <tr key={`row-${rowIndex}`}>
                      {block.headers.map((_, cellIndex) => (
                        <td key={`cell-${rowIndex}-${cellIndex}`}>{renderInlineText(row[cellIndex] || '')}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        if (block.type === 'list' || block.type === 'ordered-list') {
          const ListTag = block.type === 'ordered-list' ? 'ol' : 'ul';
          return (
            <ListTag className="agent-markdown-list" key={`list-${index}`}>
              {block.items.map((item, itemIndex) => <li key={`${item}-${itemIndex}`}>{renderInlineText(item)}</li>)}
            </ListTag>
          );
        }
        return (
          <p key={`paragraph-${index}`}>
            {block.lines.map((line, lineIndex) => (
              <React.Fragment key={`${line}-${lineIndex}`}>
                {lineIndex > 0 ? <br /> : null}
                {renderInlineText(line)}
              </React.Fragment>
            ))}
          </p>
        );
      })}
    </div>
  );
};

const ProjectOperatingReportCard = ({ card }) => {
  const healthMeta = HEALTH_META[card?.health?.level] || [card?.health?.label || '待判断', 'default'];
  const period = card?.period || {};
  const operations = card?.operations || {};
  const fees = card?.fees || {};
  const risks = Array.isArray(card?.risks) ? card.risks : [];
  const actions = Array.isArray(card?.actions) ? card.actions : [];
  const materials = Array.isArray(card?.materialTop) ? card.materialTop.slice(0, 5) : [];

  if (card?.dataAvailable === false) {
    return (
      <div className="agent-operating-report agent-operating-unavailable">
        <div className="agent-operating-head">
          <div>
            <strong>{card?.projectName || '项目经营报告'}</strong>
            <span>当前项目暂不支持完整经营快照</span>
          </div>
          <Tag>口径待补</Tag>
        </div>
        <p>{card?.summary || '当前项目的经营统计口径尚未接入。'}</p>
        <div className="agent-operating-scope">口径说明：{card?.scopeNote}</div>
      </div>
    );
  }

  return (
    <div className="agent-operating-report">
      <div className="agent-operating-head">
        <div>
          <strong>{card?.projectName || '项目经营报告'}</strong>
          <span>{period.startDate && period.endDate ? `${period.startDate} 至 ${period.endDate}` : '统计周期未提供'}</span>
        </div>
        <Tag color={healthMeta[1]}>{healthMeta[0]}</Tag>
      </div>

      <div className="agent-operating-kpis">
        {(card?.kpis || []).map((kpi) => (
          <div className={`agent-operating-kpi ${kpi?.tone || 'neutral'}`} key={kpi?.code || kpi?.label}>
            <span>{kpi?.label || '--'}</span>
            <strong>
              {kpi?.displayValue ?? '--'}
              {kpi?.displayValue === '--' ? null : <small>{kpi?.unit || ''}</small>}
            </strong>
          </div>
        ))}
      </div>

      <div className="agent-operating-section">
        <h5>租赁动态</h5>
        <div className="agent-operating-flow">
          <span>期初在租 <strong>{formatCardNumber(operations.openingRentedQuantity)}</strong></span>
          <span>本期租出 <strong>+{formatCardNumber(operations.rentQuantity)}</strong></span>
          <span>本期归还 <strong>-{formatCardNumber(operations.returnQuantity)}</strong></span>
          <span>赔偿核销 <strong>-{formatCardNumber(operations.compensationQuantity)}</strong></span>
          <span>期末在租 <strong>{formatCardNumber(operations.closingRentedQuantity)}</strong></span>
        </div>
      </div>

      <div className="agent-operating-section">
        <h5>已对账费用构成</h5>
        <div className="agent-operating-fees">
          <span>租金 <strong>{formatCardMoney(fees.periodRentFee)}</strong></span>
          <span>赔偿费 <strong>{formatCardMoney(fees.periodCompensationFee)}</strong></span>
          <span>杂费 <strong>{formatCardMoney(fees.periodIncidentalFee)}</strong></span>
          <span>其他 <strong>{formatCardMoney(fees.periodOtherFee)}</strong></span>
        </div>
      </div>

      {risks.length ? (
        <div className="agent-operating-section">
          <h5>主要风险</h5>
          <div className="agent-operating-risks">
            {risks.slice(0, 4).map((risk, index) => {
              const riskMeta = RISK_META[risk?.level] || [risk?.level || '提示', 'default'];
              return (
                <div key={`${risk?.code || 'risk'}-${index}`}>
                  <Tag color={riskMeta[1]}>{riskMeta[0]}</Tag>
                  <span><strong>{risk?.title || '经营风险'}</strong>{risk?.detail ? `：${risk.detail}` : ''}</span>
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="agent-operating-clear">当前未识别到需要优先处理的经营风险。</div>
      )}

      {actions.length ? (
        <div className="agent-operating-section">
          <h5>建议动作</h5>
          <ol className="agent-operating-actions">
            {actions.slice(0, 4).map((action, index) => (
              <li key={`${action?.riskCode || 'action'}-${index}`}>{action?.title || action}</li>
            ))}
          </ol>
        </div>
      ) : null}

      {materials.length ? (
        <div className="agent-operating-section">
          <h5>期末在租 Top 材料</h5>
          <div className="agent-operating-materials">
            {materials.map((material, index) => (
              <span key={material?.materialId || index}>
                {material?.materialName || '未命名材料'}{material?.materialSpecification ? ` · ${material.materialSpecification}` : ''}
                <strong>{formatCardNumber(material?.closingRentedQuantity)} {material?.materialUnit || '件'}</strong>
              </span>
            ))}
          </div>
        </div>
      ) : null}

      <div className="agent-operating-scope">口径说明：{card?.scopeNote || '金额按系统已确认数据统计。'}</div>
    </div>
  );
};

const PRIORITY_META = {
  P1: ['P1 立即催缴', 'red'],
  P2: ['P2 本周跟进', 'orange'],
  P3: ['P3 常规跟踪', 'blue'],
};

const ReceivableCollectionCard = ({ card }) => {
  const summary = card?.summary || {};
  const items = Array.isArray(card?.items) ? card.items : [];
  return (
    <div className="agent-collection-card">
      <div className="agent-collection-head">
        <div>
          <strong>企业应收催缴清单</strong>
          <span>截至 {card?.asOfDate || '--'} · {card?.scope === 'DUE_IN_YEAR' ? '仅本年到期' : card?.scope === 'HISTORICAL_CARRYOVER' ? '仅历史结转' : '本年及历史结转'}</span>
        </div>
        <Tag color="red">逾期未清</Tag>
      </div>
      <div className="agent-collection-kpis">
        <div><span>项目数</span><strong>{formatCardNumber(summary.projectCount)}</strong></div>
        <div><span>未清总额</span><strong>{formatCardMoney(summary.totalOutstanding)}</strong></div>
        <div><span>本年到期</span><strong>{formatCardMoney(summary.currentYearOutstanding)}</strong></div>
        <div><span>历史结转</span><strong>{formatCardMoney(summary.carryoverOutstanding)}</strong></div>
        <div className="danger"><span>P1 项目</span><strong>{formatCardNumber(summary.p1Count)}</strong></div>
        <div><span>最长逾期</span><strong>{formatCardNumber(summary.maxOverdueDays)}天</strong></div>
      </div>
      {items.length ? (
        <div className="agent-collection-table">
          <div className="agent-collection-row header"><span>项目 / 客户</span><span>逾期</span><span>未清金额</span><span>级别</span></div>
          {items.map((item, index) => {
            const priority = PRIORITY_META[item?.priority] || [item?.priority || '--', 'default'];
            const yearGroup = item?.yearGroup === 'MIXED' ? '本年+结转'
              : item?.yearGroup === 'CARRYOVER' ? '历史结转' : '本年到期';
            return (
              <div className="agent-collection-row" key={item?.projectId || index}>
                <span><strong>{item?.projectName || item?.projectId || '未命名项目'}</strong><small>{item?.customerName || '客户未填写'} · {item?.managerName || '负责人未填写'} · {yearGroup}</small></span>
                <span>{formatCardNumber(item?.overdueDays)}天</span>
                <span>{formatCardMoney(item?.outstandingAmount)}</span>
                <span><Tag color={priority[1]}>{priority[0]}</Tag></span>
              </div>
            );
          })}
        </div>
      ) : <div className="agent-operating-clear">当前筛选口径没有逾期未清项目。</div>}
      <div className="agent-operating-scope">
        当前展示 {card?.displayedCount ?? items.length} 项，共 {card?.totalArtifactCount ?? items.length} 项；可继续追问以缩小范围。
      </div>
    </div>
  );
};

const FinanceDirectionPanel = ({ title, direction, cashLabel, outstandingLabel, asOfDate }) => (
  <div className="agent-finance-direction">
    <div className="agent-finance-direction-title">
      <strong>{title}</strong>
      <span>{formatCardNumber(direction.projectCount)} 个项目 · {formatCardNumber(direction.periodCount)} 个正式账期</span>
    </div>
    <div className="agent-collection-kpis">
      <div><span>已对账本金 · 截至 {asOfDate}</span><strong>{formatCardMoney(direction.postedPrincipal)}</strong></div>
      <div><span>ACTIVE 本金核销 · 截至 {asOfDate}</span><strong>{formatCardMoney(direction.allocatedPrincipalAsOf)}</strong></div>
      <div className="danger"><span>{outstandingLabel} · 截至 {asOfDate}</span><strong>{formatCardMoney(direction.outstandingPrincipalAsOf)}</strong></div>
      <div><span>截至今日累计{cashLabel}</span><strong>{formatCardMoney(direction.cumulativeCash.totalRegistered)}</strong></div>
      <div><span>到期未清 · 截至 {asOfDate}</span><strong>{formatCardMoney(direction.dueAsOfOutstanding)}</strong></div>
    </div>
    <div className="agent-finance-cash-breakdown">
      <span>租金 {formatCardMoney(direction.cumulativeCash.rent)}</span>
      <span>赔偿 {formatCardMoney(direction.cumulativeCash.compensation)}</span>
      <span>押金 {formatCardMoney(direction.cumulativeCash.deposit)}</span>
      <span>滞纳金 {formatCardMoney(direction.cumulativeCash.lateFee)}</span>
      <span>其他 {formatCardMoney(direction.cumulativeCash.other)}</span>
    </div>
  </div>
);

const FinancePeriodCashPanel = ({ title, cash, label }) => (
  <div className="agent-finance-period-cash">
    <div className="agent-finance-direction-title">
      <strong>{title}</strong>
      <span>{label}</span>
    </div>
    <div className="agent-finance-period-total">
      <span>期间登记总额</span><strong>{formatCardMoney(cash.totalRegistered)}</strong>
    </div>
    <div className="agent-finance-cash-breakdown">
      <span>租金 {formatCardMoney(cash.rent)}</span>
      <span>赔偿 {formatCardMoney(cash.compensation)}</span>
      <span>押金 {formatCardMoney(cash.deposit)}</span>
      <span>滞纳金 {formatCardMoney(cash.lateFee)}</span>
      <span>其他 {formatCardMoney(cash.other)}</span>
    </div>
  </div>
);

const FinanceWarnings = ({ warnings }) => (warnings.length ? (
  <Alert
    className="agent-finance-warning"
    type="warning"
    showIcon
    message="财务口径提示"
    description={warnings.slice(0, 6).map((warning, index) => (
      <div key={`${warning}-${index}`}>{presentAssistantAnswer(warning)}</div>
    ))}
  />
) : null);

const FinanceEnterpriseKpiCard = ({ card }) => {
  const data = normalizeFinanceEnterpriseCard(card);
  return (
    <div className="agent-collection-card agent-finance-card">
      <div className="agent-collection-head">
        <div>
          <strong>企业财务台账总览</strong>
          <span>{data.balanceLabel} · 币种 {data.currency}</span>
          {data.hasCashPeriod ? <span>{data.cashPeriodLabel}</span> : null}
        </div>
        <Tag color="blue">受限核算口径</Tag>
      </div>
      <div className="agent-finance-columns">
        <FinanceDirectionPanel title="租出 · 客户应收" direction={data.rentOut} cashLabel="客户登记实收" outstandingLabel="未清本金" asOfDate={data.asOfDate || '--'} />
        <FinanceDirectionPanel title="租入 · 供应商应付" direction={data.rentIn} cashLabel="供应商登记实付" outstandingLabel="未付本金" asOfDate={data.asOfDate || '--'} />
      </div>
      {data.hasCashPeriod ? (
        <div className="agent-finance-columns">
          <FinancePeriodCashPanel title="租出 · 期间客户登记实收" cash={data.rentOut.periodCash} label={data.cashPeriodLabel} />
          <FinancePeriodCashPanel title="租入 · 期间供应商登记实付" cash={data.rentIn.periodCash} label={data.cashPeriodLabel} />
        </div>
      ) : null}
      <FinanceWarnings warnings={data.warnings} />
      <div className="agent-operating-scope">
        本金核销取 ACTIVE 分配记录；登记实收/实付不等于本金核销。到期日口径：{data.dueDateBasis || 'SETTLEMENT_PERIOD_END'}。
        范围内共 {formatCardNumber(data.totalCount)} 个项目，明细载荷 {formatCardNumber(data.displayedCount)} 项
        {data.truncated ? `（已按 ${formatCardNumber(data.limit)} 项截断）` : ''}。
      </div>
    </div>
  );
};

const SupplierPayableSummaryCard = ({ card }) => {
  const data = normalizeSupplierPayableCard(card);
  const summary = data.summary;
  return (
    <div className="agent-collection-card agent-finance-card">
      <div className="agent-collection-head">
        <div>
          <strong>供应商应付汇总</strong>
          <span>{data.balanceLabel} · 币种 {data.currency}</span>
          {data.hasCashPeriod ? <span>{data.cashPeriodLabel}</span> : null}
        </div>
        <Tag color="orange">租入项目</Tag>
      </div>
      <div className="agent-collection-kpis">
        <div><span>已对账应付本金 · 截至 {data.asOfDate || '--'}</span><strong>{formatCardMoney(summary.postedPrincipal)}</strong></div>
        <div><span>截至今日累计供应商登记实付</span><strong>{formatCardMoney(summary.cumulativeCash.totalRegistered)}</strong></div>
        <div><span>ACTIVE 本金核销 · 截至 {data.asOfDate || '--'}</span><strong>{formatCardMoney(summary.allocatedPrincipalAsOf)}</strong></div>
        <div className="danger"><span>未付本金 · 截至 {data.asOfDate || '--'}</span><strong>{formatCardMoney(summary.outstandingPrincipalAsOf)}</strong></div>
        <div className="danger"><span>到期未付 · 截至 {data.asOfDate || '--'}</span><strong>{formatCardMoney(summary.dueAsOfOutstanding)}</strong></div>
      </div>
      <div className="agent-finance-cash-breakdown">
        <span>严格逾期 {formatCardMoney(summary.overdueOutstanding)}</span>
        <span>今日到期 {formatCardMoney(summary.dueTodayOutstanding)}</span>
        <span>未分配本金付款 {formatCardMoney(summary.cumulativeCash.unallocatedPrincipalAsOf)}</span>
      </div>
      {data.hasCashPeriod ? (
        <FinancePeriodCashPanel title="期间供应商登记实付" cash={summary.periodCash} label={data.cashPeriodLabel} />
      ) : null}
      {data.items.length ? (
        <div className="agent-collection-table agent-finance-table">
          <div className="agent-collection-row header"><span>项目 / 当前合作单位</span><span>已对账</span><span>{data.hasCashPeriod ? '期间登记实付' : '累计登记实付'}</span><span>未付本金</span><span>到期未付</span></div>
          {data.items.map((item, index) => (
            <div className="agent-collection-row" key={item?.projectId || index}>
              <span><strong>{item?.projectName || item?.projectId || '未命名项目'}</strong><small>{item?.counterpartyName || '合作单位未填写'}</small></span>
              <span>{formatCardMoney(item?.postedPrincipal)}</span>
              <span>{formatCardMoney(data.hasCashPeriod ? item?.periodRegisteredCash : item?.registeredCash)}</span>
              <span>{formatCardMoney(item?.outstandingPrincipalAsOf)}</span>
              <span>{formatCardMoney(item?.dueAsOfOutstanding)}</span>
            </div>
          ))}
        </div>
      ) : <div className="agent-operating-clear">当前范围没有租入项目应付台账。</div>}
      <FinanceWarnings warnings={data.warnings} />
      <div className="agent-operating-scope">
        展示 {formatCardNumber(data.displayedCount)} / {formatCardNumber(data.totalCount)} 个租入项目
        {data.truncated ? `，最多 ${formatCardNumber(data.limit)} 项` : ''}。供应商名取当前项目合作单位；到期日口径：{data.dueDateBasis || 'SETTLEMENT_PERIOD_END'}。
      </div>
    </div>
  );
};

const OwnerActionCenterCard = ({ card }) => {
  const data = normalizeOwnerActionCenterCard(card);
  const boundary = ownerActionBoundaryView(data);
  return (
    <div className="agent-owner-action-card">
      <div className="agent-owner-action-head">
        <div>
          <strong>老板行动中心</strong>
          <span>截至 {data.asOfDate || '--'} · 评分版本 {data.scoringVersion || '--'}</span>
        </div>
        <Tag color={data.scoreIncomplete ? 'orange' : 'blue'}>
          {data.scoreIncomplete ? '部分维度排名' : '确定性行动榜'}
        </Tag>
      </div>
      {data.scoreIncomplete ? (
        <Alert
          className="agent-owner-action-boundary"
          type="error"
          showIcon
          message={boundary.title}
          description={boundary.description}
        />
      ) : null}
      {data.failedDimensions.map((item, index) => (
        <Alert
          className="agent-owner-action-boundary"
          type="error"
          showIcon
          key={`${item?.riskType || 'failed'}-${index}`}
          message={`${OWNER_RISK_LABELS[item?.riskType] || item?.riskType || '风险维度'}核算失败${item?.errorCode ? `（${item.errorCode}）` : ''}`}
          description={item?.reason || '该维度未参与本次评分。'}
        />
      ))}
      {data.unsupportedDimensions.map((item, index) => (
        <Alert
          className="agent-owner-action-boundary"
          type="warning"
          showIcon
          key={`${item?.riskType || 'unsupported'}-${index}`}
          message={`${OWNER_RISK_LABELS[item?.riskType] || item?.riskType || '风险维度'}暂未纳入`}
          description={item?.reason || '当前缺少可靠事实源。'}
        />
      ))}
      <div className="agent-owner-action-kpis">
        <span>范围项目 <strong>{formatCardNumber(data.totalProjectCount)}</strong></span>
        <span>匹配项目 <strong>{formatCardNumber(data.projectTotalCount)}</strong></span>
        <span>行动事项 <strong>{formatCardNumber(data.actionTotalCount)}</strong></span>
        <span>展示项目 <strong>{formatCardNumber(data.displayedProjectCount)}</strong></span>
      </div>
      {data.dimensionStatuses.length ? (
        <div className="agent-owner-action-dimensions">
          {data.dimensionStatuses.map((item, index) => (
            <Tag
              key={`${item?.riskType || 'dimension'}-${index}`}
              color={item?.status === 'FAILED' ? 'red' : item?.status === 'SUPPORTED' ? 'green' : 'orange'}
            >
              {OWNER_RISK_LABELS[item?.riskType] || item?.riskType || '风险维度'} · {item?.status || 'UNKNOWN'} · {formatCardNumber(item?.itemCount)}项
            </Tag>
          ))}
        </div>
      ) : null}
      {data.items.length ? (
        <div className="agent-owner-action-projects">
          {data.items.map((item, index) => (
            <div className="agent-owner-action-project" key={item?.projectId || index}>
              <div className="agent-owner-action-project-head">
                <div>
                  <strong>{item?.projectName || item?.projectId || '未命名项目'}</strong>
                  <span>{item?.managerName ? `负责人：${item.managerName}` : '负责人未填写'} · {formatCardNumber(item?.riskCount)} 项风险</span>
                </div>
                <b>{formatCardNumber(item?.totalScore)} 分</b>
              </div>
              <div className="agent-owner-action-score-parts">
                {item.scoreContributions.map((part, partIndex) => (
                  <span key={`${part?.code || 'project-score'}-${partIndex}`}>{part?.label || part?.code} +{formatCardNumber(part?.points)}</span>
                ))}
              </div>
              <div className="agent-owner-action-risks">
                {item.risks.map((risk, riskIndex) => (
                  <div className="agent-owner-action-risk" key={`${risk?.riskType || 'risk'}-${riskIndex}`}>
                    <span>
                      <Tag color={risk?.score >= 55 ? 'red' : risk?.score >= 35 ? 'orange' : 'blue'}>
                        {OWNER_RISK_LABELS[risk?.riskType] || risk?.riskType || '风险'}
                      </Tag>
                      <small>{ownerRiskMetricSummary(risk)} · 证据日 {risk?.evidenceDate || '--'}</small>
                    </span>
                    <strong>{formatCardNumber(risk?.score)} 分</strong>
                    <p>{risk?.suggestedAction || '请核对事实后安排责任人处理。'}</p>
                    <div className="agent-owner-action-score-parts">
                      {risk.scoreContributions.map((part, partIndex) => (
                        <span key={`${part?.code || 'risk-score'}-${partIndex}`}>{part?.label || part?.code} +{formatCardNumber(part?.points)}</span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
              <div className="agent-owner-action-primary">首要行动：{item?.suggestedAction || '请按风险明细处理。'}</div>
            </div>
          ))}
        </div>
      ) : <div className="agent-operating-clear">{boundary.emptyText}</div>}
      <FinanceWarnings warnings={data.warnings} />
      <div className="agent-operating-scope">
        项目榜展示 {formatCardNumber(data.displayedProjectCount)} / {formatCardNumber(data.projectTotalCount)}，
        行动榜载荷 {formatCardNumber(data.actionDisplayedCount)} / {formatCardNumber(data.actionTotalCount)}
        {data.truncated ? `，项目榜最多 ${formatCardNumber(data.limit)} 项` : ''}
        {data.actionTruncated ? `，行动榜最多 ${formatCardNumber(data.limit)} 项` : ''}。{data.scopeNote}
      </div>
    </div>
  );
};

const MaterialTransactionRankingCard = ({ card }) => {
  const items = Array.isArray(card?.items) ? card.items : [];
  const unitGroups = normalizeMaterialRankingGroups(card);
  const metricIsDocuments = card?.metric === 'DOCUMENT_COUNT';
  const period = card?.startDate || card?.endDate
    ? `${card?.startDate || '不限'} 至 ${card?.endDate || '今天'}`
    : '全部日期';
  return (
    <div className="agent-material-ranking">
      <div className="agent-material-ranking-head">
        <div>
          <strong>{card?.flowLabel || '材料'}材料排行</strong>
          <span>{period} · {card?.groupBy === 'MATERIAL_NAME' ? '跨规格汇总' : '按规格统计'}</span>
        </div>
        <Tag color="blue">{metricIsDocuments ? '按单位分组 · 单据数' : '按单位分榜'}</Tag>
      </div>
      <div className="agent-material-ranking-kpis">
        <span>项目 <strong>{formatCardNumber(card?.projectCount)}</strong></span>
        <span>单据 <strong>{formatCardNumber(card?.documentCount)}</strong></span>
        <span>材料组 <strong>{formatCardNumber(card?.displayedCount ?? items.length)} / {formatCardNumber(card?.totalCount ?? card?.materialGroupCount)}</strong></span>
      </div>
      {card?.aggregationWarning ? (
        <Alert className="agent-material-ranking-warning" type="warning" showIcon message={card.aggregationWarning} />
      ) : null}
      {unitGroups.length ? unitGroups.map((group) => (
        <div className="agent-material-unit-group" key={group.unit}>
          <div className="agent-material-unit-head">
            <strong>单位：{group.unit}</strong>
            <span>
              展示 {formatCardNumber(group.displayedCount)} / {formatCardNumber(group.totalCount)}
              {group.truncated ? `，每组最多 ${formatCardNumber(card?.limit)} 项` : ''}
            </span>
          </div>
          <div className="agent-material-ranking-table">
            <div className="agent-material-ranking-row header">
              <span>组内排名 / 材料</span><span>数量（{group.unit}）</span><span>单据</span><span>项目</span>
            </div>
            {group.items.map((item, index) => (
              <div className="agent-material-ranking-row" key={`${item?.materialName || 'material'}-${item?.materialSpecification || ''}-${group.unit}-${index}`}>
                <span>
                  <b>{item?.rank || index + 1}</b>
                  <strong>{item?.materialName || '未命名材料'}</strong>
                  <small>
                    {item?.materialSpecification || (Number(item?.specificationCount) > 1 ? `${item.specificationCount}个规格` : '未填写规格')}
                  </small>
                </span>
                <span>{formatCardNumber(item?.totalQuantity)} {group.unit}</span>
                <span>{formatCardNumber(item?.documentCount)}</span>
                <span>{formatCardNumber(item?.projectCount)}</span>
              </div>
            ))}
          </div>
        </div>
      )) : <div className="agent-operating-clear">当前统计口径没有材料流水。</div>}
      {card?.truncated ? (
        <div className="agent-material-ranking-truncated">结果已截断；当前按每个计数单位分别展示前 {formatCardNumber(card?.limit)} 项。</div>
      ) : null}
      <div className="agent-operating-scope">口径说明：{card?.scopeNote || '按单据业务日期汇总材料流水。'}</div>
    </div>
  );
};

const ContractCommercialCard = ({ card }) => {
  const view = normalizeContractCommercialCard(card);
  return (
    <div className="agent-analytics-card">
      <div className="agent-result-title">合同商业分析 · 核查 {formatCardNumber(view.projectCount)} 个项目</div>
      {view.completeness.totalCount > 0 ? (
        <>
          <div className="agent-analytics-section-title">
            字段完整性问题 {formatCardNumber(view.completeness.totalCount)} 项
            {view.completeness.truncated ? `（展示前 ${formatCardNumber(view.completeness.displayedCount)} 项）` : ''}
          </div>
          {view.completeness.items.map((item, index) => (
            <div className="agent-result-row" key={`c-${item.projectName}-${index}`}>
              <span>{item.projectName}{item.contractName ? ` · ${item.contractName}` : ''}</span>
              <small>{item.issueLabels.join('、')}</small>
            </div>
          ))}
        </>
      ) : null}
      {view.priceComparison.totalCount > 0 ? (
        <>
          <div className="agent-analytics-section-title">
            跨项目价差 {formatCardNumber(view.priceComparison.totalCount)} 组
            {view.priceComparison.truncated ? `（展示前 ${formatCardNumber(view.priceComparison.displayedCount)} 组）` : ''}
          </div>
          {view.priceComparison.items.map((item, index) => (
            <div className="agent-result-row" key={`p-${item.materialLabel}-${index}`}>
              <span>{item.materialLabel}（{item.countingUnit}）· {formatCardNumber(item.projectCount)} 个项目</span>
              <small>
                {item.minDailyRent == null ? '--' : formatCardMoney(item.minDailyRent)}（{item.minProjectName}）~
                {item.maxDailyRent == null ? '--' : formatCardMoney(item.maxDailyRent)}（{item.maxProjectName}）
              </small>
            </div>
          ))}
        </>
      ) : null}
      <div className="agent-operating-scope">口径说明：{view.scopeNote}</div>
    </div>
  );
};

const MaterialLifecycleCard = ({ card }) => {
  const view = normalizeMaterialLifecycleCard(card);
  return (
    <div className="agent-analytics-card">
      <div className="agent-result-title">材料生命周期分析{view.asOfDate ? ` · 截至 ${view.asOfDate}` : ''}</div>
      {view.overReturn.totalCount > 0 ? (
        <>
          <div className="agent-analytics-section-title">
            多还材料组 {formatCardNumber(view.overReturn.totalCount)} 组
            {view.overReturn.truncated ? `（展示前 ${formatCardNumber(view.overReturn.displayedCount)} 组）` : ''}
          </div>
          {view.overReturn.items.map((item, index) => (
            <div className="agent-result-row" key={`o-${item.materialLabel}-${index}`}>
              <span>{item.projectName} · {item.materialLabel}</span>
              <small>差额 {item.outstandingQuantity == null ? '--' : formatCardNumber(item.outstandingQuantity)} {item.materialUnit}</small>
            </div>
          ))}
        </>
      ) : null}
      {view.staleOccupancy.totalCount > 0 ? (
        <>
          <div className="agent-analytics-section-title">
            超过 {formatCardNumber(view.minStagnantDays)} 天无归还的项目 {formatCardNumber(view.staleOccupancy.totalCount)} 个
            {view.staleOccupancy.truncated ? `（展示前 ${formatCardNumber(view.staleOccupancy.displayedCount)} 个）` : ''}
          </div>
          {view.staleOccupancy.items.map((item, index) => (
            <div className="agent-result-row" key={`s-${item.projectName}-${index}`}>
              <span>{item.projectName}{item.managerName ? ` · ${item.managerName}` : ''}</span>
              <small>
                未归还 {formatCardNumber(item.outstandingMaterialGroups)} 组
                {item.staleDays == null ? ' · 从未有归还流水' : ` · ${formatCardNumber(item.staleDays)} 天无归还`}
              </small>
            </div>
          ))}
        </>
      ) : null}
      {view.compensationRate.totalCount > 0 ? (
        <>
          <div className="agent-analytics-section-title">
            赔偿率排行 {formatCardNumber(view.compensationRate.totalCount)} 组
            {view.compensationRate.truncated ? `（展示前 ${formatCardNumber(view.compensationRate.displayedCount)} 组）` : ''}
          </div>
          {view.compensationRate.items.map((item, index) => (
            <div className="agent-result-row" key={`r-${item.materialLabel}-${index}`}>
              <span>{item.materialLabel}（{item.materialUnit}）</span>
              <small>
                赔 {formatCardNumber(item.compensatedQuantity)} / 租 {formatCardNumber(item.rentedQuantity)}
                {item.compensationRate == null ? '' : ` · ${(item.compensationRate * 100).toFixed(1)}%`}
              </small>
            </div>
          ))}
        </>
      ) : null}
      <div className="agent-operating-scope">口径说明：{view.scopeNote}</div>
    </div>
  );
};

const DocumentAuditCard = ({ card }) => {
  const view = normalizeDocumentAuditCard(card);
  return (
    <div className="agent-document-audit">
      <div className="agent-result-title">单据审核清单{view.asOfDate ? ` · 截至 ${view.asOfDate}` : ''}</div>
      {view.issueSummary ? (
        <div className="agent-document-audit-summary">{view.issueSummary}{view.typeSummary ? `（${view.typeSummary}）` : ''}</div>
      ) : null}
      {view.items.map((item, index) => (
        <div className="agent-result-row" key={`${item.documentName}-${index}`}>
          <span>
            {item.documentTypeLabel} · {item.documentName}
            {item.projectName ? ` · ${item.projectName}` : ''}
          </span>
          <small>{item.issueLabels.join('、')}{item.businessDate ? ` · ${item.businessDate}` : ''}</small>
        </div>
      ))}
      <div className="agent-operating-scope">
        共 {formatCardNumber(view.totalCount)} 张{view.truncated ? `，展示前 ${formatCardNumber(view.displayedCount)} 张` : ''}。
        口径说明：{view.scopeNote}
      </div>
    </div>
  );
};

const InventoryOperationsCard = ({ card }) => {
  const view = normalizeInventoryOperationsCard(card);
  return (
    <div className="agent-inventory-operations">
      <div className="agent-result-title">库存异常运营 · {view.filterLabel}</div>
      <div className="agent-inventory-operations-summary">
        异常材料 {view.summary.totalAnomalyMaterials} 种：库存为负 {view.summary.negativeInventoryCount} ·
        在租为负 {view.summary.negativeRentedCount} · 租入未退为负 {view.summary.negativeLeasedCount} ·
        长期无流水 {view.summary.stagnantCount}
      </div>
      {view.items.map((item, index) => (
        <div className="agent-result-row" key={`${item.materialName}-${index}`}>
          <span>
            {item.materialName}{item.materialSpecification ? ` ${item.materialSpecification}` : ''} · {item.anomalyName}
          </span>
          <small>当前值 {item.currentValue == null ? '--' : formatCardNumber(item.currentValue)}</small>
        </div>
      ))}
      <div className="agent-operating-scope">
        共 {formatCardNumber(view.totalCount)} 项{view.truncated ? `，展示前 ${formatCardNumber(view.displayedCount)} 项` : ''}。
        口径说明：{view.scopeNote}
      </div>
    </div>
  );
};

const InventoryLedgerCard = ({ card }) => {
  const view = normalizeInventoryLedgerCard(card);
  return (
    <div className="agent-inventory-ledger">
      <div className="agent-result-title">
        库存台账追溯 · {view.materialLabel}
        {view.currentInventoryQuantity == null
          ? ''
          : `（当前库存 ${formatCardNumber(view.currentInventoryQuantity)} ${view.inventoryUnit}）`}
      </div>
      {view.period ? <div className="agent-inventory-ledger-period">期间：{view.period}</div> : null}
      {view.items.map((item, index) => (
        <div className="agent-result-row" key={`${item.eventTime}-${index}`}>
          <span>{item.eventTime} · {item.behaviorName}{item.projectName ? ` · ${item.projectName}` : ''}</span>
          <small>
            {item.inventoryDelta == null ? '--' : `${item.inventoryDelta > 0 ? '+' : ''}${formatCardNumber(item.inventoryDelta)}`}
            {item.inventoryUnit}，余 {item.afterInventory == null ? '--' : formatCardNumber(item.afterInventory)}
          </small>
        </div>
      ))}
      <div className="agent-operating-scope">
        共 {formatCardNumber(view.totalCount)} 条流水{view.truncated ? `，展示最近 ${formatCardNumber(view.displayedCount)} 条` : ''}。
        口径说明：{view.scopeNote}
      </div>
    </div>
  );
};

const CapabilityBoundaryCard = ({ card }) => {
  const view = normalizeCapabilityBoundaryCard(card);
  return (
    <div className="agent-capability-boundary">
      <div className="agent-result-title">能力边界说明 · {view.statusLabel}</div>
      {view.missingTools.length ? (
        <div className="agent-capability-boundary-missing">
          缺少执行证据的能力：{view.missingTools.map((tool) => tool.name).join('、')}
        </div>
      ) : null}
      {view.nextStep ? (
        <div className="agent-capability-boundary-next">下一步：{view.nextStep}</div>
      ) : null}
      <div className="agent-operating-scope">口径说明：{view.scopeNote}</div>
    </div>
  );
};

const ResultCards = ({ cards }) => {
  if (!Array.isArray(cards) || !cards.length) return null;
  return (
    <div className="agent-result-cards">
      {uniqueResultCards(cards).slice(0, 8).filter(hasVisibleResult).map((card, index) => (
        <div className="agent-result-card" key={`${card?.type || 'result'}-${index}`}>
          {['business-records', 'business-query-result'].includes(card?.type) ? (
            <BusinessRecordsCard card={card} />
          ) : card?.type === 'project-operating-report' ? (
            <ProjectOperatingReportCard card={card} />
          ) : card?.type === 'receivable-collection-list' ? (
            <ReceivableCollectionCard card={card} />
          ) : card?.type === 'material-transaction-ranking' ? (
            <MaterialTransactionRankingCard card={card} />
          ) : card?.type === 'finance-enterprise-kpi' ? (
            <FinanceEnterpriseKpiCard card={card} />
          ) : card?.type === 'supplier-payable-summary' ? (
            <SupplierPayableSummaryCard card={card} />
          ) : card?.type === 'owner-action-center' ? (
            <OwnerActionCenterCard card={card} />
          ) : card?.type === 'capability-boundary' ? (
            <CapabilityBoundaryCard card={card} />
          ) : card?.type === 'inventory-operations-summary' ? (
            <InventoryOperationsCard card={card} />
          ) : card?.type === 'inventory-summary' ? (
            <InventorySummaryCard card={card} />
          ) : card?.type === 'inventory-ledger-trace' ? (
            <InventoryLedgerCard card={card} />
          ) : card?.type === 'document-audit-list' ? (
            <DocumentAuditCard card={card} />
          ) : card?.type === 'contract-commercial-analytics' ? (
            <ContractCommercialCard card={card} />
          ) : card?.type === 'material-lifecycle-analytics' ? (
            <MaterialLifecycleCard card={card} />
          ) : (
            <>
              <div className="agent-result-title">
                {card?.type === 'reconciliation-due-list' ? '待对账项目'
                  : card?.type === 'project-list' ? '项目清单'
                  : card?.type === 'document-list' ? '单据结果'
                    : card?.type === 'estimate-summary' ? '材料预估' : '业务结果'}
              </div>
              {Array.isArray(card?.items) && card.items.length ? (
                (card.type === 'project-list' ? card.items : card.items.slice(0, 6)).map((item, itemIndex) => (
                  <div className="agent-result-row" key={item?.projectId || item?.documentId || item?.materialId || itemIndex}>
                    <span>{item?.projectName || item?.documentName || item?.materialName || item?.name || `结果 ${itemIndex + 1}`}</span>
                    <small>{card.type === 'project-list' ? [item?.managerName && `负责人：${item.managerName}`, item?.projectStatusFlag === '0' ? '进行中' : item?.projectStatusFlag === '1' ? '已完成' : ''].filter(Boolean).join(' · ') : (item?.lastSettledEnd || item?.dueEnd || item?.createDate || item?.managerName || item?.estimatedQty || item?.quantity || '')}</small>
                  </div>
                ))
              ) : (
                <div className="agent-result-summary">
                  {presentAssistantAnswer(card?.aiSummary || card?.scopeSummary || card?.summary || card?.suggestion)}
                </div>
              )}
            </>
          )}
        </div>
      ))}
    </div>
  );
};

const AiRentOutPreviewCard = ({
  preview,
  loading,
  confirming,
  onConfirm,
  onCancel,
}) => {
  if (loading) {
    return (
      <div className="agent-ai-preview-card loading">
        <Spin />
        <div>
          <strong>正在识别单据内容</strong>
          <span>小云会先抽取项目、日期和材料明细，再和当前项目材料库做匹配。</span>
        </div>
      </div>
    );
  }
  if (!preview) return null;

  const head = preview.head || {};
  const matchedMaterials = Array.isArray(preview.matchedMaterials)
    ? preview.matchedMaterials
    : Array.isArray(preview.materials) ? preview.materials : [];
  const unmatchedRows = Array.isArray(preview.unmatchedRows) ? preview.unmatchedRows : [];
  const warnings = Array.isArray(preview.warnings) ? preview.warnings : [];

  return (
    <div className="agent-ai-preview-card">
      <div className="agent-ai-preview-head">
        <div>
          <strong>AI 拍照开租出单预览</strong>
          <span>{preview.projectName || '当前项目'} · 请复核后再生成未审核单据</span>
        </div>
        <Tag color={preview.canConfirm ? 'green' : 'orange'}>
          {preview.canConfirm ? '可生成' : '需调整'}
        </Tag>
      </div>

      <div className="agent-ai-preview-fields">
        <span><small>租出日期</small><strong>{formatPreviewValue(head.rentDate)}</strong></span>
        <span><small>客户</small><strong>{formatPreviewValue(head.customerName || head.rentCustomerName)}</strong></span>
        <span><small>发货地点</small><strong>{formatPreviewValue(head.deliveryLocation)}</strong></span>
        <span><small>到货地点</small><strong>{formatPreviewValue(head.toLocation)}</strong></span>
      </div>

      <div className="agent-ai-preview-section">
        <div className="agent-ai-preview-title">已匹配材料</div>
        {matchedMaterials.length ? (
          <div className="agent-ai-preview-table">
            <div className="agent-ai-preview-row header">
              <span>材料</span><span>规格</span><span>数量</span><span>匹配度</span>
            </div>
            {matchedMaterials.map((item, index) => (
              <div className="agent-ai-preview-row" key={`${item.materialId || item.materialName}-${index}`}>
                <span>
                  <strong>{item.materialName || '未命名材料'}</strong>
                  {item.rawName && item.rawName !== item.materialName ? <small>识别：{item.rawName}</small> : null}
                </span>
                <span>{formatPreviewValue(item.materialSpecification)}</span>
                <span>{formatPreviewValue(item.countingQuantity || item.rawQuantity)} {item.countingUnit || item.rawUnit || ''}</span>
                <span>
                  <Tag color={(item.confidence || 0) >= 0.8 ? 'green' : 'blue'}>
                    {Math.round((item.confidence || 0) * 100)}%
                  </Tag>
                </span>
              </div>
            ))}
          </div>
        ) : <div className="agent-ai-preview-empty">还没有匹配到可保存的材料明细。</div>}
      </div>

      {unmatchedRows.length ? (
        <div className="agent-ai-preview-section warning">
          <div className="agent-ai-preview-title">待人工处理</div>
          {unmatchedRows.slice(0, 6).map((item, index) => (
            <div className="agent-ai-preview-warning-row" key={`${item.rawName || 'unmatched'}-${index}`}>
              <span>{item.rawName || '未识别材料'}{item.rawSpec ? ` · ${item.rawSpec}` : ''}</span>
              <small>{item.reason || '未能匹配项目材料库'}</small>
            </div>
          ))}
        </div>
      ) : null}

      {warnings.length ? (
        <div className="agent-ai-preview-notes">
          {warnings.slice(0, 4).map((item, index) => <span key={`${item}-${index}`}>{item}</span>)}
        </div>
      ) : null}

      <div className="agent-ai-preview-actions">
        <Button onClick={onCancel}>放弃</Button>
        <Button
          type="primary"
          icon={<CheckCircleOutlined />}
          loading={confirming}
          disabled={!preview.canConfirm}
          onClick={onConfirm}
        >
          生成未审核租出单
        </Button>
      </div>
    </div>
  );
};

const AgentBeta = () => {
  const dispatch = useDispatch();
  const agent = useSelector((state) => state.agent);
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState([]);
  const [attachmentReady, setAttachmentReady] = useState(true);
  const [renameTarget, setRenameTarget] = useState(null);
  const [renameValue, setRenameValue] = useState('');
  const [activeView, setActiveView] = useState('chat');
  const [activeRole, setActiveRole] = useState('OWNER');
  const [aiRentOutPreview, setAiRentOutPreview] = useState(null);
  const [aiPreviewLoading, setAiPreviewLoading] = useState(false);
  const [aiConfirming, setAiConfirming] = useState(false);
  const messageEndRef = useRef(null);

  const {
    capability,
    projects,
    selectedProjectIds,
    workspace,
    threads,
    selectedThreadId,
    messages,
    artifacts,
    currentRun,
    currentTask,
    currentInteraction,
    runEvents,
    streamingContent,
    statusMessage,
    bootstrapping,
    loadingMessages,
    sending,
    cancelling,
    terminalReconcilePending,
    error,
  } = agent;

  useEffect(() => { setAttachments([]); }, [selectedThreadId]);

  useEffect(() => {
    const initialize = async () => {
      let currentCapability = capability;
      if (!currentCapability.checked) currentCapability = await dispatch(fetchAgentCapabilities());
      if (currentCapability?.enabled && !workspace && !bootstrapping) dispatch(loadAgentProjects());
    };
    initialize();
  }, [bootstrapping, capability, dispatch, workspace]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, streamingContent, statusMessage, currentInteraction, aiRentOutPreview, aiPreviewLoading]);

  const isAllProjects = selectedProjectIds?.includes(ALL_AGENT_PROJECTS);
  const selectedProjects = useMemo(
    () => isAllProjects ? projects : projects.filter((item) => selectedProjectIds?.includes(item.projectId)),
    [isAllProjects, projects, selectedProjectIds],
  );
  const selectedThread = useMemo(
    () => threads.find((item) => item.threadId === selectedThreadId),
    [threads, selectedThreadId],
  );
  const previousRunTerminal = ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(currentRun?.status);
  const status = sending
    ? (previousRunTerminal ? 'QUEUED' : currentRun?.status || 'QUEUED')
    : (currentTask?.status || currentRun?.status);
  const statusMeta = currentTask?.outcome?.completionStatus === 'PARTIAL' || currentTask?.completionStatus === 'PARTIAL'
    ? ['部分完成', 'orange'] : STATUS_META[status] || [status || '待命', 'default'];
  const transientRunView = buildTransientRunView({
    sending,
    terminalReconcilePending,
    streamingContent,
    statusMessage,
  });
  const busy = sending || terminalReconcilePending || cancelling;
  const activelyExecuting = sending && !terminalReconcilePending;
  const scopeLabel = isAllProjects
    ? `全部项目（${projects.length}）`
    : selectedProjects.length === 1
      ? selectedProjects[0].projectName
      : `已选择 ${selectedProjects.length} 个项目`;
  const capabilityManifest = normalizeCapabilityManifest(capability);
  const manifestCanCollectReceivables = capabilityManifest.capabilities.some((item) => {
    const value = `${item.code} ${item.name}`.toLowerCase();
    return value.includes('receivable') && (value.includes('collection') || value.includes('催缴'));
  });
  const canSuggestCollection = capability.v2Enabled
    ? manifestCanCollectReceivables
    : capability.tenantFinanceEnabled;
  const fallbackQuickPrompts = isAllProjects
    ? ALL_PROJECTS_QUICK_PROMPTS.filter((prompt) => canSuggestCollection || !prompt.includes('催缴'))
    : selectedProjects.length === 1 ? SINGLE_PROJECT_QUICK_PROMPTS : MULTI_PROJECT_QUICK_PROMPTS;
  const quickPrompts = buildRoleStarters({ capability, role: activeRole, fallback: fallbackQuickPrompts });
  const taskView = useMemo(() => buildAgentTaskView({
    runEvents,
    currentTask,
    currentInteraction,
    messages,
    artifacts,
    capability,
  }), [artifacts, capability, currentInteraction, currentTask, messages, runEvents]);
  const selectedSingleProject = selectedProjects.length === 1 ? selectedProjects[0] : null;
  const canUploadRentOutPhoto = !!selectedThreadId
    && !!selectedSingleProject
    && selectedSingleProject.projectBusinessType !== 'rent_in'
    && !busy
    && !aiPreviewLoading
    && !aiConfirming;
  const uploadRentOutPhotoTip = !selectedThreadId
    ? '请先创建或选择一个会话'
    : !selectedSingleProject
      ? '拍照开单需要先选择单个租出项目'
      : selectedSingleProject.projectBusinessType === 'rent_in'
        ? '当前仅支持租出项目拍照开单'
        : '上传图片或 PDF，识别后生成未审核租出单';

  const handleSend = () => {
    const content = input.trim();
    if (!content || busy || !selectedThreadId) return;
    if (!attachmentReady) {
      message.warning('请等待附件解析完成，或移除无法解析的附件后发送。'); return;
    }
    setInput('');
    if (taskView.interaction && !attachments.length) {
      dispatch(answerAgentWorkspaceInteraction({ value: content, label: content }));
      return;
    }
    dispatch(sendAgentWorkspaceMessage(content, { role: activeRole }, attachments));
    setAttachments([]);
  };

  const handleInteractionAnswer = (option) => {
    if (!option?.label || busy) return;
    dispatch(answerAgentWorkspaceInteraction(option));
  };

  const openRename = (thread, event) => {
    event.stopPropagation();
    setRenameTarget(thread);
    setRenameValue(thread.title || '');
  };

  const confirmRename = async () => {
    if (!renameValue.trim()) return;
    const success = await dispatch(renameAgentThread(renameTarget.threadId, renameValue.trim()));
    if (success) setRenameTarget(null);
  };

  const changeProjectScope = (values) => {
    if (busy) return;
    setAiRentOutPreview(null);
    dispatch(resetRunView());
    const next = Array.isArray(values) ? values : [];
    if (!next.length || next[next.length - 1] === ALL_AGENT_PROJECTS) {
      dispatch(setSelectedProjectIds([ALL_AGENT_PROJECTS]));
      dispatch(setScopeOverridePending({ threadId: selectedThreadId, scopeKey: 'ALL:' }));
      dispatch(fetchAgentCapabilities([ALL_AGENT_PROJECTS]));
      return;
    }
    const explicit = next.filter((value) => value !== ALL_AGENT_PROJECTS);
    dispatch(setSelectedProjectIds(explicit));
    dispatch(setScopeOverridePending({ threadId: selectedThreadId, scopeKey: scopeOverrideKey(explicit) }));
    dispatch(fetchAgentCapabilities(explicit));
  };

  const handlePreviewRentOutDocument = async (file) => {
    if (!canUploadRentOutPhoto) {
      message.warning(uploadRentOutPhotoTip);
      return false;
    }
    const fileName = file?.name || '';
    const fileType = file?.type || '';
    const isAllowedType = fileType.startsWith('image/')
      || fileType === 'application/pdf'
      || /\.(png|jpe?g|webp|bmp|pdf)$/i.test(fileName);
    if (!isAllowedType) {
      message.warning('仅支持上传图片或 PDF 单据');
      return false;
    }
    if (file?.size > 10 * 1024 * 1024) {
      message.warning('文件不能超过 10MB');
      return false;
    }

    setAiRentOutPreview(null);
    setAiPreviewLoading(true);
    try {
      const preview = dataOf(await previewRentOutDocumentFromImage({
        file,
        projectId: selectedSingleProject.projectId,
        userInstruction: input.trim(),
      }), '单据识别失败');
      setAiRentOutPreview(preview);
      if (preview?.canConfirm) {
        message.success('识别完成，请复核后生成未审核租出单');
      } else {
        message.warning('识别完成，但仍有材料需要人工处理');
      }
    } catch (previewError) {
      message.error(previewError?.message || '单据识别失败');
    } finally {
      setAiPreviewLoading(false);
    }
    return false;
  };

  const handleConfirmRentOutDocument = async () => {
    if (!aiRentOutPreview?.draftPayload) return;
    setAiConfirming(true);
    try {
      const result = dataOf(await confirmRentOutDocumentFromImage(aiRentOutPreview.draftPayload), '租出单生成失败');
      message.success(`已生成未审核租出单：${result?.documentName || result?.documentId || '请到租出单列表查看'}`);
      setAiRentOutPreview(null);
    } catch (confirmError) {
      message.error(confirmError?.message || '租出单生成失败');
    } finally {
      setAiConfirming(false);
    }
  };

  if (capability.checked && !capability.enabled) {
    return (
      <div className="agent-workspace-page">
        <TopBar titleName="小云" titleIcon={<XiaoYunAvatar size={22} />} showRadius radiusType="both" />
        <div className="agent-unavailable">
          <CloseCircleOutlined />
          <h3>小云暂未开通</h3>
          <p>{capability.reason || '该能力当前仅向白名单账号开放，请联系管理员开通。'}</p>
        </div>
      </div>
    );
  }

  if (activeView === 'knowledge' && capability.knowledgeManagementEnabled) {
    return (
      <div className="agent-workspace-page">
        <TopBar titleName="小云" titleIcon={<XiaoYunAvatar size={22} />} showRadius radiusType="both" />
        <div className="agent-workspace-mode-nav">
          <Button icon={<XiaoYunAvatar size={18} />} onClick={() => setActiveView('chat')}>与小云对话</Button>
          <Button type="primary" icon={<DatabaseOutlined />}>AI 知识库</Button>
        </div>
        <AgentKnowledge capability={capability} />
      </div>
    );
  }

  return (
    <div className="agent-workspace-page">
      <TopBar titleName="小云" titleIcon={<XiaoYunAvatar size={22} />} showRadius radiusType="both" />
      <div className="agent-workspace-mode-nav"><AgentLearning projects={projects} onOpenThread={(threadId) => dispatch(selectAgentThread(threadId))} /></div>
      {capability.knowledgeManagementEnabled ? (
        <div className="agent-workspace-mode-nav">
          <Button type="primary" icon={<XiaoYunAvatar size={18} />}>与小云对话</Button>
          <Button icon={<DatabaseOutlined />} onClick={() => setActiveView('knowledge')}>AI 知识库</Button>
        </div>
      ) : null}
      {error ? <Alert className="agent-global-alert" type="error" showIcon closable message={error} /> : null}

      <Spin spinning={bootstrapping} wrapperClassName="agent-workspace-spin">
        <div className="agent-workspace-grid">
          <aside className="agent-project-panel agent-panel">
            <div className="agent-panel-heading">
              <span><FolderOpenOutlined /> 项目范围</span>
              <Tag color="blue">只读</Tag>
            </div>
            <Select
              mode="multiple"
              className="agent-project-select"
              showSearch
              value={selectedProjectIds}
              placeholder="选择项目范围"
              optionFilterProp="label"
              maxTagCount={1}
              disabled={busy || !!taskView.interaction}
              onChange={changeProjectScope}
              options={[
                { value: ALL_AGENT_PROJECTS, label: `全部项目 · ${projects.length}` },
                ...projects.map((project) => ({
                  value: project.projectId,
                  label: `${project.projectName} · ${project.projectBusinessType === 'rent_in' ? '租入' : '租出'}`,
                })),
              ]}
            />
            <div className="agent-project-meta">
              <strong>{scopeLabel}</strong>
              <span>{isAllProjects ? '包含当前企业全部租入与租出项目' : selectedProjects.map((item) => item.projectName).join('、')}</span>
              <span>{isAllProjects ? '跨项目经营、库存及当前可用财务数据 · 只读 · 全程留痕' : '项目边界由系统校验 · 只读 · 全程留痕'}</span>
            </div>

            <div className="agent-thread-header">
              <span>会话</span>
              <Tooltip title="新建会话">
                <Button size="small" type="text" icon={<PlusOutlined />} disabled={!workspace} onClick={() => dispatch(addAgentThread())} />
              </Tooltip>
            </div>
            <div className="agent-thread-list">
              {threads.length ? threads.map((thread) => (
                <button
                  type="button"
                  key={thread.threadId}
                  className={`agent-thread-item ${thread.threadId === selectedThreadId ? 'active' : ''}`}
                  onClick={() => dispatch(selectAgentThread(thread.threadId))}
                >
                  <span className="agent-thread-copy">
                    <strong>{thread.title || '新对话'}</strong>
                    <small>{formatTime(thread.lastMessageAt || thread.createdAt)}</small>
                  </span>
                  <span className="agent-thread-actions">
                    <EditOutlined onClick={(event) => openRename(thread, event)} />
                    <Popconfirm
                      title="归档这个会话？"
                      onConfirm={(event) => { event?.stopPropagation?.(); dispatch(removeAgentThread(thread.threadId)); }}
                      onCancel={(event) => event?.stopPropagation?.()}
                    >
                      <DeleteOutlined onClick={(event) => event.stopPropagation()} />
                    </Popconfirm>
                  </span>
                </button>
              )) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" />}
            </div>
          </aside>

          <main className="agent-chat-panel agent-panel">
            <div className="agent-chat-header">
              <div>
                <strong>{selectedThread?.title || '选择项目范围后开始工作'}</strong>
                <span>当前范围：{scopeLabel}</span>
              </div>
              <Tag color={statusMeta[1]}>{statusMeta[0]}</Tag>
            </div>

            <div className="agent-message-list">
              {loadingMessages ? <Spin /> : null}
              {!loadingMessages && !messages.length && !aiPreviewLoading && !aiRentOutPreview ? (
                <div className="agent-empty-chat">
                  <XiaoYunAvatar size={84} className="agent-empty-avatar" label="小云" />
                  <h3>{capabilityManifest.assistantShape}</h3>
                  <p>{isAllProjects ? '可以查询跨项目经营、库存及当前范围内可用的财务数据。' : selectedProjects.length === 1 ? '可以读取当前项目的经营、材料与业务单据。' : '可以查询和汇总当前选中的项目清单。'}</p>
                  {SHOW_AGENT_ROLE_SWITCH ? (
                    <div className="agent-role-switch" aria-label="工作角色">
                      {AGENT_ROLES.map((role) => (
                        <Button
                          key={role.value}
                          type={activeRole === role.value ? 'primary' : 'default'}
                          size="small"
                          onClick={() => setActiveRole(role.value)}
                        >{role.label}</Button>
                      ))}
                    </div>
                  ) : null}
                  <div className="agent-quick-prompts">
                    {quickPrompts.map((prompt) => <Button key={prompt} onClick={() => setInput(prompt)}>{prompt}</Button>)}
                  </div>
                </div>
              ) : null}
              {messages.map((item, index) => (
                <div className={`agent-message ${item.role}`} key={item.messageId || `${item.role}-${index}`}>
                  <div className="agent-message-avatar">{item.role === 'user' ? <UserOutlined /> : <XiaoYunAvatar size={30} />}</div>
                  <div className="agent-message-body">
                    <div className="agent-message-meta">
                      <strong>{item.role === 'user' ? '你' : '小云'}</strong>
                      <span>
                        {item.deliveryStatus === 'FAILED' ? <b className="agent-message-delivery-error">未送达 · </b> : null}
                        {formatTime(item.createdAt)}
                      </span>
                    </div>
                    <MessageContent content={item.role === 'assistant' ? presentAssistantAnswer(inventoryMessageContent(item.content, item.metadata?.cards)) : item.content} />
                    <ResultCards cards={item.metadata?.cards} />
                    <LearningCards entries={item.metadata?.learningResults} />
                    <ContractReviewCard review={item.metadata?.documentReview} />
                    {item.metadata?.completionStatus === 'PARTIAL' ? <Tag color="orange">部分完成，未完成项见说明</Tag> : null}
                    {item.metadata?.analysisClaims?.length ? <details><summary>查看分析依据</summary>{item.metadata.analysisClaims.map((claim, i) => <p key={i}>{claim.text} · 依据：{claim.evidenceIds.join('、')}</p>)}</details> : null}
                  </div>
                </div>
              ))}
              {transientRunView.visible ? (
                <div className={`agent-message assistant streaming${transientRunView.working ? '' : ' reconcile-pending'}`}>
                  <div className="agent-message-avatar"><XiaoYunAvatar size={30} /></div>
                  <div className="agent-message-body">
                    <div className="agent-message-meta"><strong>小云</strong><span>{transientRunView.label}</span></div>
                    {transientRunView.content ? (
                      <MessageContent content={presentAssistantAnswer(transientRunView.content, transientRunView.working)} isStreaming={transientRunView.working} />
                    ) : (
                      <div className="agent-run-status-copy">已提交任务，正在等待处理进度或回答。</div>
                    )}
                  </div>
                </div>
              ) : null}
              <BusinessArtifactDownloads artifacts={artifacts} />
              {taskView.interaction ? (
                <div className="agent-message assistant interaction">
                  <div className="agent-message-avatar"><XiaoYunAvatar size={30} /></div>
                  <div className="agent-message-body">
                    <AgentInteractionCard
                      interaction={taskView.interaction}
                      disabled={busy}
                      onAnswer={handleInteractionAnswer}
                    />
                  </div>
                </div>
              ) : null}
              {aiPreviewLoading || aiRentOutPreview ? (
                <div className="agent-message assistant ai-intake">
                  <div className="agent-message-avatar"><XiaoYunAvatar size={30} /></div>
                  <div className="agent-message-body">
                    <div className="agent-message-meta">
                      <strong>小云</strong>
                      <span>{aiPreviewLoading ? '正在识别单据' : '识别预览'}</span>
                    </div>
                    <AiRentOutPreviewCard
                      preview={aiRentOutPreview}
                      loading={aiPreviewLoading}
                      confirming={aiConfirming}
                      onConfirm={handleConfirmRentOutDocument}
                      onCancel={() => setAiRentOutPreview(null)}
                    />
                  </div>
                </div>
              ) : null}
              <div ref={messageEndRef} />
            </div>

            <div className="agent-composer">
              {capability.v2Enabled && capability.conversationFeatures?.documentReview ? <AgentAttachmentPicker threadId={selectedThreadId} value={attachments} onChange={setAttachments} onReadyChange={setAttachmentReady} disabled={busy || !selectedThreadId} /> : null}
              <TextArea
                value={input}
                maxLength={INPUT_LIMIT}
                autoSize={{ minRows: 2, maxRows: 6 }}
                disabled={!selectedThreadId}
                placeholder={taskView.interaction
                  ? '请补充小云需要的信息；Shift + Enter 换行'
                  : '描述要在当前项目范围内完成的任务；Shift + Enter 换行'}
                onChange={(event) => setInput(event.target.value)}
                onPressEnter={(event) => {
                  if (!event.shiftKey) { event.preventDefault(); handleSend(); }
                }}
              />
              <div className="agent-composer-footer">
                <span>{input.length}/{INPUT_LIMIT} · 当前范围：{scopeLabel}</span>
                <div className="agent-composer-actions">
                  <Tooltip title={uploadRentOutPhotoTip}>
                    <Upload
                      accept="image/*,.pdf"
                      disabled={!canUploadRentOutPhoto}
                      showUploadList={false}
                      beforeUpload={(file) => {
                        handlePreviewRentOutDocument(file);
                        return false;
                      }}
                    >
                      <Button icon={<UploadOutlined />} loading={aiPreviewLoading} disabled={!canUploadRentOutPhoto}>
                        拍照开单
                      </Button>
                    </Upload>
                  </Tooltip>
                  {!activelyExecuting && taskView.interaction && taskView.task?.taskId ? (
                    <Button
                      danger
                      loading={cancelling}
                      disabled={terminalReconcilePending}
                      onClick={() => dispatch(stopAgentRun())}
                    >取消当前任务</Button>
                  ) : null}
                  {currentTask?.runtimeVersion === 'V2' && ['BLOCKED', 'CANCELLED'].includes(currentTask?.status) ? (
                    <Button disabled={busy} onClick={() => dispatch(retryAgentWorkspaceTask())}>重新尝试</Button>
                  ) : null}
                  {activelyExecuting ? (
                    <Button
                      danger
                      icon={<StopOutlined />}
                      loading={cancelling}
                      disabled={cancelling || status === 'FINALIZING'}
                      onClick={() => dispatch(stopAgentRun())}
                    >{status === 'FINALIZING' ? '收尾中' : '停止'}</Button>
                  ) : (
                    <Button
                      className="agent-send-button"
                      type="primary"
                      icon={<ArrowUpOutlined />}
                      disabled={busy || !input.trim() || !selectedThreadId}
                      onClick={handleSend}
                    >
                      {taskView.interaction ? '提交补充' : '发送'}
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </main>

        </div>
      </Spin>

      <Modal
        title="重命名会话"
        open={!!renameTarget}
        okText="保存"
        cancelText="取消"
        onOk={confirmRename}
        onCancel={() => setRenameTarget(null)}
      >
        <Input value={renameValue} maxLength={60} onChange={(event) => setRenameValue(event.target.value)} onPressEnter={confirmRename} />
      </Modal>
    </div>
  );
};

export default AgentBeta;
