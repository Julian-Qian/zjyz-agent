import { request } from '@/utils';

const unwrap = (response) => {
  if (!response?.succeed) {
    const error = new Error(response?.errorMessage || '学习记录操作失败，请重试');
    error.returnCode = response?.returnCode;
    throw error;
  }
  return response.data;
};

export const getLearningCapabilities = async () => unwrap(await request.get('/agent/learning/capabilities'));
export const listLearningEntries = async (params) => unwrap(await request.get('/agent/learning/entries', { params }));
export const getLearningEntry = async (id) => unwrap(await request.get(`/agent/learning/entries/${encodeURIComponent(id)}`));
export const changeLearningEntry = async (id, action, payload) => {
  if (!['correct', 'verify', 'revoke', 'publish'].includes(action)) throw new Error('不支持的学习操作');
  return unwrap(await request.post(`/agent/learning/entries/${encodeURIComponent(id)}/${action}`, payload));
};
