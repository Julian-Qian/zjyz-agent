import { request } from '@/utils';

const unwrap = (response) => {
  if (!response?.succeed) {
    const error = new Error(response?.errorMessage || '附件操作失败');
    error.returnCode = response?.returnCode;
    throw error;
  }
  return response.data;
};
const path = (id) => `/agent/v2/attachments/${encodeURIComponent(id)}`;
export async function uploadAgentAttachment(threadId, file, clientRequestId) {
  const data = new FormData();
  data.append('file', file);
  data.append('clientRequestId', clientRequestId);
  return unwrap(await request({ url: `/agent/v2/threads/${encodeURIComponent(threadId)}/attachments`, method: 'POST', data, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 }));
}
export const getAgentAttachment = async (id) => unwrap(await request.get(path(id)));
export const deleteAgentAttachment = async (id) => unwrap(await request.delete(path(id)));
export const retryAgentAttachment = async (id, expectedVersion) => unwrap(await request.post(`${path(id)}/retry`, { expectedVersion }));
export const getAgentAttachmentContent = async (id, parseRevision, blockId) => unwrap(await request.get(`${path(id)}/content`, { params: { parseRevision, blockId } }));
