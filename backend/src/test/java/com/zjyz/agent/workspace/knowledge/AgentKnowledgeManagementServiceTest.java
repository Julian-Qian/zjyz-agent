package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.membership.service.TenantMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentKnowledgeManagementServiceTest {
    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void financeMemberCannotUseManagementApis() {
        TenantMemberService members = mock(TenantMemberService.class);
        AgentKnowledgeManagementService service = service(members);
        ReflectionTestUtils.setField(service, "managementEnabled", true);
        AuthContext.set("finance-1", "tenant-1");
        when(members.role("tenant-1", "finance-1")).thenReturn("FINANCE");

        assertFalse(service.manageAllowed());
        assertThrows(MyBizException.class, () -> service.list(1, 20, null, null, null, null));
    }

    @Test
    void ownerCanSeeManagementCapability() {
        TenantMemberService members = mock(TenantMemberService.class);
        AgentKnowledgeManagementService service = service(members);
        ReflectionTestUtils.setField(service, "managementEnabled", true);
        AuthContext.set("owner-1", "tenant-1");
        when(members.role("tenant-1", "owner-1")).thenReturn("OWNER");

        assertTrue(service.manageAllowed());
    }

    private AgentKnowledgeManagementService service(TenantMemberService members) {
        return new AgentKnowledgeManagementService(
                mock(AgentKnowledgeMapper.class),
                mock(AgentKnowledgeObjectStorage.class),
                mock(AgentKnowledgeDocumentParser.class),
                mock(HybridAgentKnowledgeRetriever.class),
                members,
                mock(QdrantVectorStore.class),
                mock(AgentEmbeddingClient.class));
    }
}
