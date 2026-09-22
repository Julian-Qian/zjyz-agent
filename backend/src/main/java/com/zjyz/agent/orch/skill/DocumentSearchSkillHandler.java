package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.*;
import com.zjyz.pojo.param.req.QueryDocumentListParam;
import com.zjyz.pojo.param.ret.DocumentBriefInfo;
import com.zjyz.pojo.param.ret.DocumentListInfoRet;
import com.zjyz.service.DocumentStrategyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class DocumentSearchSkillHandler implements AgentSkillHandler {

    @Autowired
    private DocumentStrategyFactory documentStrategyFactory;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.DOCUMENT_SEARCH;
    }

    @Override
    public boolean requiresProjectId(AgentSlotBag slots) {
        String queryType = slots == null ? "0" : slots.getQueryType();
        return Arrays.asList("0", "1", "2", "3", "8", "9", "10").contains(queryType);
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        String queryType = slots == null || !StringUtils.hasText(slots.getQueryType()) ? "0" : slots.getQueryType();
        String projectId = slots == null ? null : slots.getProjectId();

        if (requiresProjectId(slots) && !StringUtils.hasText(projectId)) {
            return AgentSkillExecution.clarification(
                    "document_search",
                    "当前问题缺少项目ID或项目名称。请补充后我可以精确查询单据并给出下载入口。",
                    Collections.singletonList("projectId"),
                    buildEvidence(0)
            );
        }

        QueryDocumentListParam param = new QueryDocumentListParam();
        param.setPageNum(1);
        param.setPageSize(10);
        param.setQueryType(queryType);
        param.setProjectId(projectId);

        DocumentListInfoRet listInfo = documentStrategyFactory.queryDocumentList(param);
        if (listInfo == null || CollectionUtils.isEmpty(listInfo.getDocumentBriefInfoList())) {
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("document_search");
            execution.setConfidence(0.87);
            execution.setAnswer("未查询到匹配单据。");
            execution.setEvidence(buildEvidence(0));
            return execution;
        }

        List<DocumentBriefInfo> items = listInfo.getDocumentBriefInfoList();
        String names = items.stream()
                .limit(3)
                .map(item -> item.getDocumentName() + "(" + item.getDocumentId() + ")")
                .collect(Collectors.joining("、"));

        String answer = "已找到 " + items.size() + " 条单据，示例：" + names + "。已提供可点击下载入口，请在下方单据列表中直接下载。";

        List<Map<String, Object>> downloadableItems = items.stream()
                .map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("documentId", item.getDocumentId());
                    m.put("documentName", item.getDocumentName());
                    m.put("createDate", item.getCreateDate());
                    m.put("personInCharge", item.getPersonInCharge());
                    m.put("downloadPath", buildDownloadPath(queryType, item.getDocumentId()));
                    m.put("downloadFileName", buildDownloadFileName(queryType, item));
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "document-list");
        card.put("queryType", queryType);
        card.put("count", items.size());
        card.put("items", downloadableItems);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("document_search");
        execution.setConfidence(0.89);
        execution.setAnswer(answer);
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(buildEvidence(items.size()));
        return execution;
    }

    private AgentEvidence buildEvidence(int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("DocumentLocatorSkill"));
        evidence.setApiList(Collections.singletonList("/document/queryDocumentList"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private String buildDownloadPath(String queryType, String documentId) {
        switch (queryType) {
            case "0":
                return "/document/exportRentDocumentExcel?rentDocumentId=" + documentId;
            case "1":
                return "/document/exportReturnDocumentExcel?returnDocumentId=" + documentId;
            case "2":
                return "/document/exportCompensationDocumentExcel?compensationDocumentId=" + documentId;
            case "3":
                return "/settlementDocument/exportSettlementDocumentExcel?documentId=" + documentId;
            case "8":
                return "/document/exportReconciliationDocumentExcel?reconciliationDocumentId=" + documentId;
            case "9":
                return "/rentInDocument/exportRentInDocumentExcel?rentInDocumentId=" + documentId;
            case "10":
                return "/rentInReturnDocument/exportRentInReturnDocumentExcel?rentInReturnDocumentId=" + documentId;
            default:
                return "";
        }
    }

    private String buildDownloadFileName(String queryType, DocumentBriefInfo item) {
        String id = item == null || !StringUtils.hasText(item.getDocumentId()) ? "document" : item.getDocumentId();
        String title = item == null || !StringUtils.hasText(item.getDocumentName()) ? "单据" : item.getDocumentName();
        switch (queryType) {
            case "0":
                return "租出单_" + id + ".xlsx";
            case "1":
                return "归还单_" + id + ".xlsx";
            case "2":
                return "赔偿单_" + id + ".xlsx";
            case "3":
                return "财务对账单_" + id + ".xlsx";
            case "8":
                return "物料对账单_" + id + ".xlsx";
            case "9":
                return "租入单_" + id + ".xlsx";
            case "10":
                return "退租单_" + id + ".xlsx";
            default:
                return title + "_" + id + ".xlsx";
        }
    }
}
