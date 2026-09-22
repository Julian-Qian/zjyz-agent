package com.zjyz.agent.workspace.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.common.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridAgentKnowledgeRetrieverTest {
    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void disabledHybridModeDoesNotTouchDynamicKnowledgeTablesOrExternalServices() {
        AgentKnowledgeMapper mapper = mock(AgentKnowledgeMapper.class);
        AgentEmbeddingClient embedding = mock(AgentEmbeddingClient.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        HybridAgentKnowledgeRetriever retriever = new HybridAgentKnowledgeRetriever(
                new BuiltInAgentKnowledgeRetriever(new ObjectMapper()), mapper, embedding, vectorStore);
        ReflectionTestUtils.setField(retriever, "hybridEnabled", false);
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setUserGoal("某月应该对账是什么意思");
        frame.setDomain("SETTLEMENT");

        AgentKnowledgeContext result = retriever.retrieve(frame, 5);

        assertEquals("BUILT_IN_CURATED", result.getMode());
        assertFalse(result.getEntries().isEmpty());
        verifyNoInteractions(mapper, embedding, vectorStore);
    }

    @Test
    void dropsVectorHitWhenMysqlPostValidationRejectsIt() {
        AgentKnowledgeMapper mapper = mock(AgentKnowledgeMapper.class);
        AgentEmbeddingClient embedding = mock(AgentEmbeddingClient.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        HybridAgentKnowledgeRetriever retriever = new HybridAgentKnowledgeRetriever(
                new BuiltInAgentKnowledgeRetriever(new ObjectMapper()), mapper, embedding, vectorStore);
        ReflectionTestUtils.setField(retriever, "hybridEnabled", true);
        AuthContext.set("user-a", "tenant-a");
        when(embedding.isConfigured()).thenReturn(true);
        when(vectorStore.isEnabledAndConfigured()).thenReturn(true);
        when(embedding.embedQuery("企业内部特殊退租流程是什么"))
                .thenReturn(Arrays.asList(0.1d, 0.2d));
        AgentKnowledgeRecords.SearchHit wrongTenantHit = new AgentKnowledgeRecords.SearchHit();
        wrongTenantHit.setSourceId("source-other-tenant");
        wrongTenantHit.setSourceVersion(1);
        wrongTenantHit.setChunkId("chunk-other-tenant");
        wrongTenantHit.setScore(0.99d);
        when(mapper.selectPublishedVectorPointIds("tenant-a","TENANT","",5001)).thenReturn(Collections.singletonList("authorized-point"));
        when(vectorStore.searchEligible(Arrays.asList(0.1d, 0.2d),Collections.singletonList("authorized-point"),"TENANT","tenant-a",12))
                .thenReturn(Collections.singletonList(wrongTenantHit));
        when(mapper.selectPublishedChunk("tenant-a", "chunk-other-tenant")).thenReturn(null);
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setUserGoal("企业内部特殊退租流程是什么");
        frame.setDomain("DOCUMENT");

        AgentKnowledgeContext result = retriever.retrieve(frame, 5);

        assertTrue(result.getEntries().stream().noneMatch(item -> "source-other-tenant".equals(item.getSourceId())));
    }

    @Test
    void degradesWhenEmbeddingServiceFails() {
        AgentKnowledgeMapper mapper = mock(AgentKnowledgeMapper.class);
        AgentEmbeddingClient embedding = mock(AgentEmbeddingClient.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        HybridAgentKnowledgeRetriever retriever = new HybridAgentKnowledgeRetriever(
                new BuiltInAgentKnowledgeRetriever(new ObjectMapper()), mapper, embedding, vectorStore);
        ReflectionTestUtils.setField(retriever, "hybridEnabled", true);
        when(embedding.isConfigured()).thenReturn(true);
        when(vectorStore.isEnabledAndConfigured()).thenReturn(true);
        when(embedding.embedQuery("材料库存怎么看")).thenThrow(new RuntimeException("provider unavailable"));
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setUserGoal("材料库存怎么看");
        frame.setDomain("INVENTORY");

        AgentKnowledgeContext result = retriever.retrieve(frame, 5);

        assertTrue(result.isDegraded());
        assertFalse(result.getWarnings().isEmpty());
    }
    @org.junit.jupiter.api.Test
    void versionedPublishedChunksRequireMatchingProductVersion() {
        HybridAgentKnowledgeRetriever retriever = new HybridAgentKnowledgeRetriever(null,null,null,null);
        AgentKnowledgeRecords.Chunk chunk = new AgentKnowledgeRecords.Chunk();
        chunk.setSourceTags("归还,product-version:1.0.36");
        org.junit.jupiter.api.Assertions.assertFalse(retriever.versionApplies(chunk));
        org.springframework.test.util.ReflectionTestUtils.setField(retriever,"productVersion","1.0.36");
        org.junit.jupiter.api.Assertions.assertTrue(retriever.versionApplies(chunk));
        chunk.setSourceTags("product-version:1.0.35");
        org.junit.jupiter.api.Assertions.assertFalse(retriever.versionApplies(chunk));
    }
    @org.junit.jupiter.api.Test
    void currentVectorAllowListPreventsHistoricalPointsFromOccupyingTopK() {
        AgentKnowledgeMapper mapper=mock(AgentKnowledgeMapper.class);
        AgentEmbeddingClient embedding=mock(AgentEmbeddingClient.class);QdrantVectorStore vectors=mock(QdrantVectorStore.class);
        BuiltInAgentKnowledgeRetriever builtin=mock(BuiltInAgentKnowledgeRetriever.class);
        when(builtin.retrieve(any(),anyInt())).thenReturn(new AgentKnowledgeContext());
        HybridAgentKnowledgeRetriever r=new HybridAgentKnowledgeRetriever(builtin,mapper,embedding,vectors);
        ReflectionTestUtils.setField(r,"hybridEnabled",true);AuthContext.set("u","c");
        when(embedding.isConfigured()).thenReturn(true);when(vectors.isEnabledAndConfigured()).thenReturn(true);
        List<Double> vector=Arrays.asList(0.1d,0.2d);when(embedding.embedQuery(anyString())).thenReturn(vector);
        when(mapper.selectPublishedVectorPointIds("c","PLATFORM","",5001)).thenReturn(Collections.singletonList("current-point"));
        AgentKnowledgeRecords.SearchHit hit=new AgentKnowledgeRecords.SearchHit();hit.setChunkId("chunk-v13");hit.setSourceId("s");hit.setSourceVersion(13);hit.setScore(0.95d);
        when(vectors.searchEligible(vector,Collections.singletonList("current-point"),"PLATFORM","c",12)).thenReturn(Collections.singletonList(hit));
        AgentKnowledgeRecords.Chunk chunk=new AgentKnowledgeRecords.Chunk();chunk.setChunkId("chunk-v13");chunk.setSourceId("s");chunk.setSourceVersion(13);chunk.setSourceTitle("归还");chunk.setContent("当前有效说明");
        when(mapper.selectPublishedChunk("c","chunk-v13")).thenReturn(chunk);
        AgentTaskFrame frame=new AgentTaskFrame();frame.setUserGoal("东西送回怎么登记");
        assertTrue(r.retrieve(frame,6).getEntries().stream().anyMatch(e->"chunk-v13".equals(e.getChunkId())));
        verify(vectors).searchEligible(vector,Collections.singletonList("current-point"),"PLATFORM","c",12);
        verify(vectors,never()).searchPlatform(anyList(),anyInt());
    }
    @org.junit.jupiter.api.Test
    void precisePublishedHelpOutranksSixWeakBuiltIns() {
        AgentKnowledgeMapper mapper=mock(AgentKnowledgeMapper.class);BuiltInAgentKnowledgeRetriever builtin=mock(BuiltInAgentKnowledgeRetriever.class);
        AgentKnowledgeContext base=new AgentKnowledgeContext();List<AgentKnowledgeEntry> entries=new ArrayList<>();
        for(int i=0;i<6;i++){AgentKnowledgeEntry e=new AgentKnowledgeEntry();e.setId("b"+i);e.setTitle("材料库存");e.setContent("材料资料说明");e.setAuthority("RUNTIME_CODE");entries.add(e);}base.setEntries(entries);
        when(builtin.retrieve(any(),anyInt())).thenReturn(base);
        HybridAgentKnowledgeRetriever r=new HybridAgentKnowledgeRetriever(builtin,mapper,mock(AgentEmbeddingClient.class),mock(QdrantVectorStore.class));
        ReflectionTestUtils.setField(r,"hybridEnabled",true);
        AgentKnowledgeRecords.Chunk precise=new AgentKnowledgeRecords.Chunk();precise.setChunkId("precise");precise.setSourceTitle("材料归还记录");precise.setContent("材料归还记录");precise.setAuthorityCode("PUBLISHED_HELP");
        when(mapper.selectPublishedChunksByKeyword(any(),anyString(),anyInt())).thenReturn(Collections.singletonList(precise));
        AgentTaskFrame frame=new AgentTaskFrame();frame.setUserGoal("材料归还记录");
        assertEquals("precise",r.retrieve(frame,6).getEntries().get(0).getChunkId());
    }
}
