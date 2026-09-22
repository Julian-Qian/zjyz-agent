import React from 'react';
import { Button, Empty, Tag, Tooltip } from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloudDownloadOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { normalizeCapabilityManifest } from './agentTaskView';

const statusColor = (status) => {
  const value = String(status || '').toUpperCase();
  if (['COMPLETED', 'SUCCEEDED'].includes(value)) return 'green';
  if (['FAILED', 'BLOCKED'].includes(value)) return 'red';
  if (['RUNNING', 'EXECUTING'].includes(value)) return 'blue';
  if (['WAITING_INPUT', 'WAITING_USER', 'WAITING_APPROVAL', 'GATHERING_INPUT'].includes(value)) return 'orange';
  return 'default';
};

const StatusIcon = ({ status }) => {
  const value = String(status || '').toUpperCase();
  if (['COMPLETED', 'SUCCEEDED'].includes(value)) return <CheckCircleOutlined />;
  if (['RUNNING', 'EXECUTING'].includes(value)) return <LoadingOutlined spin />;
  return <ClockCircleOutlined />;
};

const Section = ({ title, icon, count, children }) => (
  <section className="agent-task-section">
    <div className="agent-task-section-title">
      <span>{icon}{title}</span>
      {count !== undefined ? <small>{count}</small> : null}
    </div>
    {children}
  </section>
);

export const AgentInteractionCard = ({ interaction, disabled, onAnswer }) => {
  if (!interaction) return null;
  return (
    <div className="agent-interaction-card">
      <div className="agent-interaction-heading">
        <InfoCircleOutlined />
        <div>
          <strong>小云需要你确认一项信息</strong>
          <span>确认后继续当前任务</span>
        </div>
      </div>
      <p>{interaction.question}</p>
      {interaction.helpText ? <small>{interaction.helpText}</small> : null}
      {interaction.options.length ? (
        <div className="agent-interaction-options">
          {interaction.options.map((option) => (
            <Tooltip key={option.value} title={option.description || ''}>
              <Button disabled={disabled} onClick={() => onAnswer?.(option)}>{option.label}</Button>
            </Tooltip>
          ))}
        </div>
      ) : (
        <div className="agent-interaction-free-text">请直接在下方输入你的补充信息。</div>
      )}
    </div>
  );
};

const EvidenceRow = ({ item }) => (
  <div className="agent-evidence-row">
    <strong>{item.title}</strong>
    {item.description ? <p>{item.description}</p> : null}
    <span>
      {item.timeRange ? `数据时间：${item.timeRange}` : ''}
      {item.recordCount !== undefined ? `${item.timeRange ? ' · ' : ''}${item.recordCount} 条记录` : ''}
      {item.projectCount ? ` · ${item.projectCount} 个项目` : ''}
    </span>
  </div>
);

const AgentTaskPanel = ({
  capability,
  taskView,
  scopeLabel,
  downloadingArtifactId,
  onDownloadArtifact,
}) => {
  const manifest = normalizeCapabilityManifest(capability);
  const hasTaskDetails = !!taskView.task || taskView.plan.length || taskView.tools.length || taskView.evidence.length;
  return (
    <aside className="agent-task-panel agent-panel">
      <div className="agent-task-panel-heading">
        <div>
          <strong>任务与依据</strong>
          <span>{manifest.assistantShape}</span>
        </div>
        {manifest.version ? <Tag color="blue">V{manifest.version.replace(/^v/i, '')}</Tag> : <Tag>兼容模式</Tag>}
      </div>

      <div className="agent-task-panel-scroll">
        {taskView.task ? (
          <section className="agent-current-task">
            <div className="agent-current-task-status">
              <span>当前任务</span>
              {taskView.task.statusLabel ? (
                <Tag color={statusColor(taskView.task.status)}>{taskView.task.statusLabel}</Tag>
              ) : null}
            </div>
            <strong>{taskView.task.goal || '正在理解当前任务'}</strong>
            <small>{taskView.task.scopeLabel || `当前选择：${scopeLabel}`}</small>
          </section>
        ) : null}

        {taskView.plan.length ? (
          <Section title="执行计划" count={taskView.plan.length} icon={<FileTextOutlined />}>
            <div className="agent-plan-list">
              {taskView.plan.map((step, index) => (
                <div className="agent-plan-step" key={step.id}>
                  <span><StatusIcon status={step.status} /></span>
                  <div>
                    <strong>{index + 1}. {step.title}</strong>
                    {step.statusLabel ? <small>{step.statusLabel}</small> : null}
                  </div>
                </div>
              ))}
            </div>
          </Section>
        ) : null}

        {taskView.tools.length ? (
          <Section title="业务执行" count={taskView.tools.length} icon={<ToolOutlined />}>
            <div className="agent-tool-timeline">
              {taskView.tools.map((tool) => (
                <div className="agent-tool-event" key={tool.id}>
                  <span className={statusColor(tool.status)}><StatusIcon status={tool.status} /></span>
                  <div>
                    <strong>{tool.name}</strong>
                    {tool.summary ? <small>{tool.summary}</small> : null}
                  </div>
                  {tool.statusLabel ? <Tag color={statusColor(tool.status)}>{tool.statusLabel}</Tag> : null}
                </div>
              ))}
            </div>
          </Section>
        ) : null}

        {taskView.evidence.length ? (
          <Section title="事实依据" count={taskView.evidence.length} icon={<SafetyCertificateOutlined />}>
            <div className="agent-evidence-list">
              {taskView.evidence.slice(0, 12).map((item) => <EvidenceRow key={item.id} item={item} />)}
            </div>
          </Section>
        ) : null}

        {taskView.artifacts.length ? (
          <Section title="任务产物" count={taskView.artifacts.length} icon={<FileTextOutlined />}>
            <div className="agent-artifact-list">
              {taskView.artifacts.map((artifact) => (
                <div className="agent-artifact-row" key={artifact.artifactId}>
                  <div>
                    <strong>{artifact.title}</strong>
                    <small>{artifact.mimeType || artifact.artifactType || '结构化产物'}</small>
                  </div>
                  <Button
                    type="text"
                    icon={<CloudDownloadOutlined />}
                    loading={downloadingArtifactId === artifact.artifactId}
                    onClick={() => onDownloadArtifact?.(artifact)}
                  />
                </div>
              ))}
            </div>
          </Section>
        ) : null}

        {!hasTaskDetails && !taskView.artifacts.length ? (
          <div className="agent-task-empty">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="开始任务后，这里会显示真实计划与依据" />
          </div>
        ) : null}

        <Section title="当前可用能力" count={manifest.capabilities.length} icon={<SafetyCertificateOutlined />}>
          {manifest.capabilities.length ? (
            <div className="agent-capability-list">
              {manifest.capabilities.slice(0, 8).map((item) => (
                <Tooltip key={item.code} title={item.description || item.name}>
                  <Tag color={item.readOnly ? 'blue' : 'orange'}>{item.name}</Tag>
                </Tooltip>
              ))}
            </div>
          ) : (
            <p className="agent-capability-fallback">当前由系统按权限与项目范围动态校验能力。</p>
          )}
        </Section>
      </div>
    </aside>
  );
};

export default AgentTaskPanel;
