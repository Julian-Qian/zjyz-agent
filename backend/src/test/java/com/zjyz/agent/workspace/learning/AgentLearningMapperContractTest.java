package com.zjyz.agent.workspace.learning;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import com.zjyz.agent.dao.AgentKnowledgeMapper;
import static org.junit.jupiter.api.Assertions.*;
class AgentLearningMapperContractTest {
    @Test void legacyKnowledgeReadsExcludeLearningSources() throws Exception {
        for(String name:new String[]{"selectSource","selectSources","countSources","selectPublishedChunk","selectPublishedChunksByKeyword"}) {
            java.lang.reflect.Method method=java.util.Arrays.stream(AgentKnowledgeMapper.class.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(String.join(" ",method.getAnnotation(Select.class).value()).contains("source_type<>'LEARNING'"),name);
        }
    }
    @Test void proofCannotUseLearningItselfAndMustMatchLiveSourceVersion() throws Exception {
        String sql=String.join(" ",AgentLearningMapper.class.getMethod("evidence",String.class,String.class).getAnnotation(Select.class).value());
        assertTrue(sql.contains("s.source_type<>'LEARNING'"));assertTrue(sql.contains("c.source_version=s.version_no"));assertTrue(sql.contains("s.project_id IS NULL"));
    }
    @Test void allMapperStatementsParse() {
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();config.addMapper(AgentLearningMapper.class);
        assertTrue(config.hasStatement(AgentLearningMapper.class.getName()+".getForUpdate"));assertTrue(config.hasStatement(AgentLearningMapper.class.getName()+".evidenceForUpdate"));
    }
}
