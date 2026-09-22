import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Drawer, Empty, Input, Modal, Pagination, Select, Space, Spin, Tag, Typography, message } from 'antd';
import { getLearningCapabilities, listLearningEntries, getLearningEntry, changeLearningEntry } from '@/api/agentLearning';
import { learningKinds, learningScopes, learningStatuses, learningStatus, canVerifyLearning, canRevokeLearning } from './learningView';

export function LearningCards({ entries }) {
  if (!Array.isArray(entries)) return null;
  return entries.filter((entry) => entry?.id).map((entry) => (
    <Card key={entry.id} size="small" style={{ marginTop: 12 }}>
      <Space wrap><strong>学习记录</strong><Tag color={learningStatus(entry.status)[1]}>{learningStatus(entry.status)[0]}</Tag>
        <span>{learningScopes[entry.scopeType] || '范围待确认'}</span></Space>
      <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{entry.content}</p>
      <Typography.Text type="secondary">{entry.reason}</Typography.Text>
      <div><Typography.Text type="secondary">以上为记录时的处理结果。</Typography.Text></div>
      <div><Button type="link" onClick={() => window.dispatchEvent(new CustomEvent('agent:learning-open', { detail: { id: entry.id } }))}>查看最新状态和依据</Button></div>
    </Card>
  ));
}

