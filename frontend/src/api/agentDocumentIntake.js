import { request } from '@/utils';

export function previewRentOutDocumentFromImage({ file, projectId, userInstruction }) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('projectId', projectId);
  if (userInstruction) formData.append('userInstruction', userInstruction);
  return request({
    url: '/agent/document-intake/rent-out/preview',
    method: 'POST',
    data: formData,
    timeout: 120000,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function confirmRentOutDocumentFromImage(draftPayload) {
  return request({
    url: '/agent/document-intake/rent-out/confirm',
    method: 'POST',
    data: { draftPayload },
    timeout: 120000,
  });
}
