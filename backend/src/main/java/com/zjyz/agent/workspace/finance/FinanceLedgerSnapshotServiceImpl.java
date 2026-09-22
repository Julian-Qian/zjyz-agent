package com.zjyz.agent.workspace.finance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.CashSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.DirectionSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.ProjectSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Result;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CustomerPaymentAllocationMapper;
import com.zjyz.dao.CustomerPaymentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementPayablePeriodMapper;
import com.zjyz.dao.SettlementReceivablePeriodMapper;
import com.zjyz.dao.SupplierPaymentAllocationMapper;
import com.zjyz.dao.SupplierPaymentMapper;
import com.zjyz.pojo.entity.CustomerPaymentAllocationEntity;
import com.zjyz.pojo.entity.CustomerPaymentEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementPayablePeriodEntity;
import com.zjyz.pojo.entity.SettlementReceivablePeriodEntity;
import com.zjyz.pojo.entity.SupplierPaymentAllocationEntity;
import com.zjyz.pojo.entity.SupplierPaymentEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FinanceLedgerSnapshotServiceImpl implements FinanceLedgerSnapshotService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String PRINCIPAL = "SETTLEMENT_PRINCIPAL";
    private static final String ACTIVE = "ACTIVE";
    private static final String VOID = "VOID";
    private static final String RENT_OUT = "RENT_OUT";
    private static final String RENT_IN = "RENT_IN";

    private final SettlementReceivablePeriodMapper receivablePeriodMapper;
    private final SettlementPayablePeriodMapper payablePeriodMapper;
    private final CustomerPaymentMapper customerPaymentMapper;
    private final SupplierPaymentMapper supplierPaymentMapper;
    private final CustomerPaymentAllocationMapper customerAllocationMapper;
    private final SupplierPaymentAllocationMapper supplierAllocationMapper;
    private final ProjectMapper projectMapper;
    private final Clock clock;

    @Autowired
    public FinanceLedgerSnapshotServiceImpl(SettlementReceivablePeriodMapper receivablePeriodMapper,
                                            SettlementPayablePeriodMapper payablePeriodMapper,
                                            CustomerPaymentMapper customerPaymentMapper,
                                            SupplierPaymentMapper supplierPaymentMapper,
                                            CustomerPaymentAllocationMapper customerAllocationMapper,
                                            SupplierPaymentAllocationMapper supplierAllocationMapper,
                                            ProjectMapper projectMapper) {
        this(receivablePeriodMapper, payablePeriodMapper, customerPaymentMapper, supplierPaymentMapper,
                customerAllocationMapper, supplierAllocationMapper, projectMapper, Clock.systemDefaultZone());
    }

    FinanceLedgerSnapshotServiceImpl(SettlementReceivablePeriodMapper receivablePeriodMapper,
                                     SettlementPayablePeriodMapper payablePeriodMapper,
                                     CustomerPaymentMapper customerPaymentMapper,
                                     SupplierPaymentMapper supplierPaymentMapper,
                                     CustomerPaymentAllocationMapper customerAllocationMapper,
                                     SupplierPaymentAllocationMapper supplierAllocationMapper,
                                     ProjectMapper projectMapper,
                                     Clock clock) {
        this.receivablePeriodMapper = receivablePeriodMapper;
        this.payablePeriodMapper = payablePeriodMapper;
        this.customerPaymentMapper = customerPaymentMapper;
        this.supplierPaymentMapper = supplierPaymentMapper;
        this.customerAllocationMapper = customerAllocationMapper;
        this.supplierAllocationMapper = supplierAllocationMapper;
        this.projectMapper = projectMapper;
        this.clock = clock;
    }

    @Override
    public Result generate(AgentRuntimeRecords.Workspace workspace, Query input) {
        return generate(workspace, input, LedgerDirection.BOTH);
    }

    @Override
    public Result generate(AgentRuntimeRecords.Workspace workspace, Query input, LedgerDirection direction) {
        Scope scope = requireScope(workspace);
        if (direction == null) {
            throw new MyBizException("财务台账核算方向不能为空", "FIN400");
        }
        Query query = normalizeQuery(input);
        LocalDate asOf = date(query.getAsOfDate(), "查询截止日期");
        LocalDate rangeStart = StringUtils.hasText(query.getStartDate())
                ? date(query.getStartDate(), "统计开始日期") : null;
        LocalDate rangeEnd = StringUtils.hasText(query.getEndDate())
                ? date(query.getEndDate(), "统计结束日期") : null;
        if ((rangeStart == null) != (rangeEnd == null)) {
            throw new MyBizException("统计开始日期和结束日期必须同时提供", "FIN400");
        }
        if (rangeStart != null && (rangeStart.isAfter(rangeEnd) || rangeEnd.isAfter(asOf))) {
            throw new MyBizException("统计日期范围必须有效且不能晚于查询截止日期", "FIN400");
        }

        Map<String, ProjectEntity> projects = loadProjects(scope);
        List<String> warnings = new ArrayList<>();
        DirectionSummary rentOut = direction == LedgerDirection.RENT_IN
                ? summarize(RENT_OUT, Collections.emptyList())
                : buildDirection(RENT_OUT, scope, projects, query, asOf, rangeStart, rangeEnd, warnings);
        DirectionSummary rentIn = direction == LedgerDirection.RENT_OUT
                ? summarize(RENT_IN, Collections.emptyList())
                : buildDirection(RENT_IN, scope, projects, query, asOf, rangeStart, rangeEnd, warnings);

        warnings.add("余额仅表示本地今天的当前台账快照；当前数据模型没有状态历史版本，不能重算历史余额。");
        warnings.add("期间实收和实付按非删除付款记录的payment_date统计；本金已收和已付仅指截止今天的ACTIVE本金核销，两者不得互相替代。");
        warnings.add("到期口径采用正式结算账期结束日，不代表合同约定付款期限。 dueDateBasis=SETTLEMENT_PERIOD_END");
        Result result = new Result();
        result.setQuery(query);
        result.setRentOut(rentOut);
        result.setRentIn(rentIn);
        result.setWarnings(new ArrayList<>(new LinkedHashSet<>(warnings)));
        return result;
    }

    private DirectionSummary buildDirection(String direction,
                                            Scope scope,
                                            Map<String, ProjectEntity> projects,
                                            Query query,
                                            LocalDate asOf,
                                            LocalDate rangeStart,
                                            LocalDate rangeEnd,
                                            List<String> warnings) {
        List<LedgerPeriod> periods = RENT_OUT.equals(direction)
                ? loadReceivablePeriods(scope, asOf) : loadPayablePeriods(scope, asOf);
        List<LedgerPayment> payments = RENT_OUT.equals(direction)
                ? loadCustomerPayments(scope, asOf) : loadSupplierPayments(scope, asOf);
        Map<String, LedgerPeriod> periodsById = periods.stream().collect(Collectors.toMap(
                item -> item.id, item -> item, (left, right) -> left, LinkedHashMap::new));
        List<LedgerAllocation> allocations = RENT_OUT.equals(direction)
                ? loadCustomerAllocations(scope, periodsById.keySet())
                : loadSupplierAllocations(scope, periodsById.keySet());

        Map<String, LedgerPayment> paymentsById = payments.stream().collect(Collectors.toMap(
                item -> item.id, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, BigDecimal> allocatedByPeriod = new HashMap<>();
        Map<String, BigDecimal> allocatedByPayment = new HashMap<>();
        Map<String, BigDecimal> periodRangeAllocatedByPayment = new HashMap<>();
        for (LedgerAllocation allocation : allocations) {
            if (!inScope(scope, allocation.cid, allocation.projectId)
                    || !ACTIVE.equalsIgnoreCase(allocation.status)
                    || !PRINCIPAL.equalsIgnoreCase(allocation.targetType)
                    || !periodsById.containsKey(allocation.targetId)) {
                continue;
            }
            LocalDate effective = dateIntegrity(allocation.effectiveDate, "核销记录 " + allocation.id + " 生效日期");
            if (effective.isAfter(asOf)) {
                continue;
            }
            LedgerPeriod period = periodsById.get(allocation.targetId);
            LedgerPayment payment = paymentsById.get(allocation.paymentId);
            if (payment == null) {
                integrity("核销记录 " + allocation.id + " 缺少截止日前有效付款单");
            }
            if (!period.projectId.equals(allocation.projectId)
                    || !payment.projectId.equals(allocation.projectId)) {
                integrity("核销记录 " + allocation.id + " 的项目与本金或付款单不一致");
            }
            if (!principalEligible(payment.paymentType)) {
                integrity("核销记录 " + allocation.id + " 引用了不能冲抵本金的付款类型");
            }
            BigDecimal amount = money(allocation.amount);
            if (amount.signum() <= 0) {
                integrity("核销记录 " + allocation.id + " 的金额必须大于0");
            }
            allocatedByPeriod.merge(period.id, amount, BigDecimal::add);
            allocatedByPayment.merge(payment.id, amount, BigDecimal::add);
            if (inRange(effective, rangeStart, rangeEnd)) {
                periodRangeAllocatedByPayment.merge(payment.id, amount, BigDecimal::add);
            }
        }

        Map<String, ProjectAccumulator> projectAccumulators = new LinkedHashMap<>();
        for (LedgerPeriod period : periods) {
            ProjectEntity project = requireProject(projects, period.projectId, direction);
            BigDecimal principal = money(period.principal);
            if (principal.signum() < 0) {
                integrity("账期 " + period.id + " 的本金不能小于0");
            }
            BigDecimal allocated = money(allocatedByPeriod.get(period.id));
            if (allocated.compareTo(principal) > 0) {
                integrity("账期 " + period.id + " 的ACTIVE核销金额超过本金");
            }
            BigDecimal outstanding = principal.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            LocalDate due = optionalDate(period.dueDate);
            if (due == null) {
                warnings.add("账期 " + period.id + " 的到期日无效，已计入本金但未计入到期分组。");
            }
            projectAccumulators.computeIfAbsent(period.projectId,
                    id -> new ProjectAccumulator(project, direction))
                    .addPeriod(principal, allocated, outstanding, due, asOf);
        }

        for (LedgerPayment payment : payments) {
            ProjectEntity project = requireProject(projects, payment.projectId, direction);
            BigDecimal actual = money(payment.actualAmount);
            if (actual.signum() <= 0) {
                integrity("付款单 " + payment.id + " 的实付金额必须大于0");
            }
            BigDecimal allocated = money(allocatedByPayment.get(payment.id));
            if (allocated.compareTo(actual) > 0) {
                integrity("付款单 " + payment.id + " 的ACTIVE本金核销金额超过实付金额");
            }
            LocalDate paymentDate = dateIntegrity(payment.paymentDate, "付款单 " + payment.id + " 付款日期");
            ProjectAccumulator accumulator = projectAccumulators.computeIfAbsent(payment.projectId,
                    id -> new ProjectAccumulator(project, direction));
            accumulator.addPayment(payment.paymentType, actual, allocated, paymentDate,
                    rangeStart, rangeEnd, money(periodRangeAllocatedByPayment.get(payment.id)));
        }

        List<ProjectSummary> projectRows = projectAccumulators.values().stream()
                .map(ProjectAccumulator::toSummary)
                .sorted(Comparator.comparing(ProjectSummary::getOutstandingPrincipalAsOf,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProjectSummary::getProjectId, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
        DirectionSummary summary = summarize(direction, projectRows);
        verifyDirection(summary);
        return summary;
    }

    private List<LedgerPeriod> loadReceivablePeriods(Scope scope, LocalDate asOf) {
        QueryWrapper<SettlementReceivablePeriodEntity> wrapper = new QueryWrapper<SettlementReceivablePeriodEntity>()
                .eq("cid", scope.cid).ne("status", VOID);
        applyScope(wrapper, scope);
        List<LedgerPeriod> result = new ArrayList<>();
        for (SettlementReceivablePeriodEntity entity : receivablePeriodMapper.selectList(wrapper)) {
            if (!inScope(scope, entity.getCid(), entity.getProjectId()) || VOID.equalsIgnoreCase(entity.getStatus())) continue;
            if (createdAfter(entity.getCreateTime(), asOf, entity.getPeriodId())) continue;
            result.add(new LedgerPeriod(entity.getPeriodId(), entity.getCid(), entity.getProjectId(),
                    entity.getPrincipalAmount(), entity.getDueDate()));
        }
        return result;
    }

    private List<LedgerPeriod> loadPayablePeriods(Scope scope, LocalDate asOf) {
        QueryWrapper<SettlementPayablePeriodEntity> wrapper = new QueryWrapper<SettlementPayablePeriodEntity>()
                .eq("cid", scope.cid).ne("status", VOID);
        applyScope(wrapper, scope);
        List<LedgerPeriod> result = new ArrayList<>();
        for (SettlementPayablePeriodEntity entity : payablePeriodMapper.selectList(wrapper)) {
            if (!inScope(scope, entity.getCid(), entity.getProjectId()) || VOID.equalsIgnoreCase(entity.getStatus())) continue;
            if (createdAfter(entity.getCreateTime(), asOf, entity.getPeriodId())) continue;
            result.add(new LedgerPeriod(entity.getPeriodId(), entity.getCid(), entity.getProjectId(),
                    entity.getPrincipalAmount(), entity.getDueDate()));
        }
        return result;
    }

    private List<LedgerPayment> loadCustomerPayments(Scope scope, LocalDate asOf) {
        QueryWrapper<CustomerPaymentEntity> wrapper = new QueryWrapper<CustomerPaymentEntity>()
                .eq("cid", scope.cid).eq("is_deleted", 0);
        applyScope(wrapper, scope);
        List<LedgerPayment> result = new ArrayList<>();
        for (CustomerPaymentEntity entity : customerPaymentMapper.selectList(wrapper)) {
            if (!inScope(scope, entity.getCid(), entity.getProjectId())
                    || (entity.getIsDeleted() != null && entity.getIsDeleted() != 0)) continue;
            LocalDate paymentDate = dateIntegrity(entity.getPaymentDate(), "付款单 " + entity.getCustomerPaymentId() + " 付款日期");
            if (!paymentDate.isAfter(asOf)) {
                result.add(new LedgerPayment(entity.getCustomerPaymentId(), entity.getCid(), entity.getProjectId(),
                        entity.getPaymentDate(), entity.getPaymentType(), entity.getActualAmount()));
            }
        }
        return result;
    }

    private List<LedgerPayment> loadSupplierPayments(Scope scope, LocalDate asOf) {
        QueryWrapper<SupplierPaymentEntity> wrapper = new QueryWrapper<SupplierPaymentEntity>()
                .eq("cid", scope.cid).eq("is_deleted", 0);
        applyScope(wrapper, scope);
        List<LedgerPayment> result = new ArrayList<>();
        for (SupplierPaymentEntity entity : supplierPaymentMapper.selectList(wrapper)) {
            if (!inScope(scope, entity.getCid(), entity.getProjectId())
                    || (entity.getIsDeleted() != null && entity.getIsDeleted() != 0)) continue;
            LocalDate paymentDate = dateIntegrity(entity.getPaymentDate(), "付款单 " + entity.getSupplierPaymentId() + " 付款日期");
            if (!paymentDate.isAfter(asOf)) {
                result.add(new LedgerPayment(entity.getSupplierPaymentId(), entity.getCid(), entity.getProjectId(),
                        entity.getPaymentDate(), entity.getPaymentType(), entity.getActualAmount()));
            }
        }
        return result;
    }

    private List<LedgerAllocation> loadCustomerAllocations(Scope scope, Collection<String> periodIds) {
        if (periodIds.isEmpty()) return Collections.emptyList();
        List<LedgerAllocation> result = new ArrayList<>();
        for (List<String> ids : batches(periodIds, 500)) {
            List<CustomerPaymentAllocationEntity> rows = customerAllocationMapper.selectList(
                    new QueryWrapper<CustomerPaymentAllocationEntity>().eq("cid", scope.cid)
                            .eq("target_type", PRINCIPAL).eq("allocation_status", ACTIVE).in("target_id", ids));
            for (CustomerPaymentAllocationEntity entity : rows) {
                result.add(new LedgerAllocation(entity.getAllocationId(), entity.getCid(), entity.getProjectId(),
                        entity.getCustomerPaymentId(), entity.getTargetType(), entity.getTargetId(),
                        entity.getAllocationAmount(), entity.getEffectivePaymentDate(), entity.getAllocationStatus()));
            }
        }
        return result;
    }

    private List<LedgerAllocation> loadSupplierAllocations(Scope scope, Collection<String> periodIds) {
        if (periodIds.isEmpty()) return Collections.emptyList();
        List<LedgerAllocation> result = new ArrayList<>();
        for (List<String> ids : batches(periodIds, 500)) {
            List<SupplierPaymentAllocationEntity> rows = supplierAllocationMapper.selectList(
                    new QueryWrapper<SupplierPaymentAllocationEntity>().eq("cid", scope.cid)
                            .eq("target_type", PRINCIPAL).eq("allocation_status", ACTIVE).in("target_id", ids));
            for (SupplierPaymentAllocationEntity entity : rows) {
                result.add(new LedgerAllocation(entity.getAllocationId(), entity.getCid(), entity.getProjectId(),
                        entity.getSupplierPaymentId(), entity.getTargetType(), entity.getTargetId(),
                        entity.getAllocationAmount(), entity.getEffectivePaymentDate(), entity.getAllocationStatus()));
            }
        }
        return result;
    }

    private Map<String, ProjectEntity> loadProjects(Scope scope) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>().eq("cid", scope.cid);
        applyScope(wrapper, scope);
        Map<String, ProjectEntity> result = new HashMap<>();
        for (ProjectEntity project : projectMapper.selectList(wrapper)) {
            if (inScope(scope, project.getCid(), project.getProjectId())) {
                result.put(project.getProjectId(), project);
            }
        }
        return result;
    }

    private DirectionSummary summarize(String direction, List<ProjectSummary> rows) {
        DirectionSummary summary = new DirectionSummary();
        summary.setBusinessDirection(direction);
        summary.setProjects(rows);
        summary.setProjectCount(rows.size());
        summary.setPeriodCount(rows.stream().map(ProjectSummary::getPeriodCount).filter(value -> value != null)
                .reduce(0, Integer::sum));
        summary.setPostedPrincipal(sum(rows, ProjectSummary::getPostedPrincipal));
        summary.setAllocatedPrincipalAsOf(sum(rows, ProjectSummary::getAllocatedPrincipalAsOf));
        summary.setOutstandingPrincipalAsOf(sum(rows, ProjectSummary::getOutstandingPrincipalAsOf));
        summary.setDueAsOfOutstanding(sum(rows, ProjectSummary::getDueAsOfOutstanding));
        summary.setOverdueOutstanding(sum(rows, ProjectSummary::getOverdueOutstanding));
        summary.setDueTodayOutstanding(sum(rows, ProjectSummary::getDueTodayOutstanding));
        summary.setCumulativeCash(sumCash(rows, false));
        summary.setPeriodCash(sumCash(rows, true));
        return summary;
    }

    private CashSummary sumCash(List<ProjectSummary> rows, boolean period) {
        CashSummary result = emptyCash();
        if (period) {
            result.setTotalRegistered(sum(rows, ProjectSummary::getPeriodRegisteredCash));
            result.setPrincipalEligible(sum(rows, ProjectSummary::getPeriodPrincipalEligibleCash));
            result.setRent(sum(rows, ProjectSummary::getPeriodCashRent));
            result.setCompensation(sum(rows, ProjectSummary::getPeriodCashCompensation));
            result.setDeposit(sum(rows, ProjectSummary::getPeriodCashDeposit));
            result.setLateFee(sum(rows, ProjectSummary::getPeriodCashLateFee));
            result.setOther(sum(rows, ProjectSummary::getPeriodCashOther));
            return result;
        }
        result.setTotalRegistered(sum(rows, ProjectSummary::getRegisteredCash));
        result.setPrincipalEligible(sum(rows, ProjectSummary::getPrincipalEligibleCash));
        result.setRent(sum(rows, ProjectSummary::getCashRent));
        result.setCompensation(sum(rows, ProjectSummary::getCashCompensation));
        result.setDeposit(sum(rows, ProjectSummary::getCashDeposit));
        result.setLateFee(sum(rows, ProjectSummary::getCashLateFee));
        result.setOther(sum(rows, ProjectSummary::getCashOther));
        result.setAllocatedPrincipalAsOf(sum(rows, ProjectSummary::getAllocatedPrincipalAsOf));
        result.setUnallocatedPrincipalAsOf(sum(rows, ProjectSummary::getUnallocatedPrincipalCash));
        return result;
    }

    private void verifyDirection(DirectionSummary summary) {
        BigDecimal rebuilt = money(summary.getAllocatedPrincipalAsOf())
                .add(money(summary.getOutstandingPrincipalAsOf()));
        if (rebuilt.compareTo(money(summary.getPostedPrincipal())) != 0) {
            integrity("财务本金汇总不守恒");
        }
        if (money(summary.getAllocatedPrincipalAsOf())
                .compareTo(money(summary.getCumulativeCash().getPrincipalEligible())) > 0) {
            integrity("本金核销金额超过可冲抵本金的登记实收或实付");
        }
    }

    private ProjectEntity requireProject(Map<String, ProjectEntity> projects, String projectId, String direction) {
        ProjectEntity project = projects.get(projectId);
        if (project == null) {
            integrity("财务台账引用了当前范围内不存在的项目 " + projectId);
        }
        String expected = RENT_IN.equals(direction) ? "rent_in" : "rent_out";
        if (!expected.equalsIgnoreCase(project.getProjectBusinessType())) {
            integrity("项目 " + projectId + " 的业务类型与财务台账方向不一致");
        }
        return project;
    }

    private Scope requireScope(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !StringUtils.hasText(workspace.getCid())) {
            throw new MyBizException("财务工作空间上下文缺失", "AGT400");
        }
        String selectionMode = workspace.getSelectionMode() == null ? "" : workspace.getSelectionMode().trim().toUpperCase(Locale.ROOT);
        List<String> ids = workspace.getProjectIds() == null ? Collections.emptyList()
                : workspace.getProjectIds().stream().filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        if ("ALL".equals(selectionMode)) {
            if (!ids.isEmpty()) throw new MyBizException("ALL范围不能携带项目ID", "AGT400");
            return new Scope(workspace.getCid(), Collections.emptySet(), true);
        }
        if (!"EXPLICIT".equals(selectionMode) || ids.isEmpty()) {
            throw new MyBizException("财务工具仅支持ALL或非空EXPLICIT项目范围", "AGT400");
        }
        return new Scope(workspace.getCid(), new LinkedHashSet<>(ids), false);
    }

    private Query normalizeQuery(Query input) {
        Query query = input == null ? new Query() : input;
        LocalDate today = LocalDate.now(clock);
        if (!StringUtils.hasText(query.getAsOfDate())) query.setAsOfDate(today.toString());
        LocalDate asOf = date(query.getAsOfDate(), "查询截止日期");
        if (!asOf.equals(today)) {
            throw new MyBizException("当前财务台账只支持核算本地今天的余额，不能查询过去或未来余额", "FIN400");
        }
        if (query.getLimit() == null) query.setLimit(20);
        if (query.getLimit() < 1 || query.getLimit() > 100) {
            throw new MyBizException("limit仅支持1到100", "FIN400");
        }
        return query;
    }

    private boolean createdAfter(LocalDateTime createTime, LocalDate asOf, String periodId) {
        if (createTime == null) integrity("账期 " + periodId + " 缺少创建时间，无法按截止日核算");
        return createTime.toLocalDate().isAfter(asOf);
    }

    private boolean inScope(Scope scope, String cid, String projectId) {
        return scope.cid.equals(cid) && StringUtils.hasText(projectId)
                && (scope.all || scope.projectIds.contains(projectId));
    }

    private <T> void applyScope(QueryWrapper<T> wrapper, Scope scope) {
        if (!scope.all) wrapper.in("project_id", scope.projectIds);
    }

    private LocalDate date(String value, String label) {
        LocalDate result = optionalDate(value);
        if (result == null) throw new MyBizException(label + "格式必须为yyyy-MM-dd", "FIN400");
        return result;
    }

    private LocalDate dateIntegrity(String value, String label) {
        LocalDate result = optionalDate(value);
        if (result == null) integrity(label + "无效");
        return result;
    }

    private LocalDate optionalDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean inRange(LocalDate value, LocalDate start, LocalDate end) {
        return start != null && end != null && !value.isBefore(start) && !value.isAfter(end);
    }

    private boolean principalEligible(String type) {
        return "租金".equals(type) || "赔偿".equals(type);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<ProjectSummary> rows,
                           java.util.function.Function<ProjectSummary, BigDecimal> getter) {
        return rows.stream().map(getter).filter(value -> value != null)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private List<List<String>> batches(Collection<String> values, int size) {
        List<String> source = new ArrayList<>(values);
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index += size) {
            result.add(source.subList(index, Math.min(index + size, source.size())));
        }
        return result;
    }

    private CashSummary emptyCash() {
        CashSummary result = new CashSummary();
        result.setTotalRegistered(ZERO);
        result.setPrincipalEligible(ZERO);
        result.setRent(ZERO);
        result.setCompensation(ZERO);
        result.setDeposit(ZERO);
        result.setLateFee(ZERO);
        result.setOther(ZERO);
        result.setAllocatedPrincipalAsOf(ZERO);
        result.setUnallocatedPrincipalAsOf(ZERO);
        return result;
    }

    private void integrity(String message) {
        throw new MyBizException(message + "，请先修复财务台账", "FIN409");
    }

    private static final class Scope {
        private final String cid;
        private final Set<String> projectIds;
        private final boolean all;

        private Scope(String cid, Set<String> projectIds, boolean all) {
            this.cid = cid;
            this.projectIds = projectIds;
            this.all = all;
        }
    }

    private static final class LedgerPeriod {
        private final String id;
        private final String cid;
        private final String projectId;
        private final BigDecimal principal;
        private final String dueDate;

        private LedgerPeriod(String id, String cid, String projectId, BigDecimal principal, String dueDate) {
            this.id = id;
            this.cid = cid;
            this.projectId = projectId;
            this.principal = principal;
            this.dueDate = dueDate;
        }
    }

    private static final class LedgerPayment {
        private final String id;
        private final String cid;
        private final String projectId;
        private final String paymentDate;
        private final String paymentType;
        private final BigDecimal actualAmount;

        private LedgerPayment(String id, String cid, String projectId, String paymentDate,
                              String paymentType, BigDecimal actualAmount) {
            this.id = id;
            this.cid = cid;
            this.projectId = projectId;
            this.paymentDate = paymentDate;
            this.paymentType = paymentType;
            this.actualAmount = actualAmount;
        }
    }

    private static final class LedgerAllocation {
        private final String id;
        private final String cid;
        private final String projectId;
        private final String paymentId;
        private final String targetType;
        private final String targetId;
        private final BigDecimal amount;
        private final String effectiveDate;
        private final String status;

        private LedgerAllocation(String id, String cid, String projectId, String paymentId,
                                 String targetType, String targetId, BigDecimal amount,
                                 String effectiveDate, String status) {
            this.id = id;
            this.cid = cid;
            this.projectId = projectId;
            this.paymentId = paymentId;
            this.targetType = targetType;
            this.targetId = targetId;
            this.amount = amount;
            this.effectiveDate = effectiveDate;
            this.status = status;
        }
    }

    private final class ProjectAccumulator {
        private final ProjectEntity project;
        private final String direction;
        private BigDecimal posted = ZERO;
        private BigDecimal allocated = ZERO;
        private BigDecimal outstanding = ZERO;
        private BigDecimal due = ZERO;
        private BigDecimal overdue = ZERO;
        private BigDecimal dueToday = ZERO;
        private BigDecimal registeredCash = ZERO;
        private BigDecimal eligibleCash = ZERO;
        private BigDecimal unallocatedCash = ZERO;
        private BigDecimal periodRegisteredCash = ZERO;
        private BigDecimal periodEligibleCash = ZERO;
        private BigDecimal cashRent = ZERO;
        private BigDecimal cashCompensation = ZERO;
        private BigDecimal cashDeposit = ZERO;
        private BigDecimal cashLateFee = ZERO;
        private BigDecimal cashOther = ZERO;
        private BigDecimal periodCashRent = ZERO;
        private BigDecimal periodCashCompensation = ZERO;
        private BigDecimal periodCashDeposit = ZERO;
        private BigDecimal periodCashLateFee = ZERO;
        private BigDecimal periodCashOther = ZERO;
        private int periodCount;
        private LocalDate earliestDue;

        private ProjectAccumulator(ProjectEntity project, String direction) {
            this.project = project;
            this.direction = direction;
        }

        private void addPeriod(BigDecimal principal, BigDecimal allocatedAmount,
                               BigDecimal outstandingAmount, LocalDate dueDate, LocalDate asOf) {
            posted = posted.add(principal);
            allocated = allocated.add(allocatedAmount);
            outstanding = outstanding.add(outstandingAmount);
            periodCount++;
            if (outstandingAmount.signum() > 0 && dueDate != null) {
                if (!dueDate.isAfter(asOf)) due = due.add(outstandingAmount);
                if (dueDate.isBefore(asOf)) overdue = overdue.add(outstandingAmount);
                if (dueDate.isEqual(asOf)) dueToday = dueToday.add(outstandingAmount);
                if (earliestDue == null || dueDate.isBefore(earliestDue)) earliestDue = dueDate;
            }
        }

        private void addPayment(String type, BigDecimal actual, BigDecimal allocatedAmount,
                                LocalDate paymentDate, LocalDate rangeStart, LocalDate rangeEnd,
                                BigDecimal periodAllocated) {
            registeredCash = registeredCash.add(actual);
            addCashType(type, actual, false);
            if (principalEligible(type)) {
                eligibleCash = eligibleCash.add(actual);
                unallocatedCash = unallocatedCash.add(actual.subtract(allocatedAmount));
            }
            if (inRange(paymentDate, rangeStart, rangeEnd)) {
                periodRegisteredCash = periodRegisteredCash.add(actual);
                addCashType(type, actual, true);
                if (principalEligible(type)) periodEligibleCash = periodEligibleCash.add(actual);
                if (periodAllocated.compareTo(actual) > 0) {
                    integrity("付款日期范围内本金核销金额超过对应付款金额");
                }
            }
        }

        private void addCashType(String type, BigDecimal amount, boolean period) {
            if ("租金".equals(type)) {
                if (period) periodCashRent = periodCashRent.add(amount); else cashRent = cashRent.add(amount);
            } else if ("赔偿".equals(type)) {
                if (period) periodCashCompensation = periodCashCompensation.add(amount); else cashCompensation = cashCompensation.add(amount);
            } else if ("押金".equals(type)) {
                if (period) periodCashDeposit = periodCashDeposit.add(amount); else cashDeposit = cashDeposit.add(amount);
            } else if ("滞纳金".equals(type)) {
                if (period) periodCashLateFee = periodCashLateFee.add(amount); else cashLateFee = cashLateFee.add(amount);
            } else {
                if (period) periodCashOther = periodCashOther.add(amount); else cashOther = cashOther.add(amount);
            }
        }

        private ProjectSummary toSummary() {
            ProjectSummary row = new ProjectSummary();
            row.setProjectId(project.getProjectId());
            row.setProjectName(project.getProjectName());
            row.setCounterpartyName(RENT_IN.equals(direction) ? project.getPartnerName()
                    : StringUtils.hasText(project.getTenantUnit()) ? project.getTenantUnit() : project.getPartnerName());
            row.setCounterpartyNameSource(RENT_IN.equals(direction)
                    ? "CURRENT_PROJECT_PARTNER" : "CURRENT_PROJECT_CUSTOMER");
            row.setPostedPrincipal(money(posted));
            row.setAllocatedPrincipalAsOf(money(allocated));
            row.setOutstandingPrincipalAsOf(money(outstanding));
            row.setDueAsOfOutstanding(money(due));
            row.setOverdueOutstanding(money(overdue));
            row.setDueTodayOutstanding(money(dueToday));
            row.setRegisteredCash(money(registeredCash));
            row.setPrincipalEligibleCash(money(eligibleCash));
            row.setUnallocatedPrincipalCash(money(unallocatedCash));
            row.setCashRent(money(cashRent));
            row.setCashCompensation(money(cashCompensation));
            row.setCashDeposit(money(cashDeposit));
            row.setCashLateFee(money(cashLateFee));
            row.setCashOther(money(cashOther));
            row.setPeriodRegisteredCash(money(periodRegisteredCash));
            row.setPeriodPrincipalEligibleCash(money(periodEligibleCash));
            row.setPeriodCashRent(money(periodCashRent));
            row.setPeriodCashCompensation(money(periodCashCompensation));
            row.setPeriodCashDeposit(money(periodCashDeposit));
            row.setPeriodCashLateFee(money(periodCashLateFee));
            row.setPeriodCashOther(money(periodCashOther));
            row.setPeriodCount(periodCount);
            row.setEarliestDueDate(earliestDue == null ? null : earliestDue.toString());
            return row;
        }
    }
}
