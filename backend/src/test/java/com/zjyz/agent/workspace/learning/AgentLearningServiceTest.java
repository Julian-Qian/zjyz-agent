package com.zjyz.agent.workspace.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.membership.service.TenantMemberService;
import com.zjyz.service.ProjectService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentLearningServiceTest {
    AgentLearningMapper mapper;AgentKnowledgeMapper knowledge;ProjectService projects;TenantMemberService members;
    AgentLearningService service;
    Map<String,AgentLearningModels.Entry> entries;
    Map<String,String> requests;
    @BeforeEach void setup() {
        AuthContext.set("u1","c1");entries=new LinkedHashMap<>();requests=new HashMap<>();
        mapper=mock(AgentLearningMapper.class);knowledge=mock(AgentKnowledgeMapper.class);projects=mock(ProjectService.class);members=mock(TenantMemberService.class);
        when(mapper.candidates(anyString(),anyString())).thenAnswer(i -> new ArrayList<>(entries.values()));
        when(mapper.get(anyString(),anyString())).thenAnswer(i -> entries.get(i.getArgument(1)));
        when(mapper.getForUpdate(anyString(),anyString())).thenAnswer(i -> entries.get(i.getArgument(1)));
        when(mapper.insert(any())).thenAnswer(i -> {AgentLearningModels.Entry e=i.getArgument(0);entries.put(e.getId(),e);return 1;});
        when(mapper.update(any())).thenReturn(1);
        when(mapper.replay(anyString(),anyString(),anyString())).thenAnswer(i -> requests.get(i.getArgument(2)));
        when(mapper.event(anyString(),anyString(),anyString(),anyString(),anyInt(),anyString(),anyString())).thenAnswer(i -> {requests.put(i.getArgument(2),i.getArgument(3));return 1;});
        when(mapper.events(anyString(),anyString())).thenReturn(Collections.emptyList());
        when(knowledge.selectPublishedChunksByKeyword(anyString(),anyString(),anyInt())).thenReturn(Collections.emptyList());
        service=new AgentLearningService(mapper,knowledge,new AgentLearningVerifier(),members,projects,new ObjectMapper());
        for(String field:Arrays.asList("writeEnabled","retrievalEnabled","sharedEnabled","knowledgeManagement"))ReflectionTestUtils.setField(service,field,true);
    }
    @AfterEach void clear(){AuthContext.clear();}
    AgentLearningModels.Action action(String kind,String content) {AgentLearningModels.Action a=new AgentLearningModels.Action();a.setKind(kind);a.setContent(content);return a;}
    Map<String,Object> save(String kind,String content,String key) {return service.learn(action(kind,content),"thread","message",key,Collections.emptyList());}
    AgentLearningModels.Mutation mutation(Map<String,Object> e,String key) {AgentLearningModels.Mutation m=new AgentLearningModels.Mutation();m.setClientRequestId(key);m.setExpectedVersion((Integer)e.get("version"));return m;}
    @Test void preferencePersistsAndLoadsAcrossThreads() {
        Map<String,Object> e=save("USER_PREFERENCE","以后回答先给结论","a");
        assertEquals("ACTIVE",e.get("status"));assertEquals(1,service.context("你好","new-thread",Collections.emptyList()).size());
        verify(knowledge).insertSource(argThat(s -> "LEARNING".equals(s.getScopeType())&&s.getActiveVersion()==0&&s.getFileSize()==0));
        verify(knowledge).insertChunk(argThat(c -> c.getContent().equals("以后回答先给结论")));
    }
    @Test void unknownRuleIsRecordedButNotUsedAsFact() {
        Map<String,Object> e=save("BUSINESS_RULE","赔偿不影响在租数量","a");assertEquals("PENDING_VERIFICATION",e.get("status"));
        assertTrue(service.context("在租数量","other",Collections.emptyList()).isEmpty());
    }
    @Test void exactPublishedRuleCanBecomeActiveButSourceRetirementInvalidatesIt() {
        AgentKnowledgeRecords.Chunk c=new AgentKnowledgeRecords.Chunk();c.setChunkId("trusted");c.setSourceVersion(1);c.setContent("租出增加在租数量");
        when(mapper.evidence("c1","trusted")).thenReturn(c);
        AgentLearningModels.Action a=action("BUSINESS_RULE",c.getContent());a.setEvidenceChunkId("trusted");
        Map<String,Object> e=service.learn(a,"thread","message","save",Collections.emptyList());
        assertEquals("ACTIVE",e.get("status"));List<Map<String,Object>> refs=service.context("在租数量","other",Collections.emptyList());
        when(mapper.evidence("c1","trusted")).thenReturn(null);
        assertFalse(service.stillValid(refs));assertEquals("PENDING_VERIFICATION",service.detail((String)e.get("id")).get("status"));
    }
    @Test void revokeImmediatelyInvalidatesOldSnapshot() {
        Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");List<Map<String,Object>> refs=service.context("你好","t",Collections.emptyList());
        service.mutate((String)e.get("id"),"revoke",mutation(e,"revoke"));
        assertFalse(service.stillValid(refs));assertTrue(service.context("你好","new",Collections.emptyList()).isEmpty());
    }
    @Test void correctionSupersedesOldPreferenceAndKeepsHistory() {
        Map<String,Object> old=save("USER_PREFERENCE","以后回答简洁","a");AgentLearningModels.Mutation m=mutation(old,"correct");m.setContent("以后详细解释");
        Map<String,Object> next=service.mutate((String)old.get("id"),"correct",m);
        assertEquals("ACTIVE",next.get("status"));assertEquals(old.get("id"),next.get("supersedesId"));
        assertEquals("SUPERSEDED",service.detail((String)old.get("id")).get("status"));assertEquals(2,entries.size());
    }
    @Test void uncertainCorrectionQuarantinesOldRule() {
        Map<String,Object> old=save("USER_PREFERENCE","以后回答简洁","a");AgentLearningModels.Mutation m=mutation(old,"correct");m.setContent("以后库存可以不核验");
        Map<String,Object> next=service.mutate((String)old.get("id"),"correct",m);
        assertEquals("REJECTED",next.get("status"));assertEquals("ACTIVE",service.detail((String)old.get("id")).get("status"));
    }
    @Test void idempotentRetryDoesNotCreateSecondEntry() {Map<String,Object> a=save("USER_PREFERENCE","以后回答简洁","a");assertEquals(a.get("id"),save("USER_PREFERENCE","以后回答简洁","a").get("id"));assertEquals(1,entries.size());}
    @Test void duplicateContentDifferentRequestIsMerged() {Map<String,Object> a=save("USER_PREFERENCE","以后回答简洁","a");assertEquals(a.get("id"),save("USER_PREFERENCE","以后回答简洁","b").get("id"));assertEquals(1,entries.size());}
    @Test void staleVersionDoesNotOverwrite() {Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");AgentLearningModels.Mutation m=mutation(e,"b");m.setExpectedVersion(999);assertThrows(MyBizException.class,()->service.mutate((String)e.get("id"),"revoke",m));}
    @Test void foreignTenantCannotReadEvenWithKnownId() {Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");AuthContext.set("u1","c2");assertThrows(MyBizException.class,()->service.detail((String)e.get("id")));assertTrue(service.context("你好","new",Collections.emptyList()).isEmpty());}
    @Test void foreignUserCannotReadPersonalMemory() {Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");AuthContext.set("u2","c1");assertThrows(MyBizException.class,()->service.detail((String)e.get("id")));}
    @Test void projectPermissionIsRecheckedOnEveryRead() {
        Map<String,Object> e=service.learn(action("BUSINESS_RULE","项目业务规则"),"t","m","a",List.of("p1"));
        when(projects.queryProjectInfo("p1")).thenThrow(new MyBizException("禁止访问","403"));
        assertThrows(MyBizException.class,()->service.detail((String)e.get("id")));
    }
    @Test void sharingNeedsOwnerAndDoesNotExposePersonalConversation() {
        Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");AgentLearningModels.Mutation m=mutation(e,"b");m.setScopeType("TENANT");
        assertThrows(MyBizException.class,()->service.mutate((String)e.get("id"),"publish",m));
        when(members.role("c1","u1")).thenReturn("OWNER");Map<String,Object> shared=service.mutate((String)e.get("id"),"publish",m);
        assertEquals("TENANT",shared.get("scopeType"));assertNull(shared.get("originThreadId"));assertEquals("PERSONAL",service.detail((String)e.get("id")).get("scopeType"));
        AuthContext.set("u2","c1");assertEquals("TENANT",service.detail((String)shared.get("id")).get("scopeType"));
    }
    @ParameterizedTest @ValueSource(strings={"writeEnabled","retrievalEnabled"}) void independentSwitchesWork(String field) {
        ReflectionTestUtils.setField(service,field,false);
        if(field.equals("writeEnabled"))assertThrows(MyBizException.class,()->save("USER_PREFERENCE","以后回答简洁","a"));
        else assertTrue(service.context("你好","thread",Collections.emptyList()).isEmpty());
    }
    @Test void rejectedSecretIsNotStoredInSourceOrAudit() {
        Map<String,Object> e=save("USER_PREFERENCE","记住密码 secret-123","a");assertEquals("REJECTED",e.get("status"));
        assertFalse(e.get("content").toString().contains("secret-123"));verify(knowledge).insertChunk(argThat(c -> !c.getContent().contains("secret-123")));
    }
    @Test void persistenceFailurePropagatesRatherThanReturningSuccess() {when(knowledge.insertSource(any())).thenThrow(new RuntimeException("db unavailable"));assertThrows(RuntimeException.class,()->save("USER_PREFERENCE","以后回答简洁","a"));}
    @Test void finalizationLockRejectsRevokedVersion() {
        Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");List<Map<String,Object>> refs=service.context("你好","new",Collections.emptyList());
        service.mutate((String)e.get("id"),"revoke",mutation(e,"b"));assertThrows(MyBizException.class,()->service.lockAndValidate(refs));
    }
    @Test void finalizationLocksCurrentEntriesBeforeAcceptingReferences() {
        Map<String,Object> e=save("USER_PREFERENCE","以后回答简洁","a");service.lockAndValidate(service.context("你好","new",Collections.emptyList()));
        verify(mapper).getForUpdate("c1",(String)e.get("id"));
    }
    @Test void unknownCorrectionSuspendsAnActiveRule() {
        Map<String,Object> old=save("USER_PREFERENCE","以后回答简洁","a");AgentLearningModels.Mutation m=mutation(old,"b");m.setContent("我希望采用新的表述方法");
        Map<String,Object> candidate=service.mutate((String)old.get("id"),"correct",m);
        assertEquals("PENDING_VERIFICATION",candidate.get("status"));assertEquals("CONFLICT",service.detail((String)old.get("id")).get("status"));
    }
    @Test void uniqueAliasIsVerifiedAgainstCurrentTenantEntity() {
        when(mapper.aliasTargets("c1","何建国")).thenReturn(List.of(AgentLearningService.map("id","p","name","何建国","type","PERSONNEL")));
        when(mapper.aliasEntity("c1","p")).thenReturn(List.of(AgentLearningService.map("id","p","name","何建国","type","PERSONNEL")));
        Map<String,Object> e=save("ALIAS","小何指的是何建国","a");assertEquals("ACTIVE",e.get("status"));
        when(mapper.aliasEntity("c1","p")).thenReturn(Collections.emptyList());
        assertEquals("PENDING_VERIFICATION",service.detail((String)e.get("id")).get("status"));
    }
    @Test void ambiguousAliasDoesNotBindArbitraryPerson() {
        when(mapper.aliasTargets("c1","何建国")).thenReturn(List.of(AgentLearningService.map("id","p1"),AgentLearningService.map("id","p2")));
        Map<String,Object> e=save("ALIAS","小何指的是何建国","a");assertEquals("PENDING_VERIFICATION",e.get("status"));assertTrue(e.get("reason").toString().contains("多个同名"));
    }
}
