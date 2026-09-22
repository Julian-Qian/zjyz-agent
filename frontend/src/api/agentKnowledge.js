import apiConfig from '@/config/api.config'
import { getToken, request } from '@/utils'

const ensureSuccess = (response, fallback) => {
  if (!response?.succeed) {
    const error = new Error(response?.errorMessage || fallback)
    error.returnCode = response?.returnCode
    throw error
  }
  return response.data
}

export const getAgentKnowledgeCapability = async () => (
  ensureSuccess(await request.get('/agent/knowledge/management-capability'), '知识库能力加载失败')
)

export const listAgentKnowledgeSources = async (params = {}) => (
  ensureSuccess(await request.get('/agent/knowledge/sources', { params, membershipGraceRead: true }), '知识文档加载失败')
)

export const getAgentKnowledgeSource = async (sourceId) => (
  ensureSuccess(await request.get(`/agent/knowledge/sources/${sourceId}`, { membershipGraceRead: true }), '知识文档详情加载失败')
)

export const listAgentKnowledgeChunks = async (sourceId, params = {}) => (
  ensureSuccess(await request.get(`/agent/knowledge/sources/${sourceId}/chunks`, { params, membershipGraceRead: true }), '文档分段加载失败')
)

export const uploadAgentKnowledgeSource = async ({ file, title, description, domain, tags, clientRequestId }) => {
  const form = new FormData()
  form.append('file', file)
  form.append('title', title)
  if (description) form.append('description', description)
  if (domain) form.append('domain', domain)
  if (tags) form.append('tags', tags)
  if (clientRequestId) form.append('clientRequestId', clientRequestId)
  return ensureSuccess(await request({
    url: '/agent/knowledge/sources',
    method: 'POST',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  }), '知识文档上传失败')
}

export const publishAgentKnowledgeSource = async (sourceId, expectedVersion) => (
  ensureSuccess(await request.post(`/agent/knowledge/sources/${sourceId}/publish`, { expectedVersion }), '知识文档发布失败')
)

export const unpublishAgentKnowledgeSource = async (sourceId) => (
  ensureSuccess(await request.post(`/agent/knowledge/sources/${sourceId}/unpublish`), '知识文档下线失败')
)

export const reindexAgentKnowledgeSource = async (sourceId) => (
  ensureSuccess(await request.post(`/agent/knowledge/sources/${sourceId}/reindex`), '重新索引失败')
)

export const archiveAgentKnowledgeSource = async (sourceId) => (
  ensureSuccess(await request.delete(`/agent/knowledge/sources/${sourceId}`), '知识文档归档失败')
)

export const previewAgentKnowledgeSearch = async (query, limit = 10) => (
  ensureSuccess(await request.post('/agent/knowledge/search-preview', { query, limit }), '知识检索预览失败')
)

export async function downloadAgentKnowledgeSource(source) {
  const token = getToken()
  const baseUrl = (apiConfig.baseURL || '').replace(/\/$/, '')
  const response = await fetch(`${baseUrl}/agent/knowledge/sources/${source.sourceId}/download`, {
    method: 'GET',
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || contentType.includes('application/json')) {
    let errorMessage = response.status === 401 ? '登录状态已失效，请重新登录' : '知识原文件下载失败'
    try {
      const payload = await response.json()
      errorMessage = payload?.errorMessage || errorMessage
    } catch (error) {
      // Keep safe fallback.
    }
    throw new Error(errorMessage)
  }
  const disposition = response.headers.get('content-disposition') || ''
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  let filename = source.originalFileName || '知识文档'
  if (encodedName) {
    try { filename = decodeURIComponent(encodedName) } catch (error) { filename = source.originalFileName || '知识文档' }
  }
  return { blob: await response.blob(), filename }
}
