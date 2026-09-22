import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Upload,
} from 'antd'
import {
  CloudUploadOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useSelector } from 'react-redux'
import {
  archiveAgentKnowledgeSource,
  downloadAgentKnowledgeSource,
  getAgentKnowledgeCapability,
  listAgentKnowledgeChunks,
  listAgentKnowledgeSources,
  previewAgentKnowledgeSearch,
  publishAgentKnowledgeSource,
  reindexAgentKnowledgeSource,
  unpublishAgentKnowledgeSource,
  uploadAgentKnowledgeSource,
} from '@/api/agentKnowledge'
import './AgentKnowledge.scss'

const { Dragger } = Upload
const DOMAIN_OPTIONS = [
  ['GENERAL', '通用'], ['HELP', '操作帮助'], ['PROJECT', '项目'], ['DOCUMENT', '单据'],
  ['SETTLEMENT', '对账'], ['FINANCE', '财务'], ['INVENTORY', '库存'], ['MATERIAL', '材料'],
].map(([value, label]) => ({ value, label }))

const LIFE_META = {
  DRAFT: ['草稿', 'default'],
  PUBLISHED: ['已发布', 'green'],
  UNPUBLISHED: ['已下线', 'orange'],
  ARCHIVED: ['已归档', 'default'],
}
const INDEX_META = {
  PENDING: ['待处理', 'default'],
  PROCESSING: ['处理中', 'processing'],
  READY: ['已就绪', 'green'],
  FAILED: ['失败', 'red'],
}

