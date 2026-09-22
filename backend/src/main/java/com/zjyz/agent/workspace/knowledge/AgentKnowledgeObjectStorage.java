package com.zjyz.agent.workspace.knowledge;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class AgentKnowledgeObjectStorage {
    @Value("${aliyun.oss.endpoint:https://oss-cn-hangzhou.aliyuncs.com}")
    private String endpoint;
    @Value("${aliyun.oss.accessKeyId:}")
    private String accessKeyId;
    @Value("${aliyun.oss.accessKeySecret:}")
    private String accessKeySecret;
    @Value("${aliyun.oss.bucketName:}")
    private String bucketName;

    public String put(String cid, String sourceId, int version, byte[] bytes) {
        String objectKey = "agent-knowledge/" + safeSegment(cid) + "/" + safeSegment(sourceId)
                + "/v" + version + "/original";
        OSS client = client();
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            client.putObject(bucketName, objectKey, input);
            return objectKey;
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB016", "知识文件存储失败", e);
        } finally {
            client.shutdown();
        }
    }

    public byte[] get(String objectKey) {
        OSS client = client();
        try (OSSObject object = client.getObject(bucketName, objectKey);
             InputStream input = object.getObjectContent();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) {
                    output.write(buffer, 0, length);
                }
            }
            return output.toByteArray();
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB016", "知识文件读取失败", e);
        } finally {
            client.shutdown();
        }
    }

    public void deleteQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        OSS client = null;
        try {
            client = client();
            client.deleteObject(bucketName, objectKey);
        } catch (Exception ignored) {
            // A failed compensation is retained in MySQL and can be cleaned by operations.
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    private OSS client() {
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucketName)
                || !StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new KnowledgeProcessingException("AKB016", "OSS配置不完整");
        }
        return new OSSClientBuilder().build(endpoint.trim(), accessKeyId.trim(), accessKeySecret.trim());
    }

    private String safeSegment(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
