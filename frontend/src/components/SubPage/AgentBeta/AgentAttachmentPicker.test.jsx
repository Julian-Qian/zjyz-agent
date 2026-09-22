import React from 'react';
import '@testing-library/jest-dom';
import { render, screen, waitFor } from '@testing-library/react';
import AgentAttachmentPicker, { ContractReviewCard } from './AgentAttachmentPicker';
import { getAgentAttachment } from '@/api/agentAttachments';

jest.mock('@/api/agentAttachments', () => ({
  getAgentAttachment: jest.fn(), uploadAgentAttachment: jest.fn(), deleteAgentAttachment: jest.fn(), retryAgentAttachment: jest.fn(), getAgentAttachmentContent: jest.fn(),
}), { virtual: true });
jest.mock('antd', () => {
  const React = require('react');
  const wrapper = ({ children }) => <div>{children}</div>;
  return { Alert: ({ message }) => <div>{message}</div>, Button: ({ children, icon, ...props }) => <button {...props}>{children}</button>, Card: wrapper, Modal: ({ open, children }) => open ? <div>{children}</div> : null, Space: wrapper, Spin: wrapper, Tag: wrapper, Typography: { Text: wrapper, Paragraph: wrapper } };
});
jest.mock('@ant-design/icons', () => ({ PaperClipOutlined: () => null, DeleteOutlined: () => null, ReloadOutlined: () => null }));

afterEach(() => jest.clearAllMocks());
test('empty review does not render a misleading review card', () => {
  const { container } = render(<ContractReviewCard review={{}} />);
  expect(container).toBeEmptyDOMElement();
});
test('selection only becomes ready after authoritative parse status is loaded', async () => {
  getAgentAttachment.mockResolvedValue({ attachmentId: 'a', filename: '合同.pdf', status: 'PARTIAL', coverage: { gaps: ['第二页未识别'] } });
  const onReadyChange = jest.fn();
  render(<AgentAttachmentPicker value={['a']} threadId="t" onChange={jest.fn()} onReadyChange={onReadyChange} />);
  expect(onReadyChange).toHaveBeenCalledWith(false);
  await waitFor(() => expect(onReadyChange).toHaveBeenLastCalledWith(true));
  expect(screen.getByText('第二页未识别')).toBeInTheDocument();
});
test('unavailable attachment blocks submission rather than silently dropping its ID', async () => {
  getAgentAttachment.mockRejectedValue(new Error('附件已删除'));
  const onReadyChange = jest.fn(); const onChange = jest.fn();
  render(<AgentAttachmentPicker value={['a']} threadId="t" onChange={onChange} onReadyChange={onReadyChange} />);
  await screen.findByText('附件已删除');
  expect(onReadyChange).toHaveBeenLastCalledWith(false);
  expect(onChange).not.toHaveBeenCalled();
});

test('temporary status failure retries and recovers without dropping the attachment', async () => {
  getAgentAttachment.mockRejectedValueOnce(new Error('暂时断网')).mockResolvedValue({ attachmentId: 'a', status: 'READY' });
  const onReadyChange = jest.fn();
  render(<AgentAttachmentPicker value={['a']} threadId="t" onChange={jest.fn()} onReadyChange={onReadyChange} />);
  await screen.findByText('暂时断网');
  await waitFor(() => expect(onReadyChange).toHaveBeenLastCalledWith(true), { timeout: 2500 });
  expect(getAgentAttachment).toHaveBeenCalledTimes(2);
});

test('delete after a status error still calls the server before removing selection', async () => {
  const { deleteAgentAttachment } = require('@/api/agentAttachments');
  const { fireEvent } = require('@testing-library/react');
  getAgentAttachment.mockRejectedValue({ returnCode: 'AGT404', message: '状态不可访问' });
  deleteAgentAttachment.mockResolvedValue({ status: 'DELETED' });
  const onChange = jest.fn();
  render(<AgentAttachmentPicker value={['a']} threadId="t" onChange={onChange} />);
  await screen.findByText('状态不可访问');
  fireEvent.click(screen.getByText('删除附件'));
  await waitFor(() => expect(deleteAgentAttachment).toHaveBeenCalledWith('a'));
  await waitFor(() => expect(onChange).toHaveBeenCalledWith([]));
});