const formatSize = (bytes) => {
  const value = Number(bytes || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

const AgentKnowledge = ({ capability: agentCapability }) => {
  const membership = useSelector((state) => state.membership.current)
  const readOnly = membership?.enforcementMode === 'ENFORCE' && membership?.status === 'GRACE_PERIOD'
  const [capability, setCapability] = useState({ managementEnabled: false, vectorConfigured: false })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [items, setItems] = useState([])
  const [total, setTotal] = useState(0)
  const [statistics, setStatistics] = useState({ published: 0, draft: 0, processing: 0, failed: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [domain, setDomain] = useState('')
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [selectedFile, setSelectedFile] = useState(null)
  const [uploadRequestId, setUploadRequestId] = useState('')
  const [chunksOpen, setChunksOpen] = useState(false)
  const [chunksLoading, setChunksLoading] = useState(false)
  const [chunkSource, setChunkSource] = useState(null)
  const [chunks, setChunks] = useState([])
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewQuery, setPreviewQuery] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewResult, setPreviewResult] = useState(null)
  const [mutatingId, setMutatingId] = useState('')
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [capabilityResult, page] = await Promise.all([
        getAgentKnowledgeCapability(),
        listAgentKnowledgeSources({ pageNum, pageSize: 20, keyword: keyword || undefined, domain: domain || undefined }),
      ])
      setCapability(capabilityResult || {})
      setItems(page?.items || [])
      setTotal(page?.total || 0)
      setStatistics(page?.statistics || { published: 0, draft: 0, processing: 0, failed: 0 })
    } catch (loadError) {
      setError(loadError.message || 'AI知识库加载失败')
    } finally {
      setLoading(false)
    }
  }, [domain, keyword, pageNum])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (!items.some((item) => ['PENDING', 'PROCESSING'].includes(item.indexStatus))) return undefined
    const timer = window.setInterval(load, 4000)
    return () => window.clearInterval(timer)
  }, [items, load])

  const stats = useMemo(() => statistics, [statistics])

  const submitUpload = async () => {
    const values = await form.validateFields()
    if (!selectedFile) {
      message.error('请选择知识文档')
      return
    }
    setUploading(true)
    try {
      await uploadAgentKnowledgeSource({
        ...values,
        file: selectedFile,
        clientRequestId: uploadRequestId || `knowledge-${selectedFile.name}-${selectedFile.size}`,
      })
      message.success('文档已上传，正在解析和建立索引')
      setUploadOpen(false)
      setSelectedFile(null)
      setUploadRequestId('')
      form.resetFields()
      setPageNum(1)
      await load()
    } catch (uploadError) {
      message.error(uploadError.message || '知识文档上传失败')
    } finally {
      setUploading(false)
    }
  }

  const mutate = async (source, operation, successText) => {
    setMutatingId(source.sourceId)
    try {
      await operation()
      message.success(successText)
      await load()
    } catch (operationError) {
      message.error(operationError.message || '操作失败')
    } finally {
      setMutatingId('')
    }
  }

  const showChunks = async (source) => {
    setChunkSource(source)
    setChunksOpen(true)
    setChunksLoading(true)
    try {
      const result = await listAgentKnowledgeChunks(source.sourceId, { pageNum: 1, pageSize: 100 })
      setChunks(result?.items || [])
    } catch (chunkError) {
      message.error(chunkError.message || '文档分段加载失败')
      setChunks([])
    } finally {
      setChunksLoading(false)
    }
  }

  const download = async (source) => {
    setMutatingId(source.sourceId)
    try {
      const { blob, filename } = await downloadAgentKnowledgeSource(source)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (downloadError) {
      message.error(downloadError.message || '下载失败')
    } finally {
      setMutatingId('')
    }
  }

  const runPreview = async () => {
    if (!previewQuery.trim()) return
    setPreviewLoading(true)
    try {
      setPreviewResult(await previewAgentKnowledgeSearch(previewQuery.trim(), 10))
    } catch (previewError) {
      message.error(previewError.message || '知识检索预览失败')
    } finally {
      setPreviewLoading(false)
    }
  }

  const columns = [
    {
      title: '文档', dataIndex: 'title', render: (value, row) => (
        <div className="agent-knowledge-document-cell">
          <strong>{value}</strong>
          <span>{row.originalFileName} · {formatSize(row.fileSize)} · {row.chunkCount || 0} 个分段</span>
          {row.errorMessage ? <small>{row.errorMessage}</small> : null}
        </div>
      ),
    },
    { title: '领域', dataIndex: 'domain', width: 90, render: (value) => DOMAIN_OPTIONS.find((item) => item.value === value)?.label || value },
    {
      title: '状态', width: 150, render: (_, row) => {
        const life = LIFE_META[row.lifecycleStatus] || [row.lifecycleStatus, 'default']
        const index = INDEX_META[row.indexStatus] || [row.indexStatus, 'default']
        return <Space size={4} wrap><Tag color={life[1]}>{life[0]}</Tag><Tag color={index[1]}>{index[0]}</Tag></Space>
      },
    },
    {
      title: '操作', width: 360, render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" icon={<EyeOutlined />} disabled={!row.chunkCount} onClick={() => showChunks(row)}>分段</Button>
          <Button size="small" icon={<DownloadOutlined />} loading={mutatingId === row.sourceId} onClick={() => download(row)}>原文件</Button>
          {row.indexStatus === 'READY' && ['DRAFT', 'UNPUBLISHED'].includes(row.lifecycleStatus) ? (
            <Popconfirm title="发布后同企业小云可以检索此文档，确认发布？" onConfirm={() => mutate(
              row, () => publishAgentKnowledgeSource(row.sourceId, row.version), '知识文档已发布'
            )}><Button size="small" type="primary" disabled={readOnly}>发布</Button></Popconfirm>
          ) : null}
          {row.lifecycleStatus === 'PUBLISHED' ? (
            <Popconfirm title="下线后小云将立即停止使用此文档，确认下线？" onConfirm={() => mutate(
              row, () => unpublishAgentKnowledgeSource(row.sourceId), '知识文档已下线'
            )}><Button size="small" disabled={readOnly}>下线</Button></Popconfirm>
          ) : null}
          {!['PENDING', 'PROCESSING'].includes(row.indexStatus) && row.lifecycleStatus !== 'ARCHIVED' ? (
            <Button size="small" icon={<ReloadOutlined />} disabled={readOnly} onClick={() => mutate(
              row, () => reindexAgentKnowledgeSource(row.sourceId), '已提交重新索引任务'
            )}>重建</Button>
          ) : null}
          {row.lifecycleStatus !== 'ARCHIVED' ? (
            <Popconfirm title="归档后文档将立即停止参与小云回答，确认归档？" onConfirm={() => mutate(
              row, () => archiveAgentKnowledgeSource(row.sourceId), '知识文档已归档'
            )}><Button size="small" danger disabled={readOnly}>归档</Button></Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ]

  if (!capability.managementEnabled && !loading) {
    return <div className="agent-knowledge-unavailable"><DatabaseOutlined /><h3>AI知识库管理暂未开放</h3><p>该入口仅向企业老板开放，并需要先完成向量服务配置。</p></div>
  }

  return (
    <div className="agent-knowledge-page">
      {!capability.vectorConfigured || !agentCapability?.semanticQueryEnabled ? (
        <Alert type="warning" showIcon message="向量服务尚未启用" description="文档管理开关已开放，但向量服务未就绪时索引任务会失败；小云会继续使用内置知识。" />
      ) : null}
      {readOnly ? <Alert type="info" showIcon message="会员宽限期只读" description="可以查看和下载现有知识，但不能上传、发布、下线、重建或归档。" /> : null}
      {error ? <Alert type="error" showIcon message={error} action={<Button size="small" onClick={load}>重试</Button>} /> : null}

      <div className="agent-knowledge-toolbar">
        <Space wrap>
          <Input.Search allowClear placeholder="搜索标题、说明或标签" onSearch={(value) => { setKeyword(value.trim()); setPageNum(1) }} />
          <Select allowClear placeholder="全部领域" options={DOMAIN_OPTIONS} value={domain || undefined} onChange={(value) => { setDomain(value || ''); setPageNum(1) }} />
        </Space>
        <Space>
          <Button icon={<SearchOutlined />} onClick={() => { setPreviewResult(null); setPreviewOpen(true) }}>检索预览</Button>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<CloudUploadOutlined />} disabled={readOnly} onClick={() => setUploadOpen(true)}>上传文档</Button>
        </Space>
      </div>

      <div className="agent-knowledge-stats">
        <Statistic title="当前文档" value={total} />
        <Statistic title="已发布" value={stats.published} />
        <Statistic title="草稿/下线" value={stats.draft} />
        <Statistic title="处理中" value={stats.processing} />
        <Statistic title="失败" value={stats.failed} valueStyle={stats.failed ? { color: '#cf1322' } : undefined} />
      </div>

      <Table
        rowKey="sourceId"
        loading={loading}
        columns={columns}
        dataSource={items}
        locale={{ emptyText: <Empty description="暂无企业知识文档" /> }}
        pagination={{ current: pageNum, pageSize: 20, total, showSizeChanger: false, onChange: setPageNum }}
      />

      <Modal title="上传AI知识文档" open={uploadOpen} confirmLoading={uploading} okText="上传并解析" onOk={submitUpload}
        onCancel={() => { if (!uploading) { setUploadOpen(false); setSelectedFile(null); setUploadRequestId(''); form.resetFields() } }}>
        <Alert type="info" showIcon message="数据处理说明" description="文档文本将发送给配置的Embedding服务生成向量。请勿上传密码、密钥、身份证或银行卡等不必要的敏感信息。" />
        <Form form={form} layout="vertical" initialValues={{ domain: 'GENERAL' }}>
          <Form.Item label="文档" required>
            <Dragger accept=".pdf,.docx,.txt,.md" maxCount={1} fileList={selectedFile ? [selectedFile] : []}
              beforeUpload={(file) => { setSelectedFile(file); setUploadRequestId(`knowledge-${Date.now()}-${file.name}-${file.size}`); if (!form.getFieldValue('title')) form.setFieldValue('title', file.name.replace(/\.[^.]+$/, '')); return false }}
              onRemove={() => { setSelectedFile(null); setUploadRequestId(''); return true }}>
              <p className="ant-upload-drag-icon"><CloudUploadOutlined /></p>
              <p>拖拽或点击选择 PDF、DOCX、TXT、Markdown，最大20MB</p>
            </Dragger>
          </Form.Item>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入知识标题' }, { max: 255 }]}><Input /></Form.Item>
          <Form.Item label="领域" name="domain"><Select options={DOMAIN_OPTIONS} /></Form.Item>
          <Form.Item label="标签" name="tags"><Input maxLength={1000} placeholder="多个标签用逗号分隔" /></Form.Item>
          <Form.Item label="说明" name="description"><Input.TextArea maxLength={1000} rows={3} /></Form.Item>
        </Form>
      </Modal>

      <Modal width={920} title={chunkSource ? `文档分段：${chunkSource.title}` : '文档分段'} open={chunksOpen} footer={null}
        onCancel={() => setChunksOpen(false)}>
        <Table rowKey="chunkId" loading={chunksLoading} dataSource={chunks} pagination={{ pageSize: 10 }} columns={[
          { title: '#', dataIndex: 'chunkSeq', width: 60 },
          { title: '标题路径', dataIndex: 'headingPath', width: 180, render: (value) => value || '-' },
          { title: '内容', dataIndex: 'content', render: (value) => <div className="agent-knowledge-chunk-content">{value}</div> },
          { title: '估算Tokens', dataIndex: 'tokenCount', width: 100 },
        ]} />
      </Modal>

      <Modal width={820} title="知识检索预览" open={previewOpen} footer={null} onCancel={() => setPreviewOpen(false)}>
        <Input.Search enterButton="检索" loading={previewLoading} value={previewQuery} onChange={(event) => setPreviewQuery(event.target.value)} onSearch={runPreview}
          placeholder="输入用户可能提出的问题" />
        {previewResult?.warning ? <Alert className="agent-knowledge-preview-alert" type="warning" showIcon message={previewResult.warning} /> : null}
        <div className="agent-knowledge-preview-list">
          {(previewResult?.items || []).map((item, index) => (
            <div key={item.chunkId || item.sourceId || index}>
              <Space><Tag color="blue">#{index + 1}</Tag><Tag>{item.retrievalMethod}</Tag><strong>{item.title}</strong></Space>
              <p>{item.excerpt}</p>
            </div>
          ))}
          {previewResult && !previewResult.items?.length ? <Empty description="没有检索到相关知识" /> : null}
        </div>
      </Modal>
    </div>
  )
}

export default AgentKnowledge
