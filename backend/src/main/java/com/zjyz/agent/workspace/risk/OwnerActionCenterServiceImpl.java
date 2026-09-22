package com.zjyz.agent.workspace.risk;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.FailedDimension;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.DimensionStatus;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.ProjectAction;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Query;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Result;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.RiskFact;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.ScoreContribution;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.UnsupportedDimension;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.ReconciliationDocumentMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInDocumentMaterialMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.CompensationDocumentEntity;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.ReconciliationDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentInDocumentEntity;
import com.zjyz.pojo.entity.RentInDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentMaterialEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentMaterialEntity;
import com.zjyz.pojo.entity.SettlementDocumentEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OwnerActionCenterServiceImpl implements OwnerActionCenterService {
    public static final String CONTRACT_EXPIRED = "CONTRACT_EXPIRED";
    public static final String MATERIAL_OUTSTANDING = "MATERIAL_OUTSTANDING";
    public static final String UNRECONCILED = "UNRECONCILED";
    public static final String OVERDUE_RECEIVABLE = "OVERDUE_RECEIVABLE";
    public static final String INACTIVE_PROJECT = "INACTIVE_PROJECT";
    public static final String INVENTORY_ANOMALY = "INVENTORY_ANOMALY";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";

    private static final Set<String> SUPPORTED_RISK_TYPES = new LinkedHashSet<>(Arrays.asList(
            CONTRACT_EXPIRED, MATERIAL_OUTSTANDING, UNRECONCILED, OVERDUE_RECEIVABLE,
            INACTIVE_PROJECT, INVENTORY_ANOMALY, PENDING_REVIEW));
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int INACTIVE_DAYS = 30;
    private static final int UNRECONCILED_DAYS = 30;
    private static final String FINANCIAL_SETTLEMENT_TYPE = "3";

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final SettlementDocumentMapper settlementDocumentMapper;
    private final ReconciliationDocumentMapper reconciliationDocumentMapper;
    private final RentDocumentMapper rentDocumentMapper;
    private final ReturnDocumentMapper returnDocumentMapper;
    private final CompensationDocumentMapper compensationDocumentMapper;
    private final RentInDocumentMapper rentInDocumentMapper;
    private final RentInReturnDocumentMapper rentInReturnDocumentMapper;
    private final RentDocumentMaterialMapper rentMaterialMapper;
    private final ReturnDocumentMaterialMapper returnMaterialMapper;
    private final RentInDocumentMaterialMapper rentInMaterialMapper;
    private final RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper;
    private final FinanceLedgerSnapshotService financeLedgerSnapshotService;
    private Clock clock;

    @Autowired
    public OwnerActionCenterServiceImpl(ProjectMapper projectMapper,
                                        ContractMapper contractMapper,
                                        SettlementDocumentMapper settlementDocumentMapper,
                                        ReconciliationDocumentMapper reconciliationDocumentMapper,
                                        RentDocumentMapper rentDocumentMapper,
                                        ReturnDocumentMapper returnDocumentMapper,
                                        CompensationDocumentMapper compensationDocumentMapper,
                                        RentInDocumentMapper rentInDocumentMapper,
                                        RentInReturnDocumentMapper rentInReturnDocumentMapper,
                                        RentDocumentMaterialMapper rentMaterialMapper,
                                        ReturnDocumentMaterialMapper returnMaterialMapper,
                                        RentInDocumentMaterialMapper rentInMaterialMapper,
                                        RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper,
                                        FinanceLedgerSnapshotService financeLedgerSnapshotService) {
        this(projectMapper, contractMapper, settlementDocumentMapper, reconciliationDocumentMapper,
                rentDocumentMapper, returnDocumentMapper, compensationDocumentMapper, rentInDocumentMapper,
                rentInReturnDocumentMapper, rentMaterialMapper, returnMaterialMapper, rentInMaterialMapper,
                rentInReturnMaterialMapper, financeLedgerSnapshotService, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    OwnerActionCenterServiceImpl(ProjectMapper projectMapper,
                                 ContractMapper contractMapper,
                                 SettlementDocumentMapper settlementDocumentMapper,
                                 ReconciliationDocumentMapper reconciliationDocumentMapper,
                                 RentDocumentMapper rentDocumentMapper,
                                 ReturnDocumentMapper returnDocumentMapper,
                                 CompensationDocumentMapper compensationDocumentMapper,
                                 RentInDocumentMapper rentInDocumentMapper,
                                 RentInReturnDocumentMapper rentInReturnDocumentMapper,
                                 RentDocumentMaterialMapper rentMaterialMapper,
                                 ReturnDocumentMaterialMapper returnMaterialMapper,
                                 RentInDocumentMaterialMapper rentInMaterialMapper,
                                 RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper,
                                 FinanceLedgerSnapshotService financeLedgerSnapshotService,
                                 Clock clock) {
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.settlementDocumentMapper = settlementDocumentMapper;
        this.reconciliationDocumentMapper = reconciliationDocumentMapper;
        this.rentDocumentMapper = rentDocumentMapper;
        this.returnDocumentMapper = returnDocumentMapper;
        this.compensationDocumentMapper = compensationDocumentMapper;
        this.rentInDocumentMapper = rentInDocumentMapper;
        this.rentInReturnDocumentMapper = rentInReturnDocumentMapper;
        this.rentMaterialMapper = rentMaterialMapper;
        this.returnMaterialMapper = returnMaterialMapper;
        this.rentInMaterialMapper = rentInMaterialMapper;
        this.rentInReturnMaterialMapper = rentInReturnMaterialMapper;
        this.financeLedgerSnapshotService = financeLedgerSnapshotService;
        this.clock = clock;
    }

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    void setRuntimeTimezone(String runtimeTimezone) {
        try {
            this.clock = Clock.system(ZoneId.of(runtimeTimezone));
        } catch (Exception ignored) {
            this.clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        }
    }

    @Override
    public Result generate(AgentRuntimeRecords.Workspace workspace, Query input) {
        Scope scope = requireScope(workspace);
        boolean explicitRiskTypes = input != null && input.getRiskTypes() != null && !input.getRiskTypes().isEmpty();
        Query query = normalize(input);
        LocalDate today = LocalDate.now(clock);
        List<String> warnings = coreWarnings();
        List<UnsupportedDimension> unsupported = new ArrayList<>();
        List<FailedDimension> failed = new ArrayList<>();
        Map<String, List<String>> nonApplicableProjectIds = new LinkedHashMap<>();

        Map<String, ProjectEntity> projects = loadProjects(scope, query.getProjectStatus());
        Map<String, List<RiskFact>> facts = new LinkedHashMap<>();
        projects.keySet().forEach(projectId -> facts.put(projectId, new ArrayList<>()));
        List<String> projectIds = new ArrayList<>(projects.keySet());
        if (selected(query, CONTRACT_EXPIRED)) {
            executeDimension(CONTRACT_EXPIRED, explicitRiskTypes, query, facts, failed, warnings,
                    () -> addContractRisks(projects, loadLatestContracts(projectIds, today, warnings), facts, today));
        }
        if (selected(query, MATERIAL_OUTSTANDING)) {
            executeDimension(MATERIAL_OUTSTANDING, explicitRiskTypes, query, facts, failed, warnings,
                    () -> addMaterialRisks(scope, projects, loadDocuments(scope, projects, today, warnings), facts, today, warnings));
        }
        if (selected(query, UNRECONCILED)) {
            Map<String, ProjectEntity> applicable = rentOutProjects(projects, UNRECONCILED, nonApplicableProjectIds, warnings);
            if (applicable.isEmpty()) {
                unsupported.add(unsupported(UNRECONCILED,
                        "未对账维度仅适用于租出项目；当前范围全部为租入项目，不能将不适用解释为零风险。",
                        "rent-out reconciliation cadence"));
            } else {
                executeDimension(UNRECONCILED, explicitRiskTypes, query, facts, failed, warnings,
                        () -> addReconciliationRisks(scope, applicable,
                                loadLatestContracts(new ArrayList<>(applicable.keySet()), today, warnings), facts, today, warnings));
            }
        }
        if (selected(query, OVERDUE_RECEIVABLE)) {
            Map<String, ProjectEntity> applicable = rentOutProjects(projects, OVERDUE_RECEIVABLE,
                    nonApplicableProjectIds, warnings);
            if (applicable.isEmpty()) {
                unsupported.add(unsupported(OVERDUE_RECEIVABLE,
                        "逾期应收维度仅适用于租出项目；当前范围全部为租入项目，不能将不适用解释为零风险。",
                        "rent-out receivable ledger"));
            } else if (!Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
                unsupported.add(unsupported(OVERDUE_RECEIVABLE,
                        "当前工作空间未开通财务能力，行动中心未读取逾期应收金额。",
                        "financeEnabled=true"));
                warnings.add("逾期应收维度未参与评分：当前工作空间未开通财务能力。");
            } else {
                executeDimension(OVERDUE_RECEIVABLE, explicitRiskTypes, query, facts, failed, warnings,
                        () -> addReceivableRisks(workspace, applicable, facts, today, warnings));
            }
        }
        if (selected(query, INACTIVE_PROJECT)) {
            executeDimension(INACTIVE_PROJECT, explicitRiskTypes, query, facts, failed, warnings,
                    () -> addActivityRisks(scope, projects, loadDocuments(scope, projects, today, warnings), facts, today, warnings));
        }
        if (selected(query, INVENTORY_ANOMALY)) {
            unsupported.add(unsupported(INVENTORY_ANOMALY,
                    "现有库存异常事实源是企业材料汇总，没有可靠projectId归属，不能纳入项目风险排名。",
                    "project-attributed inventory anomaly ledger"));
            warnings.add("库存异常维度未参与评分：企业库存异常目前不能可靠归属到具体项目。");
        }
        if (selected(query, PENDING_REVIEW)) {
            executeDimension(PENDING_REVIEW, explicitRiskTypes, query, facts, failed, warnings,
                    () -> addPendingReviewRisks(projects, loadDocuments(scope, projects, today, warnings), facts, today));
        }

        long unsupportedSelectedCount = unsupported.stream().map(UnsupportedDimension::getRiskType).distinct().count();
        if (!unsupported.isEmpty() && ("ALL".equals(query.getMatchMode())
                || (explicitRiskTypes && query.getRiskTypes().size() == 1)
                || unsupportedSelectedCount == query.getRiskTypes().size())) {
            throw new MyBizException("指定风险维度当前不可评估，不能据此判断项目交集或声称未发现风险", "AGT409");
        }

        List<ProjectAction> allProjectActions = facts.entrySet().stream()
                .filter(entry -> matches(entry.getValue(), query))
                .map(entry -> projectAction(projects.get(entry.getKey()), entry.getValue()))
                .sorted(projectComparator())
                .collect(Collectors.toList());
        verifyActions(allProjectActions);
        int projectRiskCount = (int) facts.values().stream().filter(values -> !values.isEmpty()).count();
        int actionTotalCount = allProjectActions.stream().map(ProjectAction::getRiskCount).reduce(0, Integer::sum);
        int limit = query.getLimit();
        List<ProjectAction> displayedProjects = allProjectActions.stream().limit(limit).collect(Collectors.toList());
        List<RiskFact> allActions = allProjectActions.stream().flatMap(item -> item.getRisks().stream())
                .sorted(riskComparator()).collect(Collectors.toList());
        List<RiskFact> topActions = allActions.stream().limit(limit).collect(Collectors.toList());

        Result result = new Result();
        result.setAsOfDate(today.toString());
        result.setQuery(query);
        result.setTotalProjectCount(projects.size());
        result.setProjectRiskCount(projectRiskCount);
        result.setProjectTotalCount(allProjectActions.size());
        result.setDisplayedProjectCount(displayedProjects.size());
        result.setActionTotalCount(actionTotalCount);
        result.setActionDisplayedCount(topActions.size());
        result.setTotalCount(allProjectActions.size());
        result.setDisplayedCount(displayedProjects.size());
        result.setTruncated(allProjectActions.size() > limit);
        result.setActionTruncated(allActions.size() > limit);
        result.setLimit(limit);
        result.setItems(displayedProjects);
        result.setTopActions(topActions);
        result.setUnsupportedDimensions(unsupported);
        result.setFailedDimensions(failed);
        result.setDimensionStatuses(dimensionStatuses(query, facts, unsupported, failed,
                projects.size(), nonApplicableProjectIds));
        result.setScoreIncomplete(!failed.isEmpty() || !unsupported.isEmpty() || !nonApplicableProjectIds.isEmpty());
        result.setRankingBasis(Boolean.TRUE.equals(result.getScoreIncomplete())
                ? "AVAILABLE_DIMENSIONS" : "ALL_REQUESTED_DIMENSIONS");
        result.setWarnings(deduplicate(warnings));
        verifyResult(result);
        return result;
    }

    private Scope requireScope(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !StringUtils.hasText(workspace.getCid())) {
            throw new MyBizException("行动中心工作空间上下文缺失", "AGT400");
        }
        String mode = safe(workspace.getSelectionMode()).toUpperCase(Locale.ROOT);
        if (!"ALL".equals(mode) && !"EXPLICIT".equals(mode)) {
            throw new MyBizException("行动中心项目范围不正确", "AGT400");
        }
        Set<String> projectIds = workspace.getProjectIds() == null
                ? new LinkedHashSet<>() : workspace.getProjectIds().stream().filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if ("ALL".equals(mode) && !projectIds.isEmpty()) {
            throw new MyBizException("全部项目范围不能携带项目ID", "AGT400");
        }
        if ("EXPLICIT".equals(mode) && projectIds.isEmpty()) {
            throw new MyBizException("所选项目范围不能为空", "AGT400");
        }
        return new Scope(workspace.getCid(), mode, projectIds);
    }

    private Query normalize(Query input) {
        Query query = input == null ? new Query() : input;
        List<String> risks = query.getRiskTypes() == null ? new ArrayList<>() : query.getRiskTypes().stream()
                .filter(StringUtils::hasText).map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct()
                .collect(Collectors.toList());
        if (risks.isEmpty()) risks.addAll(SUPPORTED_RISK_TYPES);
        for (String risk : risks) {
            if (!SUPPORTED_RISK_TYPES.contains(risk)) {
                throw new MyBizException("riskTypes包含不支持的风险类型：" + risk, "AGT400");
            }
        }
        query.setRiskTypes(risks);
        query.setMatchMode(normalizeEnum(query.getMatchMode(), Arrays.asList("ANY", "ALL"), "matchMode"));
        query.setProjectStatus(normalizeEnum(query.getProjectStatus(), Arrays.asList("ONGOING", "ALL", "COMPLETED"), "projectStatus"));
        int limit = query.getLimit() == null ? DEFAULT_LIMIT : query.getLimit();
        if (limit < 1 || limit > MAX_LIMIT) throw new MyBizException("limit必须在1到100之间", "AGT400");
        query.setLimit(limit);
        return query;
    }

    private String normalizeEnum(String value, List<String> supported, String field) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : supported.get(0);
        if (!supported.contains(normalized)) throw new MyBizException(field + "参数不正确", "AGT400");
        return normalized;
    }

    private Map<String, ProjectEntity> loadProjects(Scope scope, String status) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>().eq("cid", scope.cid);
        if ("EXPLICIT".equals(scope.selectionMode)) wrapper.in("project_id", scope.projectIds);
        if ("ONGOING".equals(status)) wrapper.eq("project_status_flag", "0");
        if ("COMPLETED".equals(status)) wrapper.eq("project_status_flag", "1");
        Map<String, ProjectEntity> result = new LinkedHashMap<>();
        for (ProjectEntity project : projectMapper.selectList(wrapper)) {
            if (inScope(scope, project.getCid(), project.getProjectId())) result.put(project.getProjectId(), project);
        }
        return result;
    }

    private Map<String, ContractEntity> loadLatestContracts(List<String> projectIds, LocalDate today, List<String> warnings) {
        if (projectIds.isEmpty()) return Collections.emptyMap();
        Map<String, ContractEntity> result = new HashMap<>();
        int invalidFuture = 0;
        for (ContractEntity contract : contractMapper.selectList(new QueryWrapper<ContractEntity>().in("project_id", projectIds))) {
            if (!projectIds.contains(contract.getProjectId())) continue;
            LocalDate created = date(contract.getCreateDate());
            if (created != null && created.isAfter(today)) { invalidFuture++; continue; }
            ContractEntity current = result.get(contract.getProjectId());
            if (current == null || compareLatest(contract.getCreateDate(), contract.getContractId(),
                    current.getCreateDate(), current.getContractId()) > 0) result.put(contract.getProjectId(), contract);
        }
        if (invalidFuture > 0) warnings.add("已排除 " + invalidFuture + " 条未来录入的合同记录。");
        return result;
    }

    private ReviewedDocuments loadDocuments(Scope scope, Map<String, ProjectEntity> projects,
                                              LocalDate today, List<String> warnings) {
        ReviewedDocuments result = new ReviewedDocuments();
        if (projects.isEmpty()) return result;
        List<String> ids = new ArrayList<>(projects.keySet());
        int excludedUnreviewedFromFacts = 0;
        int invalidReviewStatuses = 0;
        int excludedFuture = 0;
        for (RentDocumentEntity row : rentDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate businessDate = date(row.getRentDate());
            if (isReviewed(row.getReviewStatus()) && validAsOf(businessDate, today)) {
                result.rentIds.add(row.getRentDocumentId()); result.activities.add(activity(row.getProjectId(), "RENT_OUT", businessDate));
            } else if (isReviewed(row.getReviewStatus())) excludedFuture++;
            else excludedUnreviewedFromFacts++;
            if (isPendingReview(row.getReviewStatus())) addPending(result, row.getProjectId(), "RENT_OUT", date(row.getCreateDate()), today);
            else if (isInvalidReviewStatus(row.getReviewStatus())) invalidReviewStatuses++;
        }
        for (ReturnDocumentEntity row : returnDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate businessDate = date(row.getReturnDate());
            if (isReviewed(row.getReviewStatus()) && validAsOf(businessDate, today)) {
                result.returnIds.add(row.getReturnDocumentId()); result.activities.add(activity(row.getProjectId(), "RETURN", businessDate));
            } else if (isReviewed(row.getReviewStatus())) excludedFuture++;
            else excludedUnreviewedFromFacts++;
            if (isPendingReview(row.getReviewStatus())) addPending(result, row.getProjectId(), "RETURN", date(row.getCreateDate()), today);
            else if (isInvalidReviewStatus(row.getReviewStatus())) invalidReviewStatuses++;
        }
        for (CompensationDocumentEntity row : compensationDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate businessDate = date(row.getCompensationDate());
            if (isReviewed(row.getReviewStatus()) && validAsOf(businessDate, today)) result.activities.add(activity(row.getProjectId(), "COMPENSATION", businessDate));
            else if (isReviewed(row.getReviewStatus())) excludedFuture++;
            else excludedUnreviewedFromFacts++;
            if (isPendingReview(row.getReviewStatus())) addPending(result, row.getProjectId(), "COMPENSATION", date(row.getCreateDate()), today);
            else if (isInvalidReviewStatus(row.getReviewStatus())) invalidReviewStatuses++;
        }
        for (RentInDocumentEntity row : rentInDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate businessDate = date(row.getRentInDate());
            if (isReviewed(row.getReviewStatus()) && validAsOf(businessDate, today)) {
                result.rentInIds.add(row.getRentInDocumentId()); result.activities.add(activity(row.getProjectId(), "RENT_IN", businessDate));
            } else if (isReviewed(row.getReviewStatus())) excludedFuture++;
            else excludedUnreviewedFromFacts++;
            if (isPendingReview(row.getReviewStatus())) addPending(result, row.getProjectId(), "RENT_IN", date(row.getCreateDate()), today);
            else if (isInvalidReviewStatus(row.getReviewStatus())) invalidReviewStatuses++;
        }
        for (RentInReturnDocumentEntity row : rentInReturnDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate businessDate = date(row.getRentInReturnDate());
            if (isReviewed(row.getReviewStatus()) && validAsOf(businessDate, today)) {
                result.rentInReturnIds.add(row.getRentInReturnDocumentId()); result.activities.add(activity(row.getProjectId(), "RENT_IN_RETURN", businessDate));
            } else if (isReviewed(row.getReviewStatus())) excludedFuture++;
            else excludedUnreviewedFromFacts++;
            if (isPendingReview(row.getReviewStatus())) addPending(result, row.getProjectId(), "RENT_IN_RETURN", date(row.getCreateDate()), today);
            else if (isInvalidReviewStatus(row.getReviewStatus())) invalidReviewStatuses++;
        }
        if (excludedUnreviewedFromFacts > 0) warnings.add("材料未归还和长期无活动仅采用review_status=1的已复核单据；已排除 "
                + excludedUnreviewedFromFacts + " 条非已复核单据；待审核维度仅单独统计review_status=0或NULL的记录。");
        if (invalidReviewStatuses > 0) warnings.add("发现 " + invalidReviewStatuses
                + " 条review_status不是0、1或NULL的单据；已作为数据质量异常排除，未计入待审核数量。");
        if (excludedFuture > 0) warnings.add("材料未归还和长期无活动已排除 " + excludedFuture + " 条业务日期无效或晚于今天的已复核单据。");
        long unknownPendingAge = result.pending.stream().filter(value -> value.date == null).count();
        if (unknownPendingAge > 0) warnings.add("有 " + unknownPendingAge + " 条待审核单据的录入日期无效或晚于今天；仍计入待审核数量，但不计算待审时长分。");
        return result;
    }

    private <T> QueryWrapper<T> documentScope(Scope scope, List<String> projectIds) {
        return new QueryWrapper<T>().eq("cid", scope.cid).in("project_id", projectIds);
    }

    private void addContractRisks(Map<String, ProjectEntity> projects, Map<String, ContractEntity> contracts,
                                  Map<String, List<RiskFact>> facts, LocalDate today) {
        for (ProjectEntity project : projects.values()) {
            if (isCompleted(project)) continue;
            ContractEntity contract = contracts.get(project.getProjectId());
            List<ScoreContribution> contributions = new ArrayList<>();
            Map<String, Object> metrics = new LinkedHashMap<>();
            LocalDate evidenceDate = today;
            String action;
            if (contract == null) {
                addContribution(contributions, "NO_CONTRACT", "进行中项目未找到合同", 20, true);
                metrics.put("contractStatus", "NO_CONTRACT");
                metrics.put("contractId", null);
                action = "确认项目是否应补录合同；未核实前不要用其他项目合同替代。";
            } else if (!StringUtils.hasText(contract.getEndDate())) {
                addContribution(contributions, "NO_END_DATE", "合同未填写截止日期", 15, true);
                metrics.put("contractStatus", "NO_END_DATE");
                metrics.put("contractId", contract.getContractId());
                metrics.put("contractEndDate", null);
                action = "补充核实合同截止日期，明确续租、退场或结项节点。";
            } else {
                LocalDate end = date(contract.getEndDate());
                if (end == null) {
                    addContribution(contributions, "INVALID_END_DATE", "合同截止日期格式异常", 20, contract.getEndDate());
                    metrics.put("contractStatus", "INVALID_END_DATE");
                    metrics.put("contractId", contract.getContractId());
                    metrics.put("contractEndDateRaw", contract.getEndDate());
                    action = "修复合同截止日期数据；不得将非法日期当作未填写或已到期。";
                } else if (end.isBefore(today)) {
                    int days = safeDays(end, today);
                    addContribution(contributions, "CONTRACT_EXPIRED_BASE", "进行中项目合同已到期", 25, true);
                    addContribution(contributions, "CONTRACT_OVERDUE_DAYS", "合同逾期时长阶梯", Math.min(30, ceilStep(days, 30) * 5), days);
                    metrics.put("contractStatus", "EXPIRED");
                    metrics.put("contractId", contract.getContractId());
                    metrics.put("contractEndDate", end.toString());
                    metrics.put("overdueDays", days);
                    evidenceDate = end;
                    action = "立即确认续签、退场或结项安排，并更新合同截止日期。";
                } else if (!end.isAfter(today.plusDays(30))) {
                    int daysRemaining = safeDays(today, end);
                    addContribution(contributions, "CONTRACT_EXPIRING_BASE", "合同30天内到期", 15, true);
                    addContribution(contributions, "CONTRACT_DAYS_REMAINING", "临近到期紧迫度", Math.max(0, 15 - (daysRemaining / 10) * 5), daysRemaining);
                    metrics.put("contractStatus", "EXPIRING_SOON");
                    metrics.put("contractId", contract.getContractId());
                    metrics.put("contractEndDate", end.toString());
                    metrics.put("daysRemaining", daysRemaining);
                    evidenceDate = end;
                    action = "在合同到期前确认续签、退场、材料归还与最终对账安排。";
                } else {
                    continue;
                }
            }
            facts.get(project.getProjectId()).add(risk(project, CONTRACT_EXPIRED, metrics, evidenceDate, today,
                    contributions, action));
        }
    }

    private void addMaterialRisks(Scope scope, Map<String, ProjectEntity> projects, ReviewedDocuments docs,
                                  Map<String, List<RiskFact>> facts, LocalDate today, List<String> warnings) {
        if (projects.isEmpty()) return;
        com.zjyz.agent.workspace.tool.MaterialBalanceService service = new com.zjyz.agent.workspace.tool.MaterialBalanceService(
                rentDocumentMapper, rentMaterialMapper, returnDocumentMapper, returnMaterialMapper,
                rentInDocumentMapper, rentInMaterialMapper, rentInReturnDocumentMapper, rentInReturnMaterialMapper);
        com.zjyz.agent.workspace.tool.MaterialBalanceService.Result shared = service.query(scope.cid, projects, today);
        Map<String, MaterialBalance> balances = new LinkedHashMap<>();
        shared.balances.forEach((key, row) -> {
            MaterialBalance b = new MaterialBalance(row.projectId, row.unit);
            b.rented = row.rented; b.returned = row.returned; balances.put(key, b);
        });
        Map<String, List<MaterialBalance>> grouped = balances.values().stream().filter(value -> value.outstanding().signum() > 0)
                .collect(Collectors.groupingBy(value -> value.projectId, LinkedHashMap::new, Collectors.toList()));
        int missingUnitGroups = 0;
        int overReturnGroups = (int) balances.values().stream().filter(value -> value.returned.compareTo(value.rented) > 0).count();
        for (Map.Entry<String, List<MaterialBalance>> entry : grouped.entrySet()) {
            ProjectEntity project = projects.get(entry.getKey());
            if (project == null) continue;
            List<Map<String, Object>> unitGroups = new ArrayList<>();
            Map<String, List<MaterialBalance>> byUnit = entry.getValue().stream().collect(Collectors.groupingBy(
                    value -> StringUtils.hasText(value.unit) ? value.unit : "未填写单位", LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<MaterialBalance>> unitEntry : byUnit.entrySet()) {
                boolean missing = "未填写单位".equals(unitEntry.getKey());
                if (missing) { missingUnitGroups += unitEntry.getValue().size(); continue; }
                BigDecimal unitQuantity = unitEntry.getValue().stream().map(MaterialBalance::outstanding)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                unitGroups.add(mapOf("unit", unitEntry.getKey(), "unitMissing", missing,
                        "materialKinds", unitEntry.getValue().size(), "outstandingQuantity", unitQuantity));
            }
            unitGroups.sort(Comparator.comparing(value -> safe((String) value.get("unit"))));
            int kinds = entry.getValue().size();
            List<ScoreContribution> contributions = new ArrayList<>();
            addContribution(contributions, "MATERIAL_OUTSTANDING_BASE", "存在已复核未归还材料", 20, true);
            addContribution(contributions, "OUTSTANDING_MATERIAL_KINDS", "未归还材料种类", Math.min(30, kinds * 5), kinds);
            Map<String, Object> metrics = mapOf("outstandingMaterialKinds", kinds, "unitGroups", unitGroups,
                    "crossUnitTotal", null, "reviewedOnly", true,
                    "missingUnitMaterialKinds", entry.getValue().stream().filter(v -> !StringUtils.hasText(v.unit) || "未填写单位".equals(v.unit)).count());
            facts.get(project.getProjectId()).add(risk(project, MATERIAL_OUTSTANDING, metrics, today, today,
                    contributions, "按单位核对未归还材料，优先安排归还、续租或差异复核。"));
        }
        if (missingUnitGroups > 0) warnings.add("有 " + missingUnitGroups + " 种未归还材料缺少单位；只保留材料种类和核对提示，不生成未知单位数量合计。");
        if (overReturnGroups > 0) warnings.add("发现 " + overReturnGroups + " 个归还数量大于租出/租入数量的材料组，未将负占用静默改造成库存异常风险。");
    }

    private void addReconciliationRisks(Scope scope, Map<String, ProjectEntity> projects,
                                        Map<String, ContractEntity> contracts, Map<String, List<RiskFact>> facts,
                                        LocalDate today, List<String> warnings) {
        if (projects.isEmpty()) return;
        Map<String, LocalDate> latest = new HashMap<>();
        int invalid = 0;
        for (SettlementDocumentEntity row : settlementDocumentMapper.selectList(documentScope(scope, new ArrayList<>(projects.keySet())))) {
            if (!inScope(scope, row.getCid(), row.getProjectId()) || !FINANCIAL_SETTLEMENT_TYPE.equals(row.getType())
                    || !(row.getStatus() == null || "ACTIVE".equalsIgnoreCase(row.getStatus()))) continue;
            LocalDate end = date(row.getEndDate());
            if (!validAsOf(end, today)) { invalid++; continue; }
            latest.merge(row.getProjectId(), end, (left, right) -> left.isAfter(right) ? left : right);
        }
        for (ProjectEntity project : projects.values()) {
            if (isRentIn(project)) continue;
            LocalDate lastEnd = latest.get(project.getProjectId());
            ContractEntity contract = contracts.get(project.getProjectId());
            LocalDate start = lastEnd == null ? firstDate(contract == null ? null : contract.getStartDate(), project.getCreateDate()) : lastEnd.plusDays(1);
            if (!validAsOf(start, today)) continue;
            int days = safeDays(start, today) + 1;
            if (days < UNRECONCILED_DAYS) continue;
            List<ScoreContribution> contributions = new ArrayList<>();
            addContribution(contributions, "UNRECONCILED_BASE", "存在运营结算节奏缺口", 20, true);
            addContribution(contributions, "UNRECONCILED_DAYS", "未对账时长阶梯", Math.min(35, ceilStep(days, 30) * 5), days);
            Map<String, Object> metrics = mapOf("lastSettledEnd", lastEnd == null ? null : lastEnd.toString(),
                    "unreconciledStart", start.toString(), "unreconciledDays", days,
                    "basis", lastEnd == null ? "CONTRACT_OR_PROJECT_START" : "LATEST_ACTIVE_FINANCIAL_SETTLEMENT_END");
            facts.get(project.getProjectId()).add(risk(project, UNRECONCILED, metrics,
                    lastEnd == null ? start : lastEnd, today, contributions,
                    "核对最近财务对账截止日，补齐未对账期间并确认责任人。"));
        }
        if (invalid > 0) warnings.add("未对账维度已排除 " + invalid + " 条截止日期无效或晚于今天的财务对账单。");
    }

    private void addReceivableRisks(AgentRuntimeRecords.Workspace workspace, Map<String, ProjectEntity> projects,
                                    Map<String, List<RiskFact>> facts,
                                    LocalDate today, List<String> warnings) {
        FinanceLedgerSnapshotModels.Query query = new FinanceLedgerSnapshotModels.Query();
        query.setAsOfDate(today.toString());
        query.setLimit(100);
        FinanceLedgerSnapshotModels.Result snapshot = financeLedgerSnapshotService.generate(
                workspace, query, FinanceLedgerSnapshotService.LedgerDirection.RENT_OUT);
        warnings.addAll(snapshot.getWarnings());
        if (snapshot.getRentOut() == null || snapshot.getRentOut().getProjects() == null) return;
        for (FinanceLedgerSnapshotModels.ProjectSummary row : snapshot.getRentOut().getProjects()) {
            if (!facts.containsKey(row.getProjectId()) || money(row.getOverdueOutstanding()).signum() <= 0) continue;
            LocalDate earliestDue = date(row.getEarliestDueDate());
            int days = earliestDue == null ? 0 : safeDays(earliestDue, today);
            List<ScoreContribution> contributions = new ArrayList<>();
            addContribution(contributions, "OVERDUE_RECEIVABLE_BASE", "存在逾期未清应收本金", 30, true);
            addContribution(contributions, "OVERDUE_DAYS", "逾期时长阶梯", Math.min(30, ceilStep(days, 30) * 5), days);
            addContribution(contributions, "OVERDUE_AMOUNT_CNY", "逾期金额档位", amountPoints(row.getOverdueOutstanding()), money(row.getOverdueOutstanding()));
            ProjectEntity project = projects.get(row.getProjectId());
            if (project == null) continue;
            Map<String, Object> metrics = mapOf("overdueOutstanding", money(row.getOverdueOutstanding()),
                    "outstandingPrincipalAsOf", money(row.getOutstandingPrincipalAsOf()),
                    "earliestDueDate", row.getEarliestDueDate(), "overdueDays", days,
                    "currency", "CNY", "dueDateBasis", "SETTLEMENT_PERIOD_END");
            facts.get(row.getProjectId()).add(risk(project, OVERDUE_RECEIVABLE, metrics,
                    earliestDue == null ? today : earliestDue, today, contributions,
                    "立即核对账期与登记回款，明确催缴责任人和下一次跟进时间。"));
        }
    }

    private void addActivityRisks(Scope scope, Map<String, ProjectEntity> projects, ReviewedDocuments documents,
                                  Map<String, List<RiskFact>> facts, LocalDate today, List<String> warnings) {
        List<ActivityObservation> observations = new ArrayList<>(documents.activities);
        List<String> ids = new ArrayList<>(projects.keySet());
        int invalid = 0;
        for (ReconciliationDocumentEntity row : reconciliationDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId())) continue;
            LocalDate date = date(row.getReconciliationDate());
            if (validAsOf(date, today)) observations.add(activity(row.getProjectId(), "MATERIAL_RECONCILIATION", date)); else invalid++;
        }
        for (SettlementDocumentEntity row : settlementDocumentMapper.selectList(documentScope(scope, ids))) {
            if (!inScope(scope, row.getCid(), row.getProjectId()) || !FINANCIAL_SETTLEMENT_TYPE.equals(row.getType())
                    || !(row.getStatus() == null || "ACTIVE".equalsIgnoreCase(row.getStatus()))) continue;
            LocalDate date = date(row.getEndDate());
            if (validAsOf(date, today)) observations.add(activity(row.getProjectId(), "FINANCIAL_RECONCILIATION", date)); else invalid++;
        }
        Map<String, ActivityObservation> latest = new HashMap<>();
        for (ActivityObservation value : observations) latest.merge(value.projectId, value,
                (left, right) -> left.date.isAfter(right.date) ? left : right);
        for (ProjectEntity project : projects.values()) {
            if (isCompleted(project)) continue;
            ActivityObservation observation = latest.get(project.getProjectId());
            LocalDate baseline = observation == null ? date(project.getCreateDate()) : observation.date;
            if (!validAsOf(baseline, today)) continue;
            int days = safeDays(baseline, today);
            if (days < INACTIVE_DAYS) continue;
            List<ScoreContribution> contributions = new ArrayList<>();
            addContribution(contributions, "INACTIVE_BASE", "进行中项目长期无已复核业务活动", 15, true);
            addContribution(contributions, "INACTIVE_DAYS", "无活动时长阶梯", Math.min(30, ceilStep(days, 30) * 5), days);
            Map<String, Object> metrics = mapOf("lastActivityDate", baseline.toString(),
                    "lastActivityType", observation == null ? "PROJECT_CREATED" : observation.type,
                    "inactiveDays", days, "reviewedDocumentsOnly", true, "thresholdDays", INACTIVE_DAYS);
            facts.get(project.getProjectId()).add(risk(project, INACTIVE_PROJECT, metrics, baseline, today,
                    contributions, "确认项目是否仍在推进；如继续，补录已复核业务进展；如结束，及时完成结项。"));
        }
        if (invalid > 0) warnings.add("长期无活动维度已排除 " + invalid + " 条日期无效或晚于今天的对账记录。");
    }

    private void addPendingReviewRisks(Map<String, ProjectEntity> projects, ReviewedDocuments documents,
                                       Map<String, List<RiskFact>> facts, LocalDate today) {
        Map<String, List<PendingObservation>> grouped = documents.pending.stream()
                .collect(Collectors.groupingBy(value -> value.projectId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<PendingObservation>> entry : grouped.entrySet()) {
            ProjectEntity project = projects.get(entry.getKey());
            if (project == null) continue;
            List<PendingObservation> rows = entry.getValue();
            LocalDate oldest = rows.stream().map(value -> value.date).filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
            Integer age = oldest == null ? null : safeDays(oldest, today);
            Map<String, Long> byType = rows.stream().collect(Collectors.groupingBy(value -> value.type,
                    LinkedHashMap::new, Collectors.counting()));
            List<ScoreContribution> contributions = new ArrayList<>();
            addContribution(contributions, "PENDING_REVIEW_BASE", "存在待审核项目业务单据", 15, true);
            addContribution(contributions, "PENDING_REVIEW_COUNT", "待审核单据数量", Math.min(25, rows.size() * 5), rows.size());
            addContribution(contributions, "OLDEST_PENDING_DAYS", "最老待审时长", age == null ? 0 : Math.min(20, ceilStep(age, 7) * 5), age);
            Map<String, Object> metrics = mapOf("pendingReviewCount", rows.size(), "oldestPendingDate", oldest == null ? null : oldest.toString(),
                    "oldestPendingDays", age, "documentTypeCounts", byType,
                    "reviewStatusBasis", "review_status=0 OR NULL");
            facts.get(project.getProjectId()).add(risk(project, PENDING_REVIEW, metrics, oldest == null ? today : oldest, today,
                    contributions, "优先审核最早待审单据，确认材料、日期与合同版本后再进入正式台账。"));
        }
    }

    private boolean matches(List<RiskFact> facts, Query query) {
        Set<String> actual = facts.stream().map(RiskFact::getRiskType).collect(Collectors.toSet());
        if ("ALL".equals(query.getMatchMode())) return actual.containsAll(query.getRiskTypes());
        return query.getRiskTypes().stream().anyMatch(actual::contains);
    }

    private ProjectAction projectAction(ProjectEntity project, List<RiskFact> source) {
        List<RiskFact> risks = source.stream().sorted(riskComparator()).collect(Collectors.toList());
        int primary = risks.stream().map(RiskFact::getScore).max(Integer::compareTo).orElse(0);
        int diversity = Math.min(20, Math.max(0, risks.size() - 1) * 5);
        List<ScoreContribution> contributions = new ArrayList<>();
        addContribution(contributions, "PRIMARY_RISK_SCORE", "最高单项风险分", primary, primary);
        addContribution(contributions, "ADDITIONAL_RISK_TYPES", "额外风险种类", diversity, Math.max(0, risks.size() - 1));
        int score = Math.min(100, sum(contributions));
        if (sum(contributions) > 100) {
            contributions.get(contributions.size() - 1).setPoints(Math.max(0, 100 - primary));
        }
        ProjectAction result = new ProjectAction();
        result.setProjectId(project.getProjectId());
        result.setProjectName(project.getProjectName());
        result.setProjectBusinessType(project.getProjectBusinessType());
        result.setProjectStatusFlag(project.getProjectStatusFlag());
        if (StringUtils.hasText(project.getManagerName())) result.setManagerName(project.getManagerName());
        result.setRiskTypes(risks.stream().map(RiskFact::getRiskType).distinct().collect(Collectors.toList()));
        result.setRiskCount(risks.size());
        result.setTotalScore(score);
        result.setScoreContributions(contributions);
        result.setSuggestedAction(risks.isEmpty() ? null : risks.get(0).getSuggestedAction());
        result.setRisks(risks);
        return result;
    }

    private RiskFact risk(ProjectEntity project, String type, Map<String, Object> metrics,
                          LocalDate evidenceDate, LocalDate asOfDate, List<ScoreContribution> contributions,
                          String suggestedAction) {
        RiskFact value = new RiskFact();
        value.setProjectId(project.getProjectId());
        value.setProjectName(project.getProjectName());
        value.setProjectBusinessType(project.getProjectBusinessType());
        value.setProjectStatusFlag(project.getProjectStatusFlag());
        if (StringUtils.hasText(project.getManagerName())) value.setManagerName(project.getManagerName());
        value.setRiskType(type);
        value.setRawMetrics(metrics);
        value.setEvidenceDate(evidenceDate == null ? null : evidenceDate.toString());
        value.setAsOfDate(asOfDate.toString());
        value.setScore(sum(contributions));
        value.setScoreContributions(contributions);
        value.setSuggestedAction(suggestedAction);
        return value;
    }

    private Comparator<ProjectAction> projectComparator() {
        return Comparator.comparing(ProjectAction::getTotalScore, Comparator.reverseOrder())
                .thenComparing(ProjectAction::getProjectId, Comparator.nullsLast(String::compareTo));
    }

    private Comparator<RiskFact> riskComparator() {
        return Comparator.comparing(RiskFact::getScore, Comparator.reverseOrder())
                .thenComparing(RiskFact::getEvidenceDate, Comparator.nullsLast(String::compareTo))
                .thenComparing(RiskFact::getProjectId, Comparator.nullsLast(String::compareTo))
                .thenComparing(RiskFact::getRiskType, Comparator.nullsLast(String::compareTo));
    }

    private void verifyResult(Result result) {
        for (ProjectAction project : result.getItems()) {
            if (sum(project.getScoreContributions()) != project.getTotalScore()) {
                throw new MyBizException("行动中心项目评分不可复算", "AGT500");
            }
            for (RiskFact risk : project.getRisks()) {
                if (sum(risk.getScoreContributions()) != risk.getScore()) {
                    throw new MyBizException("行动中心风险评分不可复算", "AGT500");
                }
            }
        }
    }

    private void verifyActions(List<ProjectAction> actions) {
        for (ProjectAction project : actions) {
            if (sum(project.getScoreContributions()) != project.getTotalScore()) {
                throw new MyBizException("行动中心项目评分不可复算", "AGT500");
            }
            for (RiskFact risk : project.getRisks()) {
                if (sum(risk.getScoreContributions()) != risk.getScore()) {
                    throw new MyBizException("行动中心风险评分不可复算", "AGT500");
                }
            }
        }
    }

    private List<String> coreWarnings() {
        List<String> warnings = new ArrayList<>();
        warnings.add("评分版本：" + OwnerActionCenterModels.SCORING_VERSION + "；分数仅用于当前可用维度的行动优先级，不代表经营损失概率。");
        warnings.add("材料数量严格按unit分组，跨根/吨/件等异质单位不合计、不比较、不参与数量评分；材料风险只按未归还种类数计分。");
        warnings.add("材料未归还与长期无活动只采用截至今天且review_status=1的项目业务单据；未复核和未来业务单据不进入正式事实。");
        return warnings;
    }

    private UnsupportedDimension unsupported(String riskType, String reason, String capability) {
        UnsupportedDimension value = new UnsupportedDimension();
        value.setRiskType(riskType); value.setReason(reason); value.setRequiredCapability(capability);
        return value;
    }

    private List<DimensionStatus> dimensionStatuses(Query query,
                                                    Map<String, List<RiskFact>> facts,
                                                    List<UnsupportedDimension> unsupported,
                                                    List<FailedDimension> failed,
                                                    int totalProjectCount,
                                                    Map<String, List<String>> nonApplicableProjectIds) {
        Map<String, UnsupportedDimension> unsupportedByType = unsupported.stream().collect(Collectors.toMap(
                UnsupportedDimension::getRiskType, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, FailedDimension> failedByType = failed.stream().collect(Collectors.toMap(
                FailedDimension::getRiskType, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<DimensionStatus> result = new ArrayList<>();
        for (String riskType : query.getRiskTypes()) {
            DimensionStatus status = new DimensionStatus();
            status.setRiskType(riskType);
            status.setItemCount((int) facts.values().stream().flatMap(Collection::stream)
                    .filter(item -> riskType.equals(item.getRiskType())).count());
            List<String> nonApplicable = nonApplicableProjectIds.getOrDefault(riskType, Collections.emptyList());
            status.setNonApplicableProjectIds(new ArrayList<>(nonApplicable));
            status.setNonApplicableProjectCount(nonApplicable.size());
            status.setApplicableProjectCount(Math.max(0, totalProjectCount - nonApplicable.size()));
            if (failedByType.containsKey(riskType)) {
                status.setStatus("FAILED");
                status.setReason(failedByType.get(riskType).getReason());
            } else if (unsupportedByType.containsKey(riskType)) {
                status.setStatus(unsupportedByType.get(riskType).getReason().contains("未开通") ? "DISABLED" : "UNSUPPORTED");
                status.setReason(unsupportedByType.get(riskType).getReason());
            } else if (!nonApplicable.isEmpty()) {
                status.setStatus("PARTIALLY_APPLICABLE");
                status.setReason("该维度仅适用于租出项目；所列租入项目为NOT_APPLICABLE。" );
            } else {
                status.setStatus("SUPPORTED");
            }
            result.add(status);
        }
        return result;
    }

    private FailedDimension failed(String riskType, MyBizException error) {
        FailedDimension value = new FailedDimension();
        value.setRiskType(riskType); value.setErrorCode(error.getErrorCode()); value.setReason(error.getErrorMessage());
        return value;
    }

    private FailedDimension failed(String riskType, RuntimeException error) {
        FailedDimension value = new FailedDimension();
        value.setRiskType(riskType);
        value.setErrorCode(error instanceof MyBizException ? ((MyBizException) error).getErrorCode() : "AGT500");
        value.setReason(error instanceof MyBizException
                ? ((MyBizException) error).getErrorMessage() : "该风险维度事实源读取失败");
        return value;
    }

    private void executeDimension(String riskType,
                                  boolean explicitRiskTypes,
                                  Query query,
                                  Map<String, List<RiskFact>> facts,
                                  List<FailedDimension> failed,
                                  List<String> warnings,
                                  Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            facts.values().forEach(values -> values.removeIf(item -> riskType.equals(item.getRiskType())));
            if ("ALL".equals(query.getMatchMode())
                    || (explicitRiskTypes && query.getRiskTypes().size() == 1)) {
                if (error instanceof MyBizException) throw error;
                throw new MyBizException("指定风险维度事实源读取失败，无法形成可靠结论", "AGT500");
            }
            FailedDimension dimension = failed(riskType, error);
            failed.add(dimension);
            warnings.add(riskType + " 维度核算失败，已从本次可用维度评分中排除：" + safe(dimension.getReason()));
        }
    }

    private boolean selected(Query query, String type) { return query.getRiskTypes().contains(type); }
    private boolean isReviewed(Integer value) { return value != null && value == 1; }
    private boolean isPendingReview(Integer value) { return value == null || value == 0; }
    private boolean isInvalidReviewStatus(Integer value) { return value != null && value != 0 && value != 1; }
    private boolean isCompleted(ProjectEntity project) { return project != null && "1".equals(project.getProjectStatusFlag()); }
    private boolean isRentIn(ProjectEntity project) { return project != null && "rent_in".equalsIgnoreCase(project.getProjectBusinessType()); }

    private Map<String, ProjectEntity> rentOutProjects(Map<String, ProjectEntity> projects,
                                                       String riskType,
                                                       Map<String, List<String>> nonApplicableProjectIds,
                                                       List<String> warnings) {
        List<String> nonApplicable = projects.values().stream().filter(this::isRentIn)
                .map(ProjectEntity::getProjectId).filter(StringUtils::hasText).sorted().collect(Collectors.toList());
        if (!nonApplicable.isEmpty()) {
            nonApplicableProjectIds.put(riskType, nonApplicable);
            warnings.add(riskType + " 仅适用于租出项目；当前范围有 " + nonApplicable.size()
                    + " 个租入项目标记为NOT_APPLICABLE，未将其解释为零风险。");
        }
        return projects.entrySet().stream().filter(entry -> !isRentIn(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }
    private boolean validAsOf(LocalDate value, LocalDate today) { return value != null && !value.isAfter(today); }
    private int safeDays(LocalDate start, LocalDate end) { return (int) Math.max(0L, ChronoUnit.DAYS.between(start, end)); }
    private int ceilStep(int value, int size) { return value <= 0 ? 0 : (value + size - 1) / size; }
    private int amountPoints(BigDecimal amount) { BigDecimal value = money(amount); if (value.compareTo(new BigDecimal("100000")) >= 0) return 20; if (value.compareTo(new BigDecimal("50000")) >= 0) return 15; if (value.compareTo(new BigDecimal("10000")) >= 0) return 10; return 5; }
    private int sum(List<ScoreContribution> values) { return values.stream().map(ScoreContribution::getPoints).filter(java.util.Objects::nonNull).reduce(0, Integer::sum); }

    private void addContribution(List<ScoreContribution> values, String code, String label, int points, Object raw) {
        ScoreContribution value = new ScoreContribution(); value.setCode(code); value.setLabel(label); value.setPoints(points); value.setRawValue(raw); values.add(value);
    }

    private MaterialBalance material(Map<String, MaterialBalance> balances, String projectId, String materialId, String specification, String unit) {
        String key = safe(projectId) + "\u0000" + safe(materialId) + "\u0000" + safe(specification) + "\u0000" + safe(unit);
        return balances.computeIfAbsent(key, ignored -> new MaterialBalance(projectId, unit));
    }

    private BigDecimal quantity(Double countingQuantity, Integer fallback) {
        if (countingQuantity != null) return BigDecimal.valueOf(countingQuantity).setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(fallback == null ? 0 : fallback).setScale(2, RoundingMode.HALF_UP);
    }

    private ActivityObservation activity(String projectId, String type, LocalDate date) { return new ActivityObservation(projectId, type, date); }
    private void addPending(ReviewedDocuments docs, String projectId, String type, LocalDate date, LocalDate today) {
        docs.pending.add(new PendingObservation(projectId, type, validAsOf(date, today) ? date : null));
    }

    private boolean inScope(Scope scope, String cid, String projectId) {
        return scope.cid.equals(cid) && ("ALL".equals(scope.selectionMode) || scope.projectIds.contains(projectId));
    }

    private LocalDate firstDate(String... values) { for (String value : values) { LocalDate parsed = date(value); if (parsed != null) return parsed; } return null; }
    private LocalDate date(String value) { try { return StringUtils.hasText(value) && value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null; } catch (Exception ignored) { return null; } }
    private BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP); }
    private int compareLatest(String leftDate, String leftId, String rightDate, String rightId) { int date = safe(leftDate).compareTo(safe(rightDate)); return date != 0 ? date : safe(leftId).compareTo(safe(rightId)); }
    private String firstText(String... values) { for (String value : values) if (StringUtils.hasText(value)) return value.trim(); return null; }
    private String safe(String value) { return value == null ? "" : value; }
    private List<String> deduplicate(Collection<String> values) { return new ArrayList<>(new LinkedHashSet<>(values)); }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class Scope {
        private final String cid; private final String selectionMode; private final Set<String> projectIds;
        private Scope(String cid, String selectionMode, Set<String> projectIds) { this.cid = cid; this.selectionMode = selectionMode; this.projectIds = projectIds; }
    }
    private static final class MaterialBalance {
        private final String projectId; private final String unit; private BigDecimal rented = BigDecimal.ZERO; private BigDecimal returned = BigDecimal.ZERO;
        private MaterialBalance(String projectId, String unit) { this.projectId = projectId; this.unit = unit; }
        private BigDecimal outstanding() { return rented.subtract(returned).max(BigDecimal.ZERO); }
    }
    private static final class ActivityObservation {
        private final String projectId; private final String type; private final LocalDate date;
        private ActivityObservation(String projectId, String type, LocalDate date) { this.projectId = projectId; this.type = type; this.date = date; }
    }
    private static final class PendingObservation {
        private final String projectId; private final String type; private final LocalDate date;
        private PendingObservation(String projectId, String type, LocalDate date) { this.projectId = projectId; this.type = type; this.date = date; }
    }
    private static final class ReviewedDocuments {
        private final Set<String> rentIds = new HashSet<>(); private final Set<String> returnIds = new HashSet<>();
        private final Set<String> rentInIds = new HashSet<>(); private final Set<String> rentInReturnIds = new HashSet<>();
        private final List<ActivityObservation> activities = new ArrayList<>(); private final List<PendingObservation> pending = new ArrayList<>();
    }
}
