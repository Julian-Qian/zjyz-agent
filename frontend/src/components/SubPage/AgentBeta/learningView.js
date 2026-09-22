export const learningStatuses = {
  ACTIVE: ['已记住', 'success'],
  PENDING_VERIFICATION: ['待核验', 'processing'],
  CONFLICT: ['存在冲突', 'warning'],
  SUPERSEDED: ['已被替代', 'default'],
  REVOKED: ['已撤销', 'default'],
  REJECTED: ['未采纳', 'error'],
};
export const learningKinds = {
  USER_PREFERENCE: '表达偏好', BUSINESS_RULE: '业务规则', ALIAS: '别名与指代',
  CAPABILITY: '系统能力', DEFECT: '问题反馈',
};
export const learningScopes = { PERSONAL: '仅本人', TENANT: '企业共享', PROJECT: '项目范围' };
export const canVerifyLearning = (entry) => Boolean(entry?.canEdit
  && ['PENDING_VERIFICATION', 'CONFLICT', 'ACTIVE'].includes(entry.status));
export const canRevokeLearning = (entry) => Boolean(entry?.canEdit
  && !['REVOKED', 'SUPERSEDED', 'REJECTED'].includes(entry.status));
export const learningStatus = (status) => learningStatuses[status] || ['状态待确认', 'default'];
