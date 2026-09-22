import { canRevokeLearning, canVerifyLearning, learningStatus } from './learningView';

test.each(['REVOKED', 'SUPERSEDED', 'REJECTED'])('inactive %s cannot be verified or revoked again', (status) => {
  expect(canVerifyLearning({ canEdit: true, status })).toBe(false);
  expect(canRevokeLearning({ canEdit: true, status })).toBe(false);
});
test.each(['ACTIVE', 'PENDING_VERIFICATION', 'CONFLICT'])('read-only %s never exposes write actions', (status) => {
  expect(canVerifyLearning({ canEdit: false, status })).toBe(false);
  expect(canRevokeLearning({ canEdit: false, status })).toBe(false);
});
test('pending verification is never presented as remembered', () => {
  expect(learningStatus('PENDING_VERIFICATION')[0]).toBe('待核验');
  expect(learningStatus('ACTIVE')[0]).toBe('已记住');
  expect(learningStatus('unexpected')[0]).toBe('状态待确认');
});
