import React, { useState } from 'react';
import { Button, message } from 'antd';
import { downloadAgentArtifact } from '@/api/agent';

export default function BusinessArtifactDownloads({ artifacts = [] }) {
  const [downloading, setDownloading] = useState(null);
  const files = artifacts.filter((item) => item.status === 'READY'
    && ['BUSINESS_EXCEL', 'RECEIVABLE_COLLECTION'].includes(item.artifactType));
  if (!files.length) return null;
  const download = async (file) => {
    setDownloading(file.artifactId);
    try {
      const { blob, filename } = await downloadAgentArtifact(file.artifactId, file.title);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a'); link.href = url; link.download = filename;
      document.body.appendChild(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      message.error(error.message || '下载失败，请重试');
    } finally { setDownloading(null); }
  };
  return <div className="agent-result-card" aria-label="本会话生成的文件">
    <strong>本会话生成的文件</strong>
    {files.map((file) => <div className="agent-result-row" key={file.artifactId}>
      <span>{file.title}</span>
      <Button loading={downloading === file.artifactId} disabled={!!downloading && downloading !== file.artifactId} onClick={() => download(file)}>下载 Excel</Button>
    </div>)}
  </div>;
}
