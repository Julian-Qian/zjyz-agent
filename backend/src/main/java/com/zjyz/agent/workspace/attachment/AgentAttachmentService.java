package com.zjyz.agent.workspace.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.*;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

@Service
public class AgentAttachmentService {
    private final AgentAttachmentMapper mapper;
    private final AgentRuntimeMapper runtime;
    private final AgentAttachmentStorage storage;
    private final AgentAttachmentParser parser;
    private final ObjectMapper json;
    @Value("${agent.attachment.enabled:false}") private boolean enabled;
    public AgentAttachmentService(AgentAttachmentMapper mapper,AgentRuntimeMapper runtime,AgentAttachmentStorage storage,AgentAttachmentParser parser,ObjectMapper json){this.mapper=mapper;this.runtime=runtime;this.storage=storage;this.parser=parser;this.json=json;}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> upload(String threadId,MultipartFile file,String requestId){
        requireEnabled();requireThread(threadId);
        if(!StringUtils.hasText(requestId)||requestId.length()>100)throw error("请提供有效上传请求标识","AGT400");
        if(file==null||file.isEmpty()||file.getSize()>20*1024*1024)throw error("附件为空或超过20MB","ATTACHMENT_TOO_LARGE");
        byte[] bytes;try{bytes=file.getBytes();}catch(Exception e){throw error("无法读取上传文件","ATTACHMENT_STORAGE_FAILED");}
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("attachment").replaceAll("[\\\\/\\p{Cntrl}]","_");
        if(name.length()>240)name=name.substring(name.length()-240);
        String mime=parser.validate(name,bytes),hash=hash(bytes);
        Attachment existing=mapper.byKey(cid(),uid(),requestId);
        if(existing!=null)return replay(existing,threadId,hash);
        Attachment a=new Attachment();a.setAttachmentId("aat_"+UUID.randomUUID().toString().replace("-",""));
        a.setCid(cid());a.setOwnerUid(uid());a.setThreadId(threadId);a.setClientRequestId(requestId);a.setChecksum(hash);
        a.setFilename(name);a.setMimeType(mime);a.setByteSize(bytes.length);a.setVersion(1);a.setParseRevision(1);a.setRowVersion(1);a.setStatus("PENDING");
        a.setCreatedAt(LocalDateTime.now());a.setExpiresAt(a.getCreatedAt().plusDays(30));
        a.setObjectKey("agent-attachments/"+a.getAttachmentId()+"/original");
        storage.put(a.getObjectKey(),bytes);
        final String key=a.getObjectKey();
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{storage.delete(key);}catch(Exception ignored){}}});
        try{mapper.insert(a);}catch(DuplicateKeyException e){storage.delete(key);Attachment concurrent=mapper.byKey(cid(),uid(),requestId);if(concurrent!=null)return replay(concurrent,threadId,hash);throw e;}
        mapper.insertJob(job(a.getAttachmentId(),1));
        return view(a);
    }
    private Map<String,Object> replay(Attachment a,String thread,String hash){
        if(!Objects.equals(a.getThreadId(),thread)||!Objects.equals(a.getChecksum(),hash))throw error("同一上传标识对应不同文件或会话","AGT409");
        if("DELETED".equals(a.getStatus())||a.getExpiresAt().isBefore(LocalDateTime.now()))throw error("附件已删除或过期，请重新上传","ATTACHMENT_UNAVAILABLE");
        return view(a);
    }
    public Map<String,Object> detail(String id){return view(require(id,false));}
    public Map<String,Object> content(String id,int revision,String blockId){
        Attachment a=require(id,false);ParsedDocument doc=parsed(a,revision);
        Map<String,Object> result=new LinkedHashMap<>();result.put("attachmentId",id);result.put("version",a.getVersion());result.put("parseRevision",revision);result.put("coverage",coverage(doc));
        if(StringUtils.hasText(blockId)){Block b=doc.getBlocks().stream().filter(v->blockId.equals(v.getId())).findFirst().orElseThrow(()->error("原文位置不存在","AGT404"));result.put("blocks",Collections.singletonList(b));}
        else result.put("blocks",doc.getBlocks());
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> retry(String id,int expectedVersion){
        requireEnabled();Attachment a=require(id,false);
        if(mapper.retry(id,expectedVersion)!=1)throw error("附件状态已变化或正在解析","AGT409");
        mapper.insertJob(job(id,a.getParseRevision()+1));return view(mapper.get(id));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> delete(String id){Attachment a=mapper.lock(id);requireOwned(a);mapper.delete(id);mapper.cancelJobs(id);return Collections.singletonMap("status","DELETED");}

    /** Only references, never file text or storage keys, enter a Task context. */
    public List<Map<String,Object>> freeze(String threadId,List<String> ids){
        if(ids==null||ids.isEmpty())return Collections.emptyList();requireEnabled();requireThread(threadId);
        if(ids.size()>3)throw error("每次最多审阅3份附件","AGT400");
        List<Map<String,Object>> refs=new ArrayList<>();
        for(String id:new LinkedHashSet<>(ids)){Attachment a=require(id,false);if(!threadId.equals(a.getThreadId()))throw error("附件不存在或无权限","AGT404");ParsedDocument doc=parsed(a,a.getParseRevision());Map<String,Object> ref=new LinkedHashMap<>();ref.put("attachmentId",id);ref.put("version",a.getVersion());ref.put("parseRevision",a.getParseRevision());ref.put("checksum",a.getChecksum());ref.put("filename",a.getFilename());ref.put("coverage",coverage(doc));refs.add(Collections.unmodifiableMap(ref));}
        return Collections.unmodifiableList(refs);
    }
    public ParsedDocument readFrozen(String threadId,Map<String,Object> ref){
        requireEnabled();requireThread(threadId);
        if(ref==null||!(ref.get("attachmentId") instanceof String))throw error("附件引用无效","ATTACHMENT_UNAVAILABLE");
        Attachment a=require((String)ref.get("attachmentId"),false);
        if(!threadId.equals(a.getThreadId())||!Objects.equals(a.getChecksum(),ref.get("checksum"))||!String.valueOf(a.getVersion()).equals(String.valueOf(ref.get("version"))))throw error("附件版本已失效","ATTACHMENT_UNAVAILABLE");
        int revision;try{revision=Integer.parseInt(String.valueOf(ref.get("parseRevision")));}catch(Exception e){throw error("附件解析引用无效","ATTACHMENT_UNAVAILABLE");}
        return parsed(a,revision);
    }
    public void validateFrozen(String threadId,List<Map<String,Object>> refs){for(Map<String,Object> ref:refs)readFrozen(threadId,ref);}
    /** Serialize publication and deletion on the same attachment rows, in a stable lock order. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public void lockAndValidate(String threadId,List<Map<String,Object>> refs){
        if(refs==null||refs.size()>3)throw error("附件引用无效","ATTACHMENT_UNAVAILABLE");
        List<Map<String,Object>> sorted=new ArrayList<>(refs);
        sorted.sort(Comparator.comparing(ref->String.valueOf(ref.get("attachmentId"))));
        for(Map<String,Object> ref:sorted){
            Object id=ref.get("attachmentId");if(!(id instanceof String))throw error("附件引用无效","ATTACHMENT_UNAVAILABLE");
            Attachment a=mapper.lock((String)id);requireOwned(a);
            if(!threadId.equals(a.getThreadId())||"DELETED".equals(a.getStatus())||!a.getExpiresAt().isAfter(LocalDateTime.now())
                ||!Objects.equals(a.getChecksum(),ref.get("checksum"))||!String.valueOf(a.getVersion()).equals(String.valueOf(ref.get("version"))))
                throw error("附件已删除、过期或版本失效，未发布合同内容","ATTACHMENT_UNAVAILABLE");
            int revision;try{revision=Integer.parseInt(String.valueOf(ref.get("parseRevision")));}catch(Exception e){throw error("附件引用无效","ATTACHMENT_UNAVAILABLE");}
            parsed(a,revision);
        }
    }
    private void requireOwned(Attachment a){
        if(a==null||!cid().equals(a.getCid())||!uid().equals(a.getOwnerUid()))throw error("附件不存在或无权限","AGT404");
        requireThread(a.getThreadId());
    }

    private ParsedDocument parsed(Attachment a,int revision){
        ParseJob j=mapper.revision(a.getAttachmentId(),revision);
        if(j==null||(!"READY".equals(j.getStatus())&&!"PARTIAL".equals(j.getStatus())))throw error("附件尚未完成解析，请查看附件进度后重试","ATTACHMENT_NOT_READY");
        try{return json.readValue(j.getResultJson(),ParsedDocument.class);}catch(Exception e){throw error("附件解析结果不可用","ATTACHMENT_UNAVAILABLE");}
    }
    private Attachment require(String id,boolean deleted){
        if(!StringUtils.hasText(id))throw error("附件不存在或无权限","AGT404");
        Attachment a=mapper.get(id);
        if(a==null||!cid().equals(a.getCid())||!uid().equals(a.getOwnerUid()))throw error("附件不存在或无权限","AGT404");
        requireThread(a.getThreadId());
        if(!deleted&&("DELETED".equals(a.getStatus())||a.getExpiresAt().isBefore(LocalDateTime.now())))throw error("附件已删除或过期","ATTACHMENT_UNAVAILABLE");return a;
    }
    private void requireThread(String id){
        AgentRuntimeRecords.AgentThread t=runtime.selectThread(cid(),id);
        if(t==null||!uid().equals(t.getOwnerUid())||!"ACTIVE".equals(t.getStatus()))throw error("会话不存在或无权限","AGT404");
        AgentRuntimeRecords.Workspace w=runtime.selectWorkspaceById(cid(),t.getWorkspaceId());if(w==null||!"ACTIVE".equals(w.getStatus()))throw error("工作空间不存在或无权限","AGT404");
    }
    private void requireEnabled(){if(!enabled)throw error("会话附件功能尚未开放","ATTACHMENT_DISABLED");}
    private Map<String,Object> view(Attachment a){Map<String,Object> v=new LinkedHashMap<>();v.put("attachmentId",a.getAttachmentId());v.put("filename",a.getFilename());v.put("version",a.getVersion());v.put("parseRevision",a.getParseRevision());v.put("rowVersion",a.getRowVersion());v.put("status",a.getStatus());v.put("byteSize",a.getByteSize());v.put("expiresAt",a.getExpiresAt());ParseJob j=mapper.revision(a.getAttachmentId(),a.getParseRevision());if(j!=null){v.put("parseJobId",j.getJobId());v.put("parseStatus",j.getStatus());v.put("errorCode",j.getErrorCode());if("READY".equals(j.getStatus())||"PARTIAL".equals(j.getStatus()))v.put("coverage",coverage(parsed(a,a.getParseRevision())));}return v;}
    static Map<String,Object> coverage(ParsedDocument doc){Map<String,Object> c=new LinkedHashMap<>();c.put("complete",doc.complete());c.put("totalPages",doc.getTotalPages());c.put("parsedPages",doc.getParsedPages());c.put("blockCount",doc.getBlocks().size());c.put("gaps",doc.getGaps());c.put("warnings",doc.getWarnings());c.put("parserVersion",doc.getParserVersion());return c;}
    static ParseJob job(String id,int revision){ParseJob j=new ParseJob();j.setJobId("aap_"+UUID.randomUUID().toString().replace("-",""));j.setAttachmentId(id);j.setRevision(revision);j.setUpdatedAt(LocalDateTime.now());return j;}
    static String hash(byte[] bytes){try{StringBuilder s=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private String cid(){String v=AuthContext.getCid();if(!StringUtils.hasText(v))throw error("请先登录","AGT401");return v;}
    private String uid(){String v=AuthContext.getUid();if(!StringUtils.hasText(v))throw error("请先登录","AGT401");return v;}
    private MyBizException error(String message,String code){return new MyBizException(message,code);}
}