export default function AgentLearning({ projects = [], onOpenThread }) {
  const [open, setOpen] = useState(false);
  const [capability, setCapability] = useState(null);
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [filters, setFilters] = useState({ pageNum: 1, pageSize: 20 });
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [edit, setEdit] = useState(null);
  const [share, setShare] = useState(null);
  const requestIds = useRef(new Map());
  const sequence = useRef(0);
  const detailSequence = useRef(0);
  const refresh = useCallback(async () => {
    const ticket = ++sequence.current;
    setLoading(true); setError('');
    try {
      const caps = await getLearningCapabilities();
      if (ticket !== sequence.current) return;
      setCapability(caps);
      const result = caps.canReadOwn ? await listLearningEntries(filters) : { items: [], total: 0 };
      if (ticket === sequence.current) { setRows(result.items || []); setTotal(result.total || 0); }
    } catch (e) { if (ticket === sequence.current) setError(e.message); }
    finally { if (ticket === sequence.current) setLoading(false); }
  }, [filters]);
  const showDetail = useCallback(async (id) => {
    const ticket = ++detailSequence.current;
    setDetail(null); setBusy(true);
    try { const result = await getLearningEntry(id); if (ticket === detailSequence.current) setDetail(result); }
    catch (e) { if (ticket === detailSequence.current) setError(e.message); }
    finally { if (ticket === detailSequence.current) setBusy(false); }
  }, []);
  useEffect(() => { if (open) refresh(); return () => { sequence.current += 1; }; }, [open, refresh]);
  useEffect(() => {
    const listener = (event) => { setOpen(true); if (event.detail?.id) showDetail(event.detail.id); };
    window.addEventListener('agent:learning-open', listener);
    return () => { window.removeEventListener('agent:learning-open', listener); detailSequence.current += 1; };
  }, [showDetail]);
  const mutate = async (entry, action, extra = {}) => {
    const identity = JSON.stringify([entry.id, entry.version, action, extra]);
    if (!requestIds.current.has(identity)) requestIds.current.set(identity, window.crypto?.randomUUID?.() || `learn-${Date.now()}-${Math.random().toString(36).slice(2)}`);
    setBusy(true); setError('');
    try {
      const result = await changeLearningEntry(entry.id, action, {
        ...extra, expectedVersion: entry.version, clientRequestId: requestIds.current.get(identity),
      });
      await showDetail(result.id);
      setEdit(null); setShare(null); await refresh();
      message.success(action === 'revoke' ? '已撤销，后续回答不再使用' : '处理结果已更新');
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const filter = (name, value) => { setDetail(null); setFilters((previous) => ({ ...previous, [name]: value || undefined, pageNum: 1 })); };
  return <>
    <Button onClick={() => setOpen(true)}>学习记录</Button>
    <Drawer title="小云的学习记录" open={open} width="min(680px, 100vw)" onClose={() => { setOpen(false); detailSequence.current += 1; setDetail(null); }}>
      {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={refresh}>刷新</Button>} style={{ marginBottom: 12 }} />}
      {capability && !capability.canReadOwn && <Alert type="info" message="学习功能尚未启用" description="当前不会保存新的长期记忆。" />}
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search placeholder="搜索学习内容" allowClear onSearch={(value) => filter('keyword', value)} />
        <Select aria-label="学习类型" placeholder="全部类型" allowClear style={{ width: 140 }} onChange={(value) => filter('kind', value)} options={Object.entries(learningKinds).map(([value, label]) => ({ value, label }))} />
        <Select aria-label="学习状态" placeholder="全部状态" allowClear style={{ width: 140 }} onChange={(value) => filter('status', value)} options={Object.entries(learningStatuses).map(([value, [label]]) => ({ value, label }))} />
        <Select aria-label="学习范围" placeholder="全部范围" allowClear style={{ width: 140 }} onChange={(value) => filter('scopeType', value)} options={Object.entries(learningScopes).map(([value, label]) => ({ value, label }))} />
      </Space>
      <Spin spinning={loading || busy}>
        {detail ? <>
          <Button type="link" onClick={() => setDetail(null)}>返回列表</Button>
          <Card title={learningKinds[detail.kind] || '学习记录'} extra={<Tag color={learningStatus(detail.status)[1]}>{learningStatus(detail.status)[0]}</Tag>}>
            <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detail.content}</p>
            <p>{detail.reason}</p><p>适用范围：{learningScopes[detail.scopeType] || '范围待确认'}</p>
            {detail.projectId && <p>项目：{projects.find((p) => p.projectId === detail.projectId)?.projectName || '授权项目'}</p>}
            <Space wrap>
              {detail.canEdit && <Button disabled={busy} onClick={() => setEdit({ entry: detail, content: detail.content })}>纠正内容</Button>}
              {canVerifyLearning(detail) && <Button disabled={busy} onClick={() => mutate(detail, 'verify')}>重新核验</Button>}
              {canRevokeLearning(detail) && <Button disabled={busy} onClick={() => mutate(detail, 'revoke')}>撤销记忆</Button>}
              {detail.canPublish && <Button disabled={busy} onClick={() => setShare({ entry: detail, scopeType: 'TENANT' })}>发布到共享范围</Button>}
            </Space>
            <details style={{ marginTop: 16 }}><summary>依据与修改历史</summary>
              {detail.originThreadId && onOpenThread && <Button type="link" onClick={() => { onOpenThread(detail.originThreadId); setOpen(false); }}>查看原始对话</Button>}
              <p>{detail.evidence ? detail.evidence.title : '尚无可展示的核验资料；个人表达偏好来自本人教导。'}</p>
              {detail.evidence?.content && <blockquote style={{ whiteSpace: 'pre-wrap' }}>{detail.evidence.content}</blockquote>}
              {(detail.events || []).map((event, index) => {
                let snapshot = {}; try { snapshot = JSON.parse(event.snapshot || '{}'); } catch (_) { /* Older audit format. */ }
                return <p key={`${event.version}-${index}`}>版本 {event.version} · {learningStatus(snapshot.status)[0]}<br />{snapshot.reason}</p>;
              })}
            </details>
          </Card>
        </> : <>
          {!loading && rows.length === 0 && <Empty description="暂无学习记录。你可以在对话中纠正小云或告诉它需要记住什么。" />}
          {rows.map((entry) => <Card key={entry.id} size="small" style={{ marginBottom: 12 }}>
            <Tag color={learningStatus(entry.status)[1]}>{learningStatus(entry.status)[0]}</Tag>{learningScopes[entry.scopeType]}
            <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{entry.content}</p>
            <Button type="link" onClick={() => showDetail(entry.id)}>查看、纠正或撤销</Button>
          </Card>)}
          {total > 20 && <Pagination current={filters.pageNum} pageSize={20} total={total} showSizeChanger={false} onChange={(pageNum) => setFilters((previous) => ({ ...previous, pageNum }))} />}
        </>}
      </Spin>
    </Drawer>
    <Modal title="纠正学习内容" open={Boolean(edit)} confirmLoading={busy} onCancel={() => setEdit(null)} onOk={() => mutate(edit.entry, 'correct', { content: edit.content })} okButtonProps={{ disabled: !edit?.content?.trim() }}>
      <Input.TextArea aria-label="纠正后的内容" rows={5} maxLength={2000} showCount value={edit?.content || ''} onChange={(event) => setEdit((previous) => ({ ...previous, content: event.target.value }))} />
      <p>新内容将重新核验，核验结果会显示在学习记录中。</p>
    </Modal>
    <Modal title="发布学习内容" open={Boolean(share)} confirmLoading={busy} onCancel={() => setShare(null)} onOk={() => mutate(share.entry, 'publish', { scopeType: share.scopeType, projectId: share.projectId })} okButtonProps={{ disabled: share?.scopeType === 'PROJECT' && !share?.projectId }}>
      <p>共享后，适用范围内有权限的成员可使用这条记忆。</p>
      <Select value={share?.scopeType} style={{ width: '100%', marginBottom: 12 }} onChange={(scopeType) => setShare((previous) => ({ ...previous, scopeType, projectId: undefined }))} options={[{ value: 'TENANT', label: '企业共享' }, { value: 'PROJECT', label: '指定项目' }]} />
      {share?.scopeType === 'PROJECT' && <Select placeholder="选择项目" style={{ width: '100%' }} value={share.projectId} onChange={(projectId) => setShare((previous) => ({ ...previous, projectId }))} options={projects.map((p) => ({ value: p.projectId, label: p.projectName }))} />}
    </Modal>
  </>;
}
