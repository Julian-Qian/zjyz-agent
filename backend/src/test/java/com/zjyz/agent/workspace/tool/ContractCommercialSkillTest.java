package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.ProjectMaterialPricingMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.ProjectMaterialPricingEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractCommercialSkillTest {

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ContractMapper contractMapper = mock(ContractMapper.class);
    private final ProjectMaterialPricingMapper pricingMapper = mock(ProjectMaterialPricingMapper.class);
    private final ContractCommercialSkill skill = new ContractCommercialSkill(
            projectMapper, contractMapper, pricingMapper, new ObjectMapper());

    @Test
    void detectsMissingContractFieldsAndCrossProjectPriceGaps() {
        ProjectEntity p1 = project("p1", "项目一");
        ProjectEntity p2 = project("p2", "项目二");
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(p1, p2));
        // p1 有合同但缺结算周期与税率；p2 无合同。
        ContractEntity contract = new ContractEntity();
        contract.setContractName("合同A");
        contract.setEndDate("2026-12-31");
        when(contractMapper.selectList(any()))
                .thenReturn(Collections.singletonList(contract))
                .thenReturn(Collections.emptyList());
        when(pricingMapper.selectList(any())).thenReturn(Arrays.asList(
                pricing("p1", "钢管", "48-3", "根", "1.20"),
                pricing("p2", "钢管", "48-3", "根", "1.50"),
                pricing("p1", "钢管", "48-3", "吨", "9.00"),
                pricing("p2", "扣件", null, "只", "0.10")));

        AgentSkillExecution execution = skill.execute(null, "合同字段完整吗，有没有价差", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals("contract-commercial-analytics", card.get("type"));
        Map<String, Object> completeness = (Map<String, Object>) card.get("completeness");
        assertEquals(2, completeness.get("totalCount"));
        List<Map<String, Object>> completenessItems = (List<Map<String, Object>>) completeness.get("items");
        assertTrue(((List<String>) completenessItems.get(0).get("issues")).contains("NO_CONTRACT")
                || ((List<String>) completenessItems.get(1).get("issues")).contains("NO_CONTRACT"));

        Map<String, Object> priceComparison = (Map<String, Object>) card.get("priceComparison");
        assertEquals(1, priceComparison.get("totalCount"), "不同计数单位不得进入同一比较组");
        List<Map<String, Object>> priceItems = (List<Map<String, Object>>) priceComparison.get("items");
        assertEquals("根", priceItems.get(0).get("countingUnit"));
        assertEquals(new BigDecimal("1.20"), priceItems.get(0).get("minDailyRent"));
        assertEquals(new BigDecimal("1.50"), priceItems.get(0).get("maxDailyRent"));
        assertTrue(String.valueOf(card.get("scopeNote")).contains("执行价"));
        assertTrue(execution.getAnswer().contains("2 个项目存在缺失"));
    }

    @Test
    void cleanContractsAndUniformPricesReportHonestly() {
        ProjectEntity p1 = project("p1", "项目一");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(p1));
        ContractEntity contract = new ContractEntity();
        contract.setContractName("合同A");
        contract.setEndDate("2026-12-31");
        contract.setReconciliationPeriod(30);
        contract.setTaxRate(0.09d);
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(contract));
        when(pricingMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentSkillExecution execution = skill.execute(null, "合同商业分析", workspace());
        Map<String, Object> card = execution.getCards().get(0);
        assertEquals(0, ((Map<String, Object>) card.get("completeness")).get("totalCount"));
        assertEquals(0, ((Map<String, Object>) card.get("priceComparison")).get("totalCount"));
        assertTrue(execution.getAnswer().contains("未发现缺失关键字段"));
    }

    private ProjectEntity project(String id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setProjectId(id);
        project.setProjectName(name);
        return project;
    }

    private ProjectMaterialPricingEntity pricing(String projectId, String name, String spec,
                                                 String unit, String dailyRent) {
        ProjectMaterialPricingEntity entity = new ProjectMaterialPricingEntity();
        entity.setProjectId(projectId);
        entity.setMaterialName(name);
        entity.setMaterialSpecification(spec);
        entity.setCountingUnit(unit);
        entity.setDailyRent(new BigDecimal(dailyRent));
        return entity;
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("c1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        return workspace;
    }
}
