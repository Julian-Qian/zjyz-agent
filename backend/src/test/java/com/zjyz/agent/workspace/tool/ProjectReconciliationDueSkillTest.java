package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementDocumentEntity;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectReconciliationDueSkillTest {

    @Test
    @SuppressWarnings("unchecked")
    void appliesMinUnreconciledDaysInsteadOfPaymentOverdueDays() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ContractMapper contractMapper = mock(ContractMapper.class);
        SettlementDocumentMapper settlementMapper = mock(SettlementDocumentMapper.class);
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p-1");
        project.setProjectName("未对账项目");
        project.setProjectBusinessType("rent_out");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));
        SettlementDocumentEntity settlement = new SettlementDocumentEntity();
        settlement.setProjectId("p-1");
        settlement.setEndDate("2026-06-20");
        settlement.setType("3");
        when(settlementMapper.selectList(any())).thenReturn(Collections.singletonList(settlement));
        ContractEntity contract = new ContractEntity();
        contract.setProjectId("p-1");
        contract.setStartDate("2026-01-01");
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(contract));

        AgentSkillExecution execution = new ProjectReconciliationDueSkill(projectMapper, contractMapper,
                settlementMapper, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-07-31\",\"minUnreconciledDays\":30}",
                "截至7月31日超过30天未对账", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals(41L, items.get(0).get("unreconciledDays"));
        assertEquals(30, card.get("minUnreconciledDays"));
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace value = new AgentRuntimeRecords.Workspace();
        value.setCid("cid-1");
        value.setScopeType("TENANT");
        value.setSelectionMode("ALL");
        value.setProjectIds(Collections.emptyList());
        return value;
    }
}
