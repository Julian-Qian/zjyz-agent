import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import AgentLearning, { LearningCards } from './AgentLearning';
import * as api from '@/api/agentLearning';

jest.mock('@/api/agentLearning', () => ({
  getLearningCapabilities: jest.fn(), listLearningEntries: jest.fn(),
  getLearningEntry: jest.fn(), changeLearningEntry: jest.fn(),
}), { virtual: true });
beforeAll(() => {
  window.matchMedia = jest.fn().mockImplementation(() => ({ matches: false, addListener: jest.fn(), removeListener: jest.fn() }));
});
beforeEach(() => jest.clearAllMocks());

test('historical card identifies pending state and links to live record', () => {
  const listener = jest.fn(); window.addEventListener('agent:learning-open', listener);
  render(<LearningCards entries={[{ id: 'a', content: '待核验规则', status: 'PENDING_VERIFICATION', scopeType: 'PERSONAL' }]} />);
  expect(screen.getByText('待核验')).toBeTruthy();
  fireEvent.click(screen.getByText('查看最新状态和依据'));
  expect(listener.mock.calls[0][0].detail.id).toBe('a');
  window.removeEventListener('agent:learning-open', listener);
});

test('disabled feature explains that nothing is saved and does not query records', async () => {
  api.getLearningCapabilities.mockResolvedValue({ canReadOwn: false });
  render(<AgentLearning />); fireEvent.click(screen.getByText('学习记录'));
  await screen.findByText('学习功能尚未启用');
  expect(api.listLearningEntries).not.toHaveBeenCalled();
});

test('failed loading has a retry and never fabricates entries', async () => {
  api.getLearningCapabilities.mockRejectedValue(new Error('加载失败'));
  render(<AgentLearning />); fireEvent.click(screen.getByText('学习记录'));
  await screen.findByText('加载失败');
  api.getLearningCapabilities.mockResolvedValue({ canReadOwn: true });
  api.listLearningEntries.mockResolvedValue({ items: [], total: 0 });
  fireEvent.click(screen.getByRole('button', { name: /刷\s*新/ }));
  await waitFor(() => expect(api.listLearningEntries).toHaveBeenCalled());
});
