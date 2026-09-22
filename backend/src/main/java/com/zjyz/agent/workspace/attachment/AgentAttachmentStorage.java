package com.zjyz.agent.workspace.attachment;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.*;
import com.zjyz.common.exception.MyBizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.*;

/** Private OSS objects; no public URLs or caller-provided object keys. */
@Component
public class AgentAttachmentStorage {
    @Value("${aliyun.oss.endpoint:https://oss-cn-hangzhou.aliyuncs.com}") private String endpoint;
    @Value("${aliyun.oss.accessKeyId:}") private String key;
    @Value("${aliyun.oss.accessKeySecret:}") private String secret;
    @Value("${aliyun.oss.bucketName:}") private String bucket;
    public void put(String objectKey, byte[] bytes) {
        OSS client=client();
        try {
            ObjectMetadata metadata=new ObjectMetadata(); metadata.setContentLength(bytes.length);
            metadata.setContentType("application/octet-stream"); metadata.setObjectAcl(CannedAccessControlList.Private);
            client.putObject(new PutObjectRequest(bucket,objectKey,new ByteArrayInputStream(bytes),metadata));
        } finally { client.shutdown(); }
    }
    public byte[] get(String objectKey) {
        OSS client=client();
        try(OSSObject object=client.getObject(bucket,objectKey); InputStream in=object.getObjectContent(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192]; int n;
            while((n=in.read(buf))!=-1) { if(out.size()+n>20*1024*1024)throw new MyBizException("附件超出读取限制","ATTACHMENT_TOO_LARGE"); out.write(buf,0,n); }
            return out.toByteArray();
        } catch(IOException e) {throw new MyBizException("附件读取失败","ATTACHMENT_STORAGE_FAILED");}
        finally {client.shutdown();}
    }
    public void delete(String objectKey) { OSS client=client(); try {client.deleteObject(bucket,objectKey);}finally{client.shutdown();} }
    private OSS client(){
        if(!StringUtils.hasText(key)||!StringUtils.hasText(secret)||!StringUtils.hasText(bucket))throw new MyBizException("附件存储尚未配置","ATTACHMENT_STORAGE_UNAVAILABLE");
        return new OSSClientBuilder().build(endpoint,key,secret);
    }
}
