import { presentAssistantAnswer } from './answerPresentation';
import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Modal, Space, Spin, Tag, Typography } from 'antd';
import { PaperClipOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { uploadAgentAttachment, getAgentAttachment, deleteAgentAttachment, retryAgentAttachment, getAgentAttachmentContent } from '@/api/agentAttachments';

const statusLabels = { PENDING: '等待解析', RUNNING: '解析中', READY: '可审阅', PARTIAL: '部分可读', FAILED: '解析失败', DELETED: '已删除' };
const transientAttachmentError = (error) => {
  const status = Number(error?.response?.status) || 0;
  const code = error?.returnCode || error?.errorCode;
  return !code && (!status || status === 408 || status === 429 || status >= 500);
};
const idOf = (item) => typeof item === 'string' ? item : item?.attachmentId;

/** value/onChange contain attachment ID arrays; text never enters local/session storage. */
export default function AgentAttachmentPicker({ value = [], onChange, onReadyChange, threadId, disabled = false }) {
  const [records, setRecords] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const input = useRef(null);
  const latest = useRef({ value, threadId });
  latest.current = { value, threadId };
  const ids = value.map(idOf).filter(Boolean);
  const key = ids.join(',');
  useEffect(() => {
    onReadyChange?.(!busy && (!key || key.split(',').every((id) => ['READY', 'PARTIAL'].includes(records[id]?.status))));
  }, [busy, key, records, onReadyChange]);
  useEffect(() => {
    let cancelled = false;
    let timer;
    let failures = 0;
    setError('');
    setRecords({});
    const poll = async () => {
      if (!threadId || !key) return;
      const responses = await Promise.allSettled(key.split(',').map((id) => getAgentAttachment(id)));
      if (cancelled) return;
      const next = {};
      let pending = false;
      let retryableError = false;
      responses.forEach((result, index) => {
        const id = key.split(',')[index];
        if (result.status === 'fulfilled') {
          next[id] = result.value;
          if (['PENDING', 'RUNNING'].includes(result.value.status)) pending = true;
        } else {
          const retryable = transientAttachmentError(result.reason);
          retryableError = retryableError || retryable;
          next[id] = { attachmentId: id, status: retryable ? 'RETRYABLE_ERROR' : 'UNAVAILABLE', error: result.reason?.message || '附件无法访问' };
        }
      });
      setRecords(next);
      failures = retryableError ? failures + 1 : 0;
      if (retryableError && failures <= 3) timer = setTimeout(poll, 1000 * (2 ** (failures - 1)));
      else if (pending && !retryableError) timer = setTimeout(poll, 2500);
    };
    poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [threadId, key, refresh]);
  const upload = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !threadId) return;
    if (ids.length >= 3 || !/\.(pdf|docx)$/i.test(file.name) || file.size > 20 * 1024 * 1024) {
      setError('最多3份附件，仅支持20MB以内的PDF或DOCX。'); return;
    }
    const activeThread = threadId;
    setBusy(true); setError('');
    try {
      const result = await uploadAgentAttachment(threadId, file, `attachment-${Date.now()}-${Math.random().toString(36).slice(2)}`);
      if (latest.current.threadId === activeThread) onChange?.([...latest.current.value.map(idOf).filter(Boolean), result.attachmentId]);
    } catch (e) { if (latest.current.threadId === activeThread) setError(e.message); }
    finally { setBusy(false); }
  };
  const remove = async (id) => {
    setBusy(true); setError('');
    const activeThread = threadId;
    try {
      await deleteAgentAttachment(id);
      if (latest.current.threadId === activeThread) onChange?.(latest.current.value.map(idOf).filter((item) => item !== id));
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const retry = async (record) => {
    setBusy(true); setError('');
    try {
      const result = await retryAgentAttachment(record.attachmentId, record.rowVersion);
      setRecords((old) => ({ ...old, [result.attachmentId]: result }));
      setRefresh((n) => n + 1);
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  return <div style={{ margin: '8px 0' }}>
    <input ref={input} type="file" accept=".pdf,.docx" onChange={upload} style={{ display: 'none' }} aria-label="选择合同附件" />
    <Button icon={<PaperClipOutlined />} disabled={disabled || busy || !threadId || ids.length >= 3} onClick={() => input.current?.click()}>附加合同</Button>
    {busy && <Spin size="small" style={{ marginLeft: 8 }} />}
    {ids.map((id) => {
      const record = records[id];
      return <div key={id} style={{ marginTop: 8 }}><Space wrap>
        <Typography.Text>{record?.filename || '附件'}</Typography.Text>
        <Tag color={record?.status === 'READY' ? 'green' : record?.status === 'PARTIAL' ? 'orange' : 'default'}>{statusLabels[record?.status] || (record?.error ? '不可访问' : '加载中')}</Tag>
        {['RETRYABLE_ERROR', 'UNAVAILABLE'].includes(record?.status) && <Button size="small" disabled={busy || disabled} onClick={() => setRefresh((n) => n + 1)}>刷新状态</Button>}
        {['FAILED', 'PARTIAL'].includes(record?.status) && <Button size="small" icon={<ReloadOutlined />} disabled={busy || disabled} onClick={() => retry(record)}>重新解析</Button>}
        <Button size="small" icon={<DeleteOutlined />} disabled={busy || disabled} onClick={() => remove(id)}>删除附件</Button>
      </Space>
        {record?.error && <Typography.Text type="danger">{record.error}</Typography.Text>}
        {record?.coverage?.gaps?.map((gap, index) => <div key={index}><Typography.Text type="warning">{gap}</Typography.Text></div>)}
      </div>;
    })}
    {error && <Alert type="error" showIcon message={error} style={{ marginTop: 8 }} />}
  </div>;
}

export function ContractReviewCard({ review }) {
  const [source, setSource] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const sourceRequest = useRef(0);
  if (!review?.answer) return null;
  const openSource = async (ref) => {
    const requestId = ++sourceRequest.current;
    setLoading(true); setError(''); setSource({ location: ref.location, blocks: [] });
    try { const result = await getAgentAttachmentContent(ref.attachmentId, ref.parseRevision, ref.blockId); if (requestId === sourceRequest.current) setSource({ ...result, location: ref.location }); }
    catch (e) { if (requestId === sourceRequest.current) setError(e.message); }
    finally { if (requestId === sourceRequest.current) setLoading(false); }
  };
  return <Card size="small" title="合同审阅" style={{ marginTop: 12 }}>
    <Typography.Paragraph>{presentAssistantAnswer(review.answer)}</Typography.Paragraph>
    {review.completionStatus !== 'FULL' && <Tag color="orange">尚未完整审阅</Tag>}
    {(review.findings || []).map((finding) => <Card key={finding.findingId} size="small" style={{ marginTop: 10 }}>
      <Space><Tag color={finding.severity === 'HIGH' ? 'red' : 'orange'}>{({ HIGH: '重点核对', MEDIUM: '建议完善', LOW: '可优化' })[finding.severity]}</Tag><strong>{finding.problem}</strong></Space>
      <blockquote style={{ whiteSpace: 'pre-wrap' }}>{finding.originalExcerpt}</blockquote>
      <Typography.Paragraph>{finding.impact}</Typography.Paragraph>
      <Typography.Paragraph>建议：{finding.suggestion}</Typography.Paragraph>
      {finding.proposedText && <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>建议文本：{finding.proposedText}</Typography.Paragraph>}
      {(finding.sourceRefs || []).map((ref, index) => <Button key={index} type="link" onClick={() => openSource(ref)}>{ref.location || '查看原文'}</Button>)}
    </Card>)}
    {(review.warnings || []).map((warning, index) => <Alert key={index} type="warning" message={presentAssistantAnswer(warning)} style={{ marginTop: 8 }} />)}
    <Modal title={source?.location || '原文引用'} open={!!source} onCancel={() => { sourceRequest.current += 1; setSource(null); setError(''); }} footer={null} destroyOnClose>
      <Spin spinning={loading}>{error ? <Alert type="error" message={error} /> : (source?.blocks || []).map((block) => <pre key={block.id} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit' }}>{block.text}</pre>)}</Spin>
    </Modal>
  </Card>;
}
