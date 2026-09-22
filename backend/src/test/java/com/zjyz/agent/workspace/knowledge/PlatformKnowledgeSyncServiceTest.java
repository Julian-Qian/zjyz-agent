package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.service.HelpCenterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformKnowledgeSyncServiceTest {
    final AgentKnowledgeMapper mapper=mock(AgentKnowledgeMapper.class);
    final BuiltInAgentKnowledgeRetriever builtin=mock(BuiltInAgentKnowledgeRetriever.class);
    final HelpCenterService help=mock(HelpCenterService.class);
    final AgentEmbeddingClient embedding=mock(AgentEmbeddingClient.class);
    final QdrantVectorStore vector=mock(QdrantVectorStore.class);
    final PlatformKnowledgeSyncService sync=new PlatformKnowledgeSyncService(builtin,help,mapper,new AgentKnowledgeDocumentParser(),embedding,vector);
    AgentKnowledgeRecords.Source source() {
        AgentKnowledgeRecords.Source source=new AgentKnowledgeRecords.Source();
        source.setSourceId("source"); source.setVersionNo(3); source.setIndexStatus("READY");
        source.setLifecycleStatus("PUBLISHED"); source.setOriginalFileName("help.md");
        source.setParserVersion(AgentKnowledgeDocumentParser.PARSER_VERSION);
        source.setContentChecksum(sync.fingerprint("标题","正文","HELP","PUBLISHED_HELP","归还","help:1"));
        return source;
    }
    @Test void identicalPublishedContentDoesNotMutate() {
        when(mapper.selectPlatformSource("HELP_ARTICLE","1")).thenReturn(source());
        sync.syncOne("HELP_ARTICLE","1","标题","正文","HELP","PUBLISHED_HELP","归还","help:1");
        verify(mapper,never()).updatePlatformSourceForSync(any()); verify(mapper,never()).insertChunk(any());
    }
    @Test void metadataChangeCreatesNewVersionWithoutDestroyingOldCitations() {
        AgentKnowledgeRecords.Source source=source();
        when(mapper.selectPlatformSource("HELP_ARTICLE","1")).thenReturn(source);
        when(mapper.updatePlatformSourceForSync(any())).thenReturn(1);
        sync.syncOne("HELP_ARTICLE","1","新标题","正文","HELP","PUBLISHED_HELP","归还","help:1");
        assertEquals(4,source.getVersionNo());
        verify(mapper,never()).deleteChunks(anyString(),anyInt()); verify(vector,never()).deleteSource(any());
        verify(mapper).insertChunk(argThat(c -> c.getSourceVersion()==4 && c.getMetadataJson().contains("新标题")));
        verify(mapper).markSourceReady(eq("source"),eq(4),anyString(),isNull(),eq(0),eq(1),any());
    }
    @Test void losingCompareAndSetCannotWriteChunks() {
        when(mapper.selectPlatformSource("HELP_ARTICLE","1")).thenReturn(source());
        sync.syncOne("HELP_ARTICLE","1","新标题","正文","HELP","PUBLISHED_HELP","归还","help:1");
        verify(mapper,never()).insertChunk(any());
    }
    @Test void unavailableHelpInventoryNeverRetiresSources() {
        ReflectionTestUtils.setField(sync,"hybridEnabled",true);
        when(builtin.trustedEntries()).thenReturn(Collections.emptyList());
        when(help.queryArticleList(null,null,"all","all")).thenReturn(null);
        sync.sync();
        verify(mapper,never()).selectPublishedPlatformSources("HELP_ARTICLE");
    }
    @Test void authoritativeEmptyInventoryRetiresEvenWithoutVectorService() {
        ReflectionTestUtils.setField(sync,"hybridEnabled",true);
        when(builtin.trustedEntries()).thenReturn(Collections.emptyList());
        when(help.queryArticleList(null,null,"all","all")).thenReturn(Collections.emptyList());
        AgentKnowledgeRecords.Source source=source(); source.setExternalRef("1");
        when(mapper.selectPublishedPlatformSources("HELP_ARTICLE")).thenReturn(Collections.singletonList(source));
        sync.sync(); verify(mapper).retirePlatformSource(eq("source"),eq(3),any());
    }
    @Test void retrievalRequiresCurrentVersionAndPublishedUpstreamArticle() throws Exception {
        String sql=String.join("", AgentKnowledgeMapper.class.getMethod("selectPublishedChunk",String.class,String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(sql.contains("c.source_version=s.version_no"));
        assertTrue(sql.contains("h.status=1 AND h.is_deleted=0"));
        assertTrue(sql.contains("SUBSTRING_INDEX(s.description,';helpUpdatedAt=',-1)"));
        assertFalse(sql.contains("h.update_time<=s.updated_at"));
    }
    @Test void upstreamTimestampChangeAdvancesSnapshotEvenWhenBodyUnchanged() {
        AgentKnowledgeRecords.Source source=source();
        source.setContentChecksum(sync.fingerprint("标题","正文","HELP","PUBLISHED_HELP","归还","help:1;helpUpdatedAt=2026-09-19 10:00:00"));
        when(mapper.selectPlatformSource("HELP_ARTICLE","1")).thenReturn(source);
        when(mapper.updatePlatformSourceForSync(any())).thenReturn(1);
        sync.syncOne("HELP_ARTICLE","1","标题","正文","HELP","PUBLISHED_HELP","归还","help:1;helpUpdatedAt=2026-09-19 10:01:00");
        assertEquals(4,source.getVersionNo());
        verify(mapper).updatePlatformSourceForSync(any());
    }
}
