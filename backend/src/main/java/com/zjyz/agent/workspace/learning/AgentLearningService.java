package com.zjyz.agent.workspace.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.membership.service.TenantMemberService;
import com.zjyz.service.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AgentLearningService {
    private final AgentLearningMapper mapper;
    private final AgentKnowledgeMapper knowledge;
    private final AgentLearningVerifier verifier;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zjyz.agent.workspace.knowledge.HybridAgentKnowledgeRetriever retriever;
    private final TenantMemberService members;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final com.zjyz.agent.workspace.tool.AgentToolCatalog catalog = new com.zjyz.agent.workspace.tool.AgentToolCatalog();
    @Value("${agent.learning.writeEnabled:${AGENT_LEARNING_WRITE_ENABLED:false}}") private boolean writeEnabled;
    @Value("${agent.learning.retrievalEnabled:${AGENT_LEARNING_RETRIEVAL_ENABLED:false}}") private boolean retrievalEnabled;
    @Value("${agent.learning.sharedEnabled:${AGENT_LEARNING_SHARED_ENABLED:false}}") private boolean sharedEnabled;
    @Value("${agent.knowledge.managementEnabled:${AGENT_KNOWLEDGE_MANAGEMENT_ENABLED:false}}") private boolean knowledgeManagement;
    public AgentLearningService(AgentLearningMapper mapper,AgentKnowledgeMapper knowledge,AgentLearningVerifier verifier,
            TenantMemberService members,ProjectService projects,ObjectMapper json) {
        this.mapper=mapper;this.knowledge=knowledge;this.verifier=verifier;this.members=members;this.projects=projects;this.json=json;
    }
    public Map<String,Object> capabilities() {
        auth(); return map("canReadOwn",writeEnabled||retrievalEnabled,"canWriteOwn",writeEnabled,
                "canManageShared",writeEnabled&&sharedEnabled&&owner(),"retrievalEnabled",retrievalEnabled);
    }
    public Map<String,Object> list(int page,int size,String keyword,String kind,String status,String scope,String projectId) {
        auth(); if(!writeEnabled&&!retrievalEnabled)return map("items",Collections.emptyList(),"total",0);
        List<AgentLearningModels.Entry> entries=mapper.candidates(cid(),uid()).stream().filter(this::visible)
            .filter(e -> blank(keyword)||e.getContent().contains(keyword))
            .filter(e -> blank(kind)||kind.equals(e.getKind())).filter(e -> blank(status)||status.equals(e.getStatus()))
            .filter(e -> blank(scope)||scope.equals(e.getScopeType())).filter(e -> blank(projectId)||projectId.equals(e.getProjectId()))
            .collect(Collectors.toList());
        int n=Math.max(1,Math.min(size,50)),offset=(Math.max(1,Math.min(page,1000))-1)*n;
        return map("items",entries.stream().skip(offset).limit(n).map(this::view).collect(Collectors.toList()),"total",entries.size());
    }
    public Map<String,Object> detail(String id) {
        readable(); AgentLearningModels.Entry e=require(id,false); Map<String,Object> v=view(e);
        v.put("events",mapper.events(cid(),id));
        AgentKnowledgeRecords.Chunk proof=evidence(e.getEvidenceChunkId());
        if(proof!=null)v.put("evidence",map("title",proof.getSourceTitle(),"content",proof.getContent(),"version",proof.getSourceVersion()));
        return v;
    }
    private Map<String,Object> view(AgentLearningModels.Entry e) {
        boolean stale="ACTIVE".equals(e.getStatus())&&!validEvidence(e);
        return map("id",e.getId(),"content",e.getContent(),"kind",e.getKind(),"status",stale?"PENDING_VERIFICATION":e.getStatus(),
            "version",e.getVersion(),"scopeType",e.getScopeType(),"projectId",e.getProjectId(),
            "reason",stale?"原依据已更新或下线，需要重新核验。":e.getReason(),"supersedesId",e.getSupersedesId(),
            "originThreadId",e.getOriginThreadId(),"originMessageId",e.getOriginMessageId(),
            "evidenceChunkId",e.getEvidenceChunkId(),"evidenceVersion",e.getEvidenceVersion(),
            "updatedAt",e.getUpdatedAt(),"canEdit",writeEnabled&&editable(e),
            "canPublish",writeEnabled&&sharedEnabled&&owner()&&"PERSONAL".equals(e.getScopeType())&&"ACTIVE".equals(e.getStatus())&&!stale);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> learn(AgentLearningModels.Action a,String threadId,String messageId,String requestId,List<String> projectIds) {
        writable(); String key=key(requestId); String replay=mapper.replay(cid(),uid(),key);
        if(replay!=null)return view(require(replay,false));
        if(a==null)throw error("缺少要记录的内容","ALN400");
        if(!Arrays.asList("SAVE","REVOKE").contains(a.getAction()))throw error("不支持的学习操作","ALN400");
        if("REVOKE".equals(a.getAction())) {
            AgentLearningModels.Mutation m=new AgentLearningModels.Mutation();m.setExpectedVersion(a.getExpectedVersion());m.setClientRequestId(requestId);
            return mutate(a.getTargetId(),"revoke",m);
        }
        AgentLearningModels.Entry old=blank(a.getTargetId())?null:locked(a.getTargetId(),a.getExpectedVersion());
        String content=text(a.getContent(),2000), kind=text(a.getKind(),40);
        if(!Arrays.asList("USER_PREFERENCE","BUSINESS_RULE","ALIAS","CAPABILITY","DEFECT").contains(kind))throw error("不支持的学习类型","ALN400");
        List<String> required=old==null?new ArrayList<>(projectIds==null?Collections.emptyList():projectIds):requiredProjects(old);
        if("USER_PREFERENCE".equals(kind)||"ALIAS".equals(kind)||"CAPABILITY".equals(kind))required=Collections.emptyList();
        for(String id:required)projects.queryProjectInfo(id);
        for(AgentLearningModels.Entry e:mapper.candidates(cid(),uid())) {
            if(old==null&&uid().equals(e.getOwnerUid())&&"PERSONAL".equals(e.getScopeType())&&kind.equals(e.getKind())
                    &&required.equals(requiredProjects(e))&&content.equals(e.getContent())&&Arrays.asList("ACTIVE","PENDING_VERIFICATION").contains(e.getStatus())&&visible(e)) {
                record(e,key,"DUPLICATE");return view(e);
            }
        }
        AgentLearningModels.Entry e=new AgentLearningModels.Entry(); e.setId("aln_"+CommonUtil.createUuid());e.setCid(cid());e.setOwnerUid(uid());
        e.setScopeType(old==null?"PERSONAL":old.getScopeType());e.setProjectId(old==null?null:old.getProjectId());e.setRequiredProjects(toJson(required));
        e.setContent(content);e.setKind(kind);e.setVersion(1);e.setOriginThreadId(threadId);e.setOriginMessageId(messageId);
        if(old!=null)e.setSupersedesId(old.getId());
        applyVerification(e,"CAPABILITY".equals(kind)&&!blank(a.getCapabilityCode())?"capability:"+a.getCapabilityCode():a.getEvidenceChunkId());
        if("REJECTED".equals(e.getStatus()))e.setContent("内容未保存：包含敏感信息或不适合作为长期记忆的指令。");
        saveContent(e);mapper.insert(e);
        if(old!=null&&"ACTIVE".equals(e.getStatus()))supersede(old,key);
        else if(old!=null&&"ACTIVE".equals(old.getStatus())&&!"REJECTED".equals(e.getStatus())) {
            old.setStatus("CONFLICT");old.setReason("用户已提出纠正，待核验期间暂停使用旧规则。");update(old);record(old,key+":hold","CONFLICT");
        }
        record(e,key,"SAVE");return view(e);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> mutate(String id,String action,AgentLearningModels.Mutation m) {
        writable(); if(m==null)throw error("缺少请求参数","ALN400");
        String key=key(m.getClientRequestId());String replay=mapper.replay(cid(),uid(),key);
        if(replay!=null)return view(require(replay,false));
        AgentLearningModels.Entry e=locked(id,m.getExpectedVersion());
        if("correct".equals(action)) {
            AgentLearningModels.Action a=new AgentLearningModels.Action();a.setTargetId(id);a.setExpectedVersion(e.getVersion());
            a.setContent(m.getContent());a.setKind(e.getKind());
            return learn(a,e.getOriginThreadId(),e.getOriginMessageId(),m.getClientRequestId(),requiredProjects(e));
        }
        if("revoke".equals(action)) { e.setStatus("REVOKED");e.setReason("已撤销，后续回答不再使用这条记忆。"); }
        else if("verify".equals(action)) {
            if(Arrays.asList("REVOKED","SUPERSEDED","REJECTED").contains(e.getStatus()))throw error("此条记录已停用，请重新提交教导","ALN409");
            applyVerification(e,e.getEvidenceChunkId());
            if("ACTIVE".equals(e.getStatus())&&!blank(e.getSupersedesId())) {
                AgentLearningModels.Entry old=require(e.getSupersedesId(),true);
                old=mapper.getForUpdate(cid(),old.getId());
                if(!visible(old)||!editable(old))throw error("记录不存在或无权访问","ALN404");
                if(!Arrays.asList("ACTIVE","PENDING_VERIFICATION","CONFLICT").contains(old.getStatus())) {
                    e.setStatus("CONFLICT");e.setReason("原规则已被撤销或替代，请重新核对当前规则。");
                } else supersede(old,key);
            }
        } else if("publish".equals(action)) {
            if(!sharedEnabled||!owner())throw error("当前无权发布共享记忆","ALN403");
            if(!"ACTIVE".equals(e.getStatus())||!validEvidence(e))throw error("只有核验有效的记忆可共享","ALN409");
            if(!"PERSONAL".equals(e.getScopeType()))throw error("此条记录已是共享记忆","ALN409");
            String scope=m.getScopeType();if(!Arrays.asList("TENANT","PROJECT").contains(scope))throw error("请选择共享范围","ALN400");
            String projectId="PROJECT".equals(scope)?text(m.getProjectId(),64):null;
            if(projectId!=null)projects.queryProjectInfo(projectId);
            AgentLearningModels.Entry copy=new AgentLearningModels.Entry();
            copy.setId("aln_"+CommonUtil.createUuid());copy.setCid(cid());copy.setOwnerUid(uid());copy.setScopeType(scope);copy.setProjectId(projectId);
            List<String> required=new ArrayList<>(requiredProjects(e));if(projectId!=null&&!required.contains(projectId))required.add(projectId);
            copy.setRequiredProjects(toJson(required));copy.setKind(e.getKind());copy.setContent(e.getContent());copy.setStatus(e.getStatus());
            copy.setVersion(1);copy.setReason(e.getReason());copy.setEvidenceChunkId(e.getEvidenceChunkId());copy.setEvidenceVersion(e.getEvidenceVersion());
            // Do not expose the personal conversation or original record through shared metadata.
            saveContent(copy);mapper.insert(copy);record(copy,key,"PUBLISH");return view(copy);
        } else throw error("不支持的操作","ALN400");
        update(e);record(e,key,action.toUpperCase(Locale.ROOT));return view(e);
    }
    private void applyVerification(AgentLearningModels.Entry e,String evidenceId) {
        AgentLearningModels.Verification preliminary=verifier.verify(e.getKind(),e.getContent(),null);
        if("REJECTED".equals(preliminary.getStatus())){setVerification(e,preliminary);return;}
        if("ALIAS".equals(e.getKind())) { verifyAlias(e);return; }
        AgentKnowledgeRecords.Chunk evidence=evidence(evidenceId);
        if(evidence==null&&!"CAPABILITY".equals(e.getKind())&&!"USER_PREFERENCE".equals(e.getKind())&&!"DEFECT".equals(e.getKind())) {
            String keyword=e.getContent().replaceAll("^(以后|记住|我说的是|应该是|不是)[，,：: ]*", "");
            keyword=keyword.substring(0,Math.min(2,keyword.length()));
            Map<String,AgentKnowledgeRecords.Chunk> candidates=new LinkedHashMap<>();
            if(retriever!=null) {
                try {
                    com.zjyz.agent.workspace.context.AgentTaskFrame frame=new com.zjyz.agent.workspace.context.AgentTaskFrame();frame.setUserGoal(e.getContent());frame.setDomain("GENERAL");
                    for(com.zjyz.agent.workspace.knowledge.AgentKnowledgeEntry hit:retriever.retrieve(frame,5).getEntries()) {
                        if(!blank(hit.getChunkId())) {AgentKnowledgeRecords.Chunk candidate=mapper.evidence(cid(),hit.getChunkId());if(candidate!=null)candidates.put(candidate.getChunkId(),candidate);}
                    }
                }catch(Exception unavailable){ /* SQL keyword fallback below remains scoped and version checked. */ }
            }
            for(AgentKnowledgeRecords.Chunk c:knowledge.selectPublishedChunksByKeyword(cid(),keyword,5))candidates.putIfAbsent(c.getChunkId(),c);
            int checked=0;
            for(AgentKnowledgeRecords.Chunk c:candidates.values()) {
                if(checked++>=3)break;
                AgentKnowledgeRecords.Chunk eligible=mapper.evidence(cid(),c.getChunkId());
                if(eligible==null)continue;
                AgentLearningModels.Verification candidate=verifier.verify(e.getKind(),e.getContent(),eligible);
                if(Arrays.asList("ACTIVE","CONFLICT","REJECTED").contains(candidate.getStatus())) {
                    setVerification(e,candidate);return;
                }
            }
        }
        AgentLearningModels.Verification v=verifier.verify(e.getKind(),e.getContent(),evidence);
        setVerification(e,v);
    }
    private void setVerification(AgentLearningModels.Entry e,AgentLearningModels.Verification v) {
        e.setStatus(v.getStatus());e.setReason(v.getReason());e.setEvidenceChunkId(v.getEvidenceChunkId());e.setEvidenceVersion(v.getEvidenceVersion());
    }
    private void saveContent(AgentLearningModels.Entry e) {
        LocalDateTime now=LocalDateTime.now();String sourceId="aks_"+CommonUtil.createUuid();e.setSourceId(sourceId);
        AgentKnowledgeRecords.Source s=new AgentKnowledgeRecords.Source();s.setSourceId(sourceId);s.setScopeType("LEARNING");s.setSourceType("LEARNING");s.setCid(cid());
        s.setTitle("学习记录");s.setDomainCode("GENERAL");s.setAuthorityCode("LEARNING_CANDIDATE");s.setVersionNo(1);s.setActiveVersion(0);
        s.setLifecycleStatus("DRAFT");s.setIndexStatus("READY");s.setFileSize(0L);s.setChunkCount(1);s.setCreatedBy(uid());s.setUpdatedBy(uid());s.setCreatedAt(now);s.setUpdatedAt(now);
        knowledge.insertSource(s);
        AgentKnowledgeRecords.Chunk c=new AgentKnowledgeRecords.Chunk();c.setChunkId("akc_"+CommonUtil.createUuid());c.setSourceId(sourceId);c.setSourceVersion(1);c.setCid(cid());
        c.setChunkSeq(0);c.setContent(e.getContent());c.setContentHash(contentHash(e.getContent()));c.setStatus("ACTIVE");c.setTokenCount(e.getContent().length());c.setCreatedAt(now);c.setUpdatedAt(now);
        knowledge.insertChunk(c);
    }
    public List<Map<String,Object>> context(String query,String threadId,List<String> projectIds) {
        auth();if(!retrievalEnabled)return Collections.emptyList();
        List<AgentLearningModels.Entry> entries=mapper.candidates(cid(),uid()).stream().filter(this::visible)
            .filter(e -> requiredProjects(e).isEmpty()||projectIds!=null&&projectIds.containsAll(requiredProjects(e)))
            .filter(e -> "ACTIVE".equals(e.getStatus())&&validEvidence(e)||threadId!=null&&threadId.equals(e.getOriginThreadId()))
            .filter(e -> score(e,query,threadId)>0)
            .sorted(Comparator.comparingInt((AgentLearningModels.Entry e) -> score(e,query,threadId)).reversed())
            .limit(16).collect(Collectors.toList());
        List<Map<String,Object>> result=new ArrayList<>();int chars=0;
        for(AgentLearningModels.Entry e:entries) {
            Map<String,Object> v=view(e);boolean active="ACTIVE".equals(v.get("status"));
            String content=active?e.getContent():"历史原文（仅用于定位纠正，不作为事实）："+e.getContent();
            if(chars+content.length()>6000)continue;chars+=content.length();
            result.add(map("id",e.getId(),"version",e.getVersion(),"kind",e.getKind(),"status",v.get("status"),"content",content,"reason",v.get("reason")));
        }
        return result;
    }
    private int score(AgentLearningModels.Entry e,String q,String thread) {
        int n=Objects.equals(thread,e.getOriginThreadId())?50:0;if("USER_PREFERENCE".equals(e.getKind()))n+=30;
        if(q!=null)for(int i=0;i+1<q.length();i++)if(e.getContent().contains(q.substring(i,i+2)))n++;
        return n;
    }
    public boolean stillValid(List<Map<String,Object>> snapshot) {
        if(!retrievalEnabled)return snapshot==null||snapshot.isEmpty();
        if(snapshot==null)return true;
        for(Map<String,Object> ref:snapshot)if("ACTIVE".equals(ref.get("status"))) {
            AgentLearningModels.Entry e=mapper.get(cid(),String.valueOf(ref.get("id")));
            if(e==null||!visible(e)||!"ACTIVE".equals(e.getStatus())||!Objects.equals(e.getVersion(),ref.get("version"))||!validEvidence(e))return false;
        }
        return true;
    }
    /** Called within response finalization transaction: linearizes revocation and answer persistence. */
    public void lockAndValidate(List<Map<String,Object>> refs) {
        if(refs==null||refs.isEmpty())return;
        auth();
        List<String> ids=refs.stream().filter(m -> "ACTIVE".equals(m.get("status")))
            .map(m -> String.valueOf(m.get("id"))).sorted().distinct().collect(Collectors.toList());
        if(!retrievalEnabled)throw error("学习功能已停用，请重新查询","ALN409");
        for(String id:ids) {
            AgentLearningModels.Entry e=mapper.getForUpdate(cid(),id);
            Map<String,Object> ref=refs.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElse(Collections.emptyMap());
            if(!visible(e)||!"ACTIVE".equals(e.getStatus())||!Objects.equals(e.getVersion(),ref.get("version")))
                throw error("学习规则已更新或撤销，请按最新规则重新查询","ALN409");
            if(!blank(e.getEvidenceChunkId())&&!e.getEvidenceChunkId().startsWith("capability:")&&!e.getEvidenceChunkId().startsWith("entity:")) {
                AgentKnowledgeRecords.Chunk proof=mapper.evidenceForUpdate(cid(),e.getEvidenceChunkId());
                if(proof==null||!Objects.equals(proof.getSourceVersion(),e.getEvidenceVersion()))throw error("规则依据已更新，请重新查询","ALN409");
            } else if(!validEvidence(e))throw error("学习规则已失效，请重新查询","ALN409");
        }
    }
    public void recordUsage(List<Map<String,Object>> refs,String runId,String messageId,String type) {
        if(!retrievalEnabled||refs==null)return;
        for(Map<String,Object> ref:refs)if("ACTIVE".equals(ref.get("status")))mapper.usage(cid(),runId,messageId,String.valueOf(ref.get("id")),((Number)ref.get("version")).intValue(),type);
    }
    private void verifyAlias(AgentLearningModels.Entry e) {
        AgentLearningModels.Verification result=new AgentLearningModels.Verification();
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(?:记住[，,:： ]*)?(?:这里|我们|我说的)?(.{1,30}?)(?:就是|指的是|也叫|叫作)(.{1,60}?)[。！! ]*$").matcher(e.getContent());
        if(match.matches()) {
            String alias=match.group(1).trim(),name=match.group(2).trim();
            List<Map<String,Object>> candidates=mapper.aliasTargets(cid(),name);
            if(candidates.size()==1) {
                AgentKnowledgeRecords.Chunk proof=entityEvidence(candidates.get(0));
                result.setStatus("ACTIVE");result.setReason("已核对唯一登记对象，将“"+alias+"”作为你使用的称呼。");
                result.setEvidenceChunkId(proof.getChunkId());result.setEvidenceVersion(proof.getSourceVersion());
            }else result.setReason(candidates.isEmpty()?"未找到对应登记对象，请补充系统中的名称。":"发现多个同名对象，请补充具体人员或材料规格。");
        }else result.setReason("已记录这条称呼说明，请使用“小何指的是何建国”这样的明确对应关系以便核验。");
        setVerification(e,result);
    }
    private AgentKnowledgeRecords.Chunk entityEvidence(Map<String,Object> entity) {
        AgentKnowledgeRecords.Chunk proof=new AgentKnowledgeRecords.Chunk();
        proof.setChunkId("entity:"+entity.get("type")+":"+entity.get("id"));proof.setSourceTitle("当前企业登记对象");
        proof.setContent(String.valueOf(entity.get("name")));proof.setSourceVersion(Objects.hash(entity.get("type"),entity.get("id"),entity.get("name")));
        return proof;
    }
    private AgentKnowledgeRecords.Chunk evidence(String id) {
        if(blank(id))return null;
        if(id.startsWith("entity:")) {
            String[] parts=id.split(":",3);if(parts.length!=3)return null;
            for(Map<String,Object> entity:mapper.aliasEntity(cid(),parts[2])) {
                if(parts[1].equals(entity.get("type")))return entityEvidence(entity);
            }
            return null;
        }
        if(!id.startsWith("capability:"))return mapper.evidence(cid(),id);
        com.zjyz.agent.workspace.tool.AgentToolDescriptor d=catalog.find(id.substring("capability:".length()));
        if(d==null)return null;
        AgentKnowledgeRecords.Chunk c=new AgentKnowledgeRecords.Chunk();c.setChunkId(id);
        c.setSourceTitle("系统注册能力（使用权限与可用性仍按每次执行清单校验）");
        c.setContent(d.getDescription());c.setSourceVersion(Objects.hash(d.getToolCode(),d.getDescription(),d.getParameters(),d.getRiskLevel()));return c;
    }
    private boolean validEvidence(AgentLearningModels.Entry e) {
        if("USER_PREFERENCE".equals(e.getKind()))return "ACTIVE".equals(verifier.verify(e.getKind(),e.getContent(),null).getStatus());
        if(blank(e.getEvidenceChunkId()))return false;
        AgentKnowledgeRecords.Chunk c=evidence(e.getEvidenceChunkId());
        return c!=null&&Objects.equals(c.getSourceVersion(),e.getEvidenceVersion());
    }
    private boolean visible(AgentLearningModels.Entry e) {
        if(e==null||!cid().equals(e.getCid())||"PERSONAL".equals(e.getScopeType())&&!uid().equals(e.getOwnerUid()))return false;
        try {for(String id:requiredProjects(e))projects.queryProjectInfo(id);if(!blank(e.getProjectId()))projects.queryProjectInfo(e.getProjectId());return true;}catch(MyBizException ex){return false;}
    }
    private boolean editable(AgentLearningModels.Entry e) { return "PERSONAL".equals(e.getScopeType())?uid().equals(e.getOwnerUid()):owner(); }
    private AgentLearningModels.Entry require(String id,boolean edit) {
        if(blank(id))throw error("请指定要处理的学习记录","ALN400");AgentLearningModels.Entry e=mapper.get(cid(),id);
        if(!visible(e)||edit&&!editable(e))throw error("记录不存在或无权访问","ALN404");return e;
    }
    private AgentLearningModels.Entry locked(String id,Integer version) {
        require(id,true);AgentLearningModels.Entry e=mapper.getForUpdate(cid(),id);
        if(!visible(e)||!editable(e))throw error("记录不存在或无权访问","ALN404");
        if(version==null||!version.equals(e.getVersion()))throw error("记录已更新，请刷新后重试","ALN409");return e;
    }
    private void update(AgentLearningModels.Entry e) {if(mapper.update(e)!=1)throw error("记录已更新，请刷新后重试","ALN409");e.setVersion(e.getVersion()+1);}
    private void supersede(AgentLearningModels.Entry old,String key) {old.setStatus("SUPERSEDED");old.setReason("已由新规则替代。");update(old);record(old,key+":sup","SUPERSEDE");}
    private void record(AgentLearningModels.Entry e,String key,String action) {mapper.event(cid(),uid(),key,e.getId(),e.getVersion(),action,toJson(view(e)));}
    @SuppressWarnings("unchecked") private List<String> requiredProjects(AgentLearningModels.Entry e) {try{return blank(e.getRequiredProjects())?Collections.emptyList():json.readValue(e.getRequiredProjects(),List.class);}catch(Exception ex){throw error("记忆范围数据异常","ALN500");}}
    private boolean owner(){return knowledgeManagement&&"OWNER".equals(members.role(cid(),uid()));}
    private void readable(){auth();if(!writeEnabled&&!retrievalEnabled)throw error("学习功能尚未启用","ALN503");}
    private void writable(){auth();if(!writeEnabled)throw error("学习记录暂未开放保存；本次说明不会被声称为长期记忆","ALN503");}
    private void auth(){if(blank(cid())||blank(uid()))throw error("请先登录","AUTH401");}
    private String cid(){return AuthContext.getCid();} private String uid(){return AuthContext.getUid();}
    private String contentHash(String content) {
        try { byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String key(String s){
        try { byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest((cid()+":"+uid()+":"+text(s,160)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String text(String s,int max){if(blank(s)||s.length()>max)throw error("内容为空或超过允许长度","ALN400");return s.trim();}
    private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    private String toJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw error("记录序列化失败","ALN500");}}
    private static MyBizException error(String m,String c){return new MyBizException(m,c);}
    public static Map<String,Object> map(Object... args){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)m.put(String.valueOf(args[i]),args[i+1]);return m;}
}
