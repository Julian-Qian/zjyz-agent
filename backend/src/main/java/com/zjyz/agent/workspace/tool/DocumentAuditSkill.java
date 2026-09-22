package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.pojo.entity.CompensationDocumentEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentInDocumentEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * D2 单据审核清单（document.audit_list）：跨五类业务单据的确定性审核规则清单。
 * v1 规则：UNREVIEWED（未复核）与 MISSING_DATE（业务日期缺失）；
 * 附件缺失、数量越界、日期断档等规则留待后续版本，卡片口径中显式声明。
 */
@Component
public class DocumentAuditSkill {
    public static final String TOOL_CODE = "document.audit_list";
    private static final List<String> DOCUMENT_TYPES = Arrays.asList(
            "RENT_OUT", "RETURN", "COMPENSATION", "RENT_IN", "RENT_IN_RETURN");
    private static final List<String> ISSUE_TYPES = Arrays.asList("UNREVIEWED", "MISSING_DATE");
    private static final Map<String, String> TYPE_LABELS;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("RENT_OUT", "租出单");
        labels.put("RETURN", "归还单");
        labels.put("COMPENSATION", "赔偿单");
        labels.put("RENT_IN", "租入单");
        labels.put("RENT_IN_RETURN", "退租单");
        TYPE_LABELS = Collections.unmodifiableMap(labels);
    }

    private final RentDocumentMapper rentDocumentMapper;
    private final ReturnDocumentMapper returnDocumentMapper;
    private final CompensationDocumentMapper compensationDocumentMapper;
    private final RentInDocumentMapper rentInDocumentMapper;
    private final RentInReturnDocumentMapper rentInReturnDocumentMapper;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    public DocumentAuditSkill(RentDocumentMapper rentDocumentMapper,
                              ReturnDocumentMapper returnDocumentMapper,
                              CompensationDocumentMapper compensationDocumentMapper,
                              RentInDocumentMapper rentInDocumentMapper,
                              RentInReturnDocumentMapper rentInReturnDocumentMapper,
                              ProjectMapper projectMapper,
                              ObjectMapper objectMapper) {
        this.rentDocumentMapper = rentDocumentMapper;
        this.returnDocumentMapper = returnDocumentMapper;
        this.compensationDocumentMapper = compensationDocumentMapper;
        this.rentInDocumentMapper = rentInDocumentMapper;
        this.rentInReturnDocumentMapper = rentInReturnDocumentMapper;
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson);
        List<AuditItem> items = new ArrayList<>();
        if (query.documentTypes.contains("RENT_OUT")) {
            for (RentDocumentEntity row : rentDocumentMapper.selectList(
                    wrapper(workspace, query, "rent_date", "rent_document_name"))) {
                collect(items, "RENT_OUT", row.getRentDocumentId(), row.getRentDocumentName(),
                        row.getProjectId(), row.getCustomerName(), row.getRentDate(),
                        row.getReviewStatus(), row.getCreateDate(), query);
            }
        }
        if (query.documentTypes.contains("RETURN")) {
            for (ReturnDocumentEntity row : returnDocumentMapper.selectList(
                    wrapper(workspace, query, "return_date", "return_document_name"))) {
                collect(items, "RETURN", row.getReturnDocumentId(), row.getReturnDocumentName(),
                        row.getProjectId(), row.getCustomerName(), row.getReturnDate(),
                        row.getReviewStatus(), row.getCreateDate(), query);
            }
        }
        if (query.documentTypes.contains("COMPENSATION")) {
            for (CompensationDocumentEntity row : compensationDocumentMapper.selectList(
                    wrapper(workspace, query, "compensation_date", "compensation_document_name"))) {
                collect(items, "COMPENSATION", row.getCompensationDocumentId(), row.getCompensationDocumentName(),
                        row.getProjectId(), row.getCustomerName(), row.getCompensationDate(),
                        row.getReviewStatus(), row.getCreateDate(), query);
            }
        }
        if (query.documentTypes.contains("RENT_IN")) {
            for (RentInDocumentEntity row : rentInDocumentMapper.selectList(
                    wrapper(workspace, query, "rent_in_date", "rent_in_document_name"))) {
                collect(items, "RENT_IN", row.getRentInDocumentId(), row.getRentInDocumentName(),
                        row.getProjectId(), row.getSupplierName(), row.getRentInDate(),
                        row.getReviewStatus(), row.getCreateDate(), query);
            }
        }
        if (query.documentTypes.contains("RENT_IN_RETURN")) {
            for (RentInReturnDocumentEntity row : rentInReturnDocumentMapper.selectList(
                    wrapper(workspace, query, "rent_in_return_date", "rent_in_return_document_name"))) {
                collect(items, "RENT_IN_RETURN", row.getRentInReturnDocumentId(), row.getRentInReturnDocumentName(),
                        row.getProjectId(), row.getSupplierName(), row.getRentInReturnDate(),
                        row.getReviewStatus(), row.getCreateDate(), query);
            }
        }

        items.sort(Comparator
                .comparingInt((AuditItem item) -> item.issues.size()).reversed()
                .thenComparing(item -> safe(item.businessDate), Comparator.reverseOrder())
                .thenComparing(item -> safe(item.documentId)));
        List<AuditItem> displayed = items.stream().limit(query.limit).collect(Collectors.toList());
        Map<String, String> projectNames = resolveProjectNames(workspace, displayed);

        Map<String, Integer> countsByType = new LinkedHashMap<>();
        Map<String, Integer> countsByIssue = new LinkedHashMap<>();
        for (AuditItem item : items) {
            countsByType.merge(item.documentType, 1, Integer::sum);
            for (String issue : item.issues) {
                countsByIssue.merge(issue, 1, Integer::sum);
            }
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "document-audit-list");
        card.put("schemaVersion", "1.0");
        card.put("metricVersion", "document-audit-v1");
        card.put("asOfDate", LocalDate.now().toString());
        card.put("selectionMode", workspace.getSelectionMode());
        card.put("startDate", query.startDate);
        card.put("endDate", query.endDate);
        card.put("issueTypes", query.issueTypes);
        card.put("countsByType", countsByType);
        card.put("countsByIssue", countsByIssue);
        card.put("totalCount", items.size());
        card.put("displayedCount", displayed.size());
        card.put("truncated", items.size() > displayed.size());
        card.put("limit", query.limit);
        card.put("items", displayed.stream()
                .map(item -> cardItem(item, projectNames)).collect(Collectors.toList()));
        card.put("scopeNote", "v1 审核规则仅覆盖未复核与业务日期缺失两类；"
                + "附件缺失、数量越界、日期断档等规则尚未纳入，不能据此判断单据完全合规。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(StringUtils.hasText(query.startDate) || StringUtils.hasText(query.endDate)
                ? safe(query.startDate) + "~" + safe(query.endDate)
                : "asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("DocumentAuditSkill"));
        evidence.setApiList(Arrays.asList("internal:rent_document", "internal:return_document",
                "internal:compensation_document", "internal:rent_in_document", "internal:rent_in_return_document"));
        evidence.setRecordCount(items.size());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("document_audit_list");
        execution.setConfidence(0.92d);
        execution.setAnswer(buildAnswer(workspace, query, items, displayed.size(), countsByType, countsByIssue));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private <T> QueryWrapper<T> wrapper(AgentRuntimeRecords.Workspace workspace,
                                        Query query,
                                        String dateColumn,
                                        String nameColumn) {
        QueryWrapper<T> wrapper = new QueryWrapper<T>().eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(query.startDate)) {
            wrapper.ge(dateColumn, query.startDate);
        }
        if (StringUtils.hasText(query.endDate)) {
            wrapper.le(dateColumn, query.endDate);
        }
        if (StringUtils.hasText(query.keyword)) {
            wrapper.like(nameColumn, query.keyword);
        }
        boolean unreviewed = query.issueTypes.contains("UNREVIEWED");
        boolean missingDate = query.issueTypes.contains("MISSING_DATE");
        if (unreviewed && missingDate) {
            wrapper.and(w -> w.eq("review_status", 0).or().isNull("review_status")
                    .or().isNull(dateColumn).or().eq(dateColumn, ""));
        } else if (unreviewed) {
            wrapper.and(w -> w.eq("review_status", 0).or().isNull("review_status"));
        } else {
            wrapper.and(w -> w.isNull(dateColumn).or().eq(dateColumn, ""));
        }
        return wrapper;
    }

    private void collect(List<AuditItem> items,
                         String documentType,
                         String documentId,
                         String documentName,
                         String projectId,
                         String counterpartyName,
                         String businessDate,
                         Integer reviewStatus,
                         String createDate,
                         Query query) {
        List<String> issues = new ArrayList<>();
        if (query.issueTypes.contains("UNREVIEWED") && (reviewStatus == null || reviewStatus == 0)) {
            issues.add("UNREVIEWED");
        }
        if (query.issueTypes.contains("MISSING_DATE") && !StringUtils.hasText(businessDate)) {
            issues.add("MISSING_DATE");
        }
        if (issues.isEmpty()) {
            return;
        }
        AuditItem item = new AuditItem();
        item.documentType = documentType;
        item.documentId = documentId;
        item.documentName = documentName;
        item.projectId = projectId;
        item.counterpartyName = counterpartyName;
        item.businessDate = businessDate;
        item.createDate = createDate;
        item.issues = issues;
        items.add(item);
    }

    private Map<String, String> resolveProjectNames(AgentRuntimeRecords.Workspace workspace,
                                                    List<AuditItem> displayed) {
        Set<String> projectIds = new HashSet<>();
        for (AuditItem item : displayed) {
            if (StringUtils.hasText(item.projectId)) {
                projectIds.add(item.projectId);
            }
        }
        if (projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProjectEntity> projects = projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                .eq("cid", workspace.getCid())
                .in("project_id", projectIds));
        return projects.stream().collect(Collectors.toMap(
                ProjectEntity::getProjectId, project -> safe(project.getProjectName()), (left, right) -> left));
    }

    private Map<String, Object> cardItem(AuditItem item, Map<String, String> projectNames) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("documentType", item.documentType);
        value.put("documentTypeLabel", TYPE_LABELS.getOrDefault(item.documentType, item.documentType));
        value.put("documentId", item.documentId);
        value.put("documentName", item.documentName);
        value.put("projectId", item.projectId);
        value.put("projectName", projectNames.get(item.projectId));
        value.put("counterpartyName", item.counterpartyName);
        value.put("businessDate", item.businessDate);
        value.put("createDate", item.createDate);
        value.put("issues", item.issues);
        return value;
    }

    private String buildAnswer(AgentRuntimeRecords.Workspace workspace,
                               Query query,
                               List<AuditItem> items,
                               int displayedCount,
                               Map<String, Integer> countsByType,
                               Map<String, Integer> countsByIssue) {
        String scope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部项目" : "当前选中的 " + workspace.getProjectIds().size() + " 个项目";
        if (items.isEmpty()) {
            return scope + "范围内没有命中审核规则的单据（v1 规则：未复核、业务日期缺失）。"
                    + "该结论不代表附件、数量等其他规则合规。";
        }
        String typeText = countsByType.entrySet().stream()
                .map(entry -> TYPE_LABELS.getOrDefault(entry.getKey(), entry.getKey()) + " " + entry.getValue())
                .collect(Collectors.joining("、"));
        String issueText = countsByIssue.entrySet().stream()
                .map(entry -> ("UNREVIEWED".equals(entry.getKey()) ? "未复核" : "业务日期缺失") + " " + entry.getValue())
                .collect(Collectors.joining("、"));
        return scope + "范围内命中审核规则的单据共 " + items.size() + " 张（按类型：" + typeText
                + "；按问题：" + issueText + "）。"
                + (items.size() > displayedCount ? "结果卡展示前 " + displayedCount + " 张。" : "")
                + "v1 规则仅覆盖未复核与业务日期缺失，不代表其他规则合规。";
    }

    private Query parseQuery(String argumentsJson) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                List<String> types = textList(root.path("documentTypes"));
                if (!types.isEmpty()) {
                    for (String type : types) {
                        if (!DOCUMENT_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
                            throw new MyBizException("不支持的单据类型：" + type, "AGT400");
                        }
                    }
                    query.documentTypes = types.stream()
                            .map(type -> type.toUpperCase(Locale.ROOT)).distinct().collect(Collectors.toList());
                }
                List<String> issues = textList(root.path("issueTypes"));
                if (!issues.isEmpty()) {
                    for (String issue : issues) {
                        if (!ISSUE_TYPES.contains(issue.toUpperCase(Locale.ROOT))) {
                            throw new MyBizException("不支持的审核规则：" + issue, "AGT400");
                        }
                    }
                    query.issueTypes = issues.stream()
                            .map(issue -> issue.toUpperCase(Locale.ROOT)).distinct().collect(Collectors.toList());
                }
                query.startDate = trimToNull(root.path("startDate").asText(null));
                query.endDate = trimToNull(root.path("endDate").asText(null));
                query.keyword = trimToNull(root.path("keyword").asText(null));
                int limit = root.path("limit").asInt(DEFAULT_LIMIT);
                query.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("单据审核查询参数格式不正确", "AGT400");
            }
        }
        return query;
    }

    private List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("");
                if (StringUtils.hasText(value)) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private static class Query {
        private List<String> documentTypes = new ArrayList<>(DOCUMENT_TYPES);
        private List<String> issueTypes = new ArrayList<>(ISSUE_TYPES);
        private String startDate;
        private String endDate;
        private String keyword;
        private int limit = DEFAULT_LIMIT;
    }

    private static class AuditItem {
        private String documentType;
        private String documentId;
        private String documentName;
        private String projectId;
        private String counterpartyName;
        private String businessDate;
        private String createDate;
        private List<String> issues = Collections.emptyList();
    }
}
