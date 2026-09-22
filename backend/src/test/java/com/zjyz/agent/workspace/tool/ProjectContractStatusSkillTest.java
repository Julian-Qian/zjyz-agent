package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectContractStatusSkillTest {

    @Test
    void filtersExpiredContractsAsOfRequestedDate() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ContractMapper contractMapper = mock(ContractMapper.class);
        ProjectEntity expiredProject = project("p-1", "已到期项目");
        ProjectEntity activeProject = project("p-2", "有效项目");
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(expiredProject, activeProject));
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(
                contract("c-2", "p-2", "2026-12-31"),
                contract("c-1", "p-1", "2026-06-30")));

        AgentSkillExecution execution = new ProjectContractStatusSkill(projectMapper, contractMapper, new ObjectMapper())
                .execute("{\"asOfDate\":\"2026-08-24\",\"status\":\"EXPIRED\"}", "查询到期合同", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals(1, card.get("total"));
        assertTrue(execution.getAnswer().contains("已到期项目"));
    }

    @Test
    void filtersContractsWhoseEndDateFallsInRequestedMonth() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ContractMapper contractMapper = mock(ContractMapper.class);
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(
                project("p-1", "八月到期项目"),
                project("p-2", "七月到期项目"),
                project("p-3", "九月到期项目")));
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(
                contract("c-3", "p-3", "2026-09-01"),
                contract("c-2", "p-2", "2026-07-31"),
                contract("c-1", "p-1", "2026-08-31")));

        AgentSkillExecution execution = new ProjectContractStatusSkill(projectMapper, contractMapper, new ObjectMapper())
                .execute("{\"asOfDate\":\"2026-08-28\"}",
                        "这个月有哪些合同到期", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals(1, card.get("total"));
        assertEquals("2026-08-01", card.get("expiryStartDate"));
        assertEquals("2026-08-31", card.get("expiryEndDate"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals("八月到期项目", items.get(0).get("projectName"));
        assertEquals("2026年8月有1个合同到期：八月到期项目（8月31日到期）。", execution.getAnswer());
        assertFalse(execution.getAnswer().contains("ACTIVE"));
        assertFalse(execution.getAnswer().contains("contractEndDate"));
    }

    private ProjectEntity project(String id, String name) {
        ProjectEntity value = new ProjectEntity();
        value.setProjectId(id);
        value.setProjectName(name);
        value.setProjectBusinessType("rent_out");
        value.setProjectStatusFlag("0");
        return value;
    }

    private ContractEntity contract(String id, String projectId, String endDate) {
        ContractEntity value = new ContractEntity();
        value.setContractId(id);
        value.setProjectId(projectId);
        value.setStartDate("2026-01-01");
        value.setEndDate(endDate);
        value.setCreateDate("2026-01-01");
        return value;
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
