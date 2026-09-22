package com.zjyz.agent.workspace.finance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.PeriodItem;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.ProjectItem;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Query;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Result;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Summary;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CustomerPaymentAllocationMapper;
import com.zjyz.dao.CustomerPaymentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementReceivablePeriodMapper;
import com.zjyz.pojo.entity.CustomerPaymentAllocationEntity;
import com.zjyz.pojo.entity.CustomerPaymentEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementReceivablePeriodEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReceivableCollectionServiceImpl implements ReceivableCollectionService {
    private static final String SCOPE_ALL = "ALL_OVERDUE";
    private static final String SCOPE_YEAR = "DUE_IN_YEAR";
    private static final String SCOPE_HISTORY = "HISTORICAL_CARRYOVER";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SettlementReceivablePeriodMapper periodMapper;
    private final CustomerPaymentAllocationMapper allocationMapper;
    private final CustomerPaymentMapper paymentMapper;
    private final ProjectMapper projectMapper;

    @Value("${agent.finance.collection.p1.overdueDays:${AGENT_FINANCE_COLLECTION_P1_OVERDUE_DAYS:90}}")
    private int p1OverdueDays;
    @Value("${agent.finance.collection.p1.outstandingAmount:${AGENT_FINANCE_COLLECTION_P1_OUTSTANDING_AMOUNT:100000}}")
    private BigDecimal p1OutstandingAmount;
    @Value("${agent.finance.collection.p2.overdueDays:${AGENT_FINANCE_COLLECTION_P2_OVERDUE_DAYS:30}}")
    private int p2OverdueDays;
    @Value("${agent.finance.collection.p2.outstandingAmount:${AGENT_FINANCE_COLLECTION_P2_OUTSTANDING_AMOUNT:50000}}")
    private BigDecimal p2OutstandingAmount;
    @Value("${agent.finance.collection.maxRows:${AGENT_FINANCE_COLLECTION_MAX_ROWS:5000}}")
    private int maxRows;

    public ReceivableCollectionServiceImpl(SettlementReceivablePeriodMapper periodMapper,
                                            CustomerPaymentAllocationMapper allocationMapper,
                                            CustomerPaymentMapper paymentMapper,
                                            ProjectMapper projectMapper) {
        this.periodMapper = periodMapper;
        this.allocationMapper = allocationMapper;
        this.paymentMapper = paymentMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public Result generate(String cid, Query input) {
        Query query = normalize(input);
        LocalDate asOf = parseAsOfDate(query.getAsOfDate());
        if (asOf.isAfter(LocalDate.now())) {
            throw new MyBizException("查询截止日期不能晚于今天", "FIN400");
        }
        if (query.getYear() > asOf.getYear()) {
            throw new MyBizException("统计年度不能晚于截止日期所在年度", "FIN400");
        }

        List<String> warnings = new ArrayList<>();
        if (Boolean.TRUE.equals(query.getIncludeClosedLate())) {
            warnings.add("当前版本仅输出截止日仍有未清余额的项目，不包含已结清但曾逾期的项目。");
        }
        List<SettlementReceivablePeriodEntity> sourcePeriods = periodMapper.selectList(
                new QueryWrapper<SettlementReceivablePeriodEntity>()
                        .eq("cid", cid)
                        .ne("status", "VOID"));

        List<PeriodSource> eligible = new ArrayList<>();
        for (SettlementReceivablePeriodEntity period : sourcePeriods) {
            LocalDate dueDate = safeDate(period.getDueDate());
            if (dueDate == null) {
                warnings.add("账期 " + period.getPeriodId() + " 的到期日无效，已从清单排除。");
                continue;
            }
            if (dueDate.isAfter(asOf) || !matchesScope(dueDate, query.getYear(), query.getScope())) {
                continue;
            }
            eligible.add(new PeriodSource(period, dueDate));
        }

        Map<String, BigDecimal> allocatedByPeriod = loadAllocations(cid, eligible, asOf, warnings);
        Map<String, ProjectAccumulator> grouped = new LinkedHashMap<>();
        for (PeriodSource source : eligible) {
            SettlementReceivablePeriodEntity period = source.period;
            BigDecimal principal = money(period.getPrincipalAmount());
            BigDecimal allocated = money(allocatedByPeriod.get(period.getPeriodId()));
            BigDecimal outstanding = principal.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            if (outstanding.compareTo(ZERO) < 0) {
                throw new MyBizException("账期 " + period.getPeriodId() + " 的有效核销金额超过应收本金，请先修复财务数据", "FIN409");
            }
            if (outstanding.compareTo(ZERO) == 0) {
                continue;
            }
            ProjectAccumulator accumulator = grouped.computeIfAbsent(period.getProjectId(), ProjectAccumulator::new);
            int overdueDays = (int) ChronoUnit.DAYS.between(source.dueDate, asOf);
            String yearGroup = source.dueDate.getYear() == query.getYear() ? "CURRENT_YEAR" : "CARRYOVER";
            accumulator.add(period, source.dueDate, overdueDays, principal, allocated, outstanding, yearGroup);
        }

        Map<String, ProjectEntity> projects = loadProjects(cid, grouped.keySet());
        Map<String, String> latestPayments = loadLatestPayments(cid, grouped.keySet(), asOf, warnings);
        List<ProjectItem> items = new ArrayList<>();
        for (ProjectAccumulator accumulator : grouped.values()) {
            ProjectEntity project = projects.get(accumulator.projectId);
            if (project == null || "rent_in".equalsIgnoreCase(project.getProjectBusinessType())) {
                warnings.add("项目 " + accumulator.projectId + " 不存在或不是租出项目，已从清单排除。");
                continue;
            }
            ProjectItem item = accumulator.toItem(project, latestPayments.get(accumulator.projectId));
            item.setPriority(priority(item.getOverdueDays(), item.getOutstandingAmount()));
            item.setSuggestedAction(suggestedAction(item));
            if (matchesFilters(item, query)) {
                items.add(item);
            }
        }

        items.sort(resolveComparator(query.getSortBy()));
        int total = items.size();
        int effectiveLimit = Math.min(Math.max(query.getLimit(), 1), Math.max(maxRows, 1));
        if (total > effectiveLimit) {
            warnings.add("符合条件的项目共 " + total + " 个，本次产物仅保留前 " + effectiveLimit + " 个，请增加筛选条件后重试。");
            items = new ArrayList<>(items.subList(0, effectiveLimit));
        }

        Result result = new Result();
        result.setGeneratedAt(LocalDateTime.now().toString());
        result.setQuery(query);
        result.setItems(items);
        result.setWarnings(deduplicate(warnings));
        result.setSummary(summarize(items));
        verifySummary(result);
        return result;
    }

    private Query normalize(Query input) {
        Query query = input == null ? new Query() : input;
        LocalDate today = LocalDate.now();
        if (!StringUtils.hasText(query.getAsOfDate())) query.setAsOfDate(today.toString());
        if (query.getYear() == null) query.setYear(safeDate(query.getAsOfDate()) == null ? today.getYear() : safeDate(query.getAsOfDate()).getYear());
        String scope = StringUtils.hasText(query.getScope()) ? query.getScope().trim().toUpperCase(Locale.ROOT) : SCOPE_ALL;
        if (!SCOPE_ALL.equals(scope) && !SCOPE_YEAR.equals(scope) && !SCOPE_HISTORY.equals(scope)) {
            throw new MyBizException("scope 仅支持 ALL_OVERDUE、DUE_IN_YEAR、HISTORICAL_CARRYOVER", "FIN400");
        }
        query.setScope(scope);
        if (query.getMinOutstandingAmount() == null) query.setMinOutstandingAmount(ZERO);
        if (query.getMinOutstandingAmount().compareTo(ZERO) < 0) throw new MyBizException("最低欠款金额不能小于0", "FIN400");
        if (query.getMinOverdueDays() == null) query.setMinOverdueDays(0);
        if (query.getMinOverdueDays() < 0) throw new MyBizException("最低逾期天数不能小于0", "FIN400");
        if (query.getLimit() == null) query.setLimit(1000);
        if (!StringUtils.hasText(query.getSortBy())) query.setSortBy("PRIORITY");
        query.setSortBy(query.getSortBy().trim().toUpperCase(Locale.ROOT));
        if (query.getPriorityLevels() == null) query.setPriorityLevels(new ArrayList<>());
        query.setPriorityLevels(query.getPriorityLevels().stream().filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toList()));
        return query;
    }

    private LocalDate parseAsOfDate(String value) {
        LocalDate result = safeDate(value);
        if (result == null) throw new MyBizException("截止日期格式必须为 yyyy-MM-dd", "FIN400");
        return result;
    }

    private boolean matchesScope(LocalDate dueDate, int year, String scope) {
        if (SCOPE_YEAR.equals(scope)) return dueDate.getYear() == year;
        if (SCOPE_HISTORY.equals(scope)) return dueDate.getYear() < year;
        return dueDate.getYear() <= year;
    }

    private Map<String, BigDecimal> loadAllocations(String cid, List<PeriodSource> periods, LocalDate asOf, List<String> warnings) {
        List<String> ids = periods.stream().map(source -> source.period.getPeriodId()).collect(Collectors.toList());
        Map<String, BigDecimal> totals = new HashMap<>();
        for (List<String> batch : batches(ids, 500)) {
            List<CustomerPaymentAllocationEntity> allocations = allocationMapper.selectList(
                    new QueryWrapper<CustomerPaymentAllocationEntity>()
                            .eq("cid", cid)
                            .eq("target_type", "SETTLEMENT_PRINCIPAL")
                            .eq("allocation_status", "ACTIVE")
                            .in("target_id", batch));
            for (CustomerPaymentAllocationEntity allocation : allocations) {
                LocalDate effective = safeDate(allocation.getEffectivePaymentDate());
                if (effective == null) {
                    warnings.add("核销记录 " + allocation.getAllocationId() + " 的生效日期无效，截止日重算时未计入。");
                } else if (!effective.isAfter(asOf)) {
                    totals.merge(allocation.getTargetId(), money(allocation.getAllocationAmount()), BigDecimal::add);
                }
            }
        }
        return totals;
    }

    private Map<String, ProjectEntity> loadProjects(String cid, Collection<String> ids) {
        Map<String, ProjectEntity> result = new HashMap<>();
        for (List<String> batch : batches(ids, 500)) {
            for (ProjectEntity project : projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                    .eq("cid", cid).in("project_id", batch))) {
                result.put(project.getProjectId(), project);
            }
        }
        return result;
    }

    private Map<String, String> loadLatestPayments(String cid, Collection<String> ids, LocalDate asOf, List<String> warnings) {
        Map<String, String> result = new HashMap<>();
        for (List<String> batch : batches(ids, 500)) {
            List<CustomerPaymentEntity> payments = paymentMapper.selectList(new QueryWrapper<CustomerPaymentEntity>()
                    .eq("cid", cid).eq("is_deleted", 0).in("project_id", batch));
            for (CustomerPaymentEntity payment : payments) {
                LocalDate paymentDate = safeDate(payment.getPaymentDate());
                if (paymentDate == null) {
                    warnings.add("收款记录 " + payment.getCustomerPaymentId() + " 的收款日期无效，未用于最近回款日期。");
                } else if (!paymentDate.isAfter(asOf)) {
                    result.merge(payment.getProjectId(), paymentDate.toString(), (left, right) -> left.compareTo(right) >= 0 ? left : right);
                }
            }
        }
        return result;
    }

    private boolean matchesFilters(ProjectItem item, Query query) {
        if (item.getOutstandingAmount().compareTo(query.getMinOutstandingAmount()) < 0) return false;
        if (item.getOverdueDays() < query.getMinOverdueDays()) return false;
        if (!query.getPriorityLevels().isEmpty() && !query.getPriorityLevels().contains(item.getPriority())) return false;
        if (StringUtils.hasText(query.getManagerName()) && !contains(item.getManagerName(), query.getManagerName())) return false;
        if (StringUtils.hasText(query.getProjectKeyword())
                && !contains(item.getProjectName(), query.getProjectKeyword())
                && !contains(item.getProjectId(), query.getProjectKeyword())) return false;
        if (StringUtils.hasText(query.getCustomerKeyword())
                && !contains(item.getCustomerName(), query.getCustomerKeyword())) return false;
        return true;
    }

    private Comparator<ProjectItem> resolveComparator(String sortBy) {
        Comparator<ProjectItem> amount = Comparator.comparing(ProjectItem::getOutstandingAmount, Comparator.reverseOrder());
        Comparator<ProjectItem> overdue = Comparator.comparing(ProjectItem::getOverdueDays, Comparator.reverseOrder());
        Comparator<ProjectItem> fallback = Comparator.comparing(ProjectItem::getProjectId, Comparator.nullsLast(String::compareTo));
        if ("AMOUNT".equals(sortBy)) return amount.thenComparing(overdue).thenComparing(fallback);
        if ("OVERDUE".equals(sortBy)) return overdue.thenComparing(amount).thenComparing(fallback);
        return Comparator.comparingInt((ProjectItem item) -> priorityRank(item.getPriority()))
                .thenComparing(amount).thenComparing(overdue).thenComparing(fallback);
    }

    private Summary summarize(List<ProjectItem> items) {
        Summary summary = new Summary();
        summary.setProjectCount(items.size());
        summary.setReturnedProjectCount(items.size());
        summary.setTotalOutstanding(sum(items, ProjectItem::getOutstandingAmount));
        summary.setCurrentYearOutstanding(sum(items, ProjectItem::getCurrentYearOutstanding));
        summary.setCarryoverOutstanding(sum(items, ProjectItem::getCarryoverOutstanding));
        summary.setCurrentYearProjectCount((int) items.stream().filter(item -> item.getCurrentYearOutstanding().compareTo(ZERO) > 0).count());
        summary.setCarryoverProjectCount((int) items.stream().filter(item -> item.getCarryoverOutstanding().compareTo(ZERO) > 0).count());
        summary.setP1Count((int) items.stream().filter(item -> "P1".equals(item.getPriority())).count());
        summary.setP2Count((int) items.stream().filter(item -> "P2".equals(item.getPriority())).count());
        summary.setP3Count((int) items.stream().filter(item -> "P3".equals(item.getPriority())).count());
        summary.setMaxOverdueDays(items.stream().map(ProjectItem::getOverdueDays).max(Integer::compareTo).orElse(0));
        return summary;
    }

    private void verifySummary(Result result) {
        BigDecimal grouped = result.getSummary().getCurrentYearOutstanding().add(result.getSummary().getCarryoverOutstanding());
        if (grouped.compareTo(result.getSummary().getTotalOutstanding()) != 0) {
            throw new MyBizException("应收催缴汇总校验失败，请稍后重试", "FIN500");
        }
    }

    private BigDecimal sum(List<ProjectItem> items, java.util.function.Function<ProjectItem, BigDecimal> getter) {
        return items.stream().map(getter).filter(value -> value != null).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private String priority(int overdueDays, BigDecimal outstanding) {
        if (overdueDays >= p1OverdueDays || outstanding.compareTo(p1OutstandingAmount) >= 0) return "P1";
        if (overdueDays >= p2OverdueDays || outstanding.compareTo(p2OutstandingAmount) >= 0) return "P2";
        return "P3";
    }

    private String suggestedAction(ProjectItem item) {
        if ("P1".equals(item.getPriority())) return "立即核对账期与回款，明确责任人并在1个工作日内发起催缴。";
        if ("P2".equals(item.getPriority())) return "本周联系客户确认付款计划，记录承诺付款日期。";
        return "纳入常规应收跟踪，下次例会复核付款进度。";
    }

    private int priorityRank(String priority) {
        if ("P1".equals(priority)) return 1;
        if ("P2".equals(priority)) return 2;
        return 3;
    }

    private boolean contains(String value, String query) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private LocalDate safeDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return LocalDate.parse(value.trim()); } catch (Exception ignored) { return null; }
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private <T> List<List<T>> batches(Collection<T> values, int size) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<T> source = new ArrayList<>(values);
        List<List<T>> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index += size) {
            result.add(source.subList(index, Math.min(index + size, source.size())));
        }
        return result;
    }

    private List<String> deduplicate(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }

    private static final class PeriodSource {
        private final SettlementReceivablePeriodEntity period;
        private final LocalDate dueDate;

        private PeriodSource(SettlementReceivablePeriodEntity period, LocalDate dueDate) {
            this.period = period;
            this.dueDate = dueDate;
        }
    }

    private static final class ProjectAccumulator {
        private final String projectId;
        private BigDecimal outstanding = ZERO;
        private BigDecimal currentYear = ZERO;
        private BigDecimal carryover = ZERO;
        private int overdueDays;
        private LocalDate earliestDue;
        private final List<PeriodItem> periods = new ArrayList<>();

        private ProjectAccumulator(String projectId) { this.projectId = projectId; }

        private void add(SettlementReceivablePeriodEntity entity, LocalDate dueDate, int days, BigDecimal principal,
                         BigDecimal allocated, BigDecimal balance, String yearGroup) {
            outstanding = outstanding.add(balance);
            if ("CURRENT_YEAR".equals(yearGroup)) currentYear = currentYear.add(balance); else carryover = carryover.add(balance);
            overdueDays = Math.max(overdueDays, days);
            if (earliestDue == null || dueDate.isBefore(earliestDue)) earliestDue = dueDate;
            PeriodItem item = new PeriodItem();
            item.setPeriodId(entity.getPeriodId());
            item.setSettlementDocumentId(entity.getSettlementDocumentId());
            item.setPeriodStart(entity.getPeriodStart());
            item.setPeriodEnd(entity.getPeriodEnd());
            item.setDueDate(dueDate.toString());
            item.setOverdueDays(days);
            item.setPrincipalAmount(principal);
            item.setAllocatedAsOf(allocated);
            item.setOutstandingAsOf(balance);
            item.setYearGroup(yearGroup);
            periods.add(item);
        }

        private ProjectItem toItem(ProjectEntity project, String latestPaymentDate) {
            periods.sort(Comparator.comparing(PeriodItem::getDueDate));
            ProjectItem item = new ProjectItem();
            item.setProjectId(projectId);
            item.setProjectName(project.getProjectName());
            item.setCustomerName(StringUtils.hasText(project.getTenantUnit()) ? project.getTenantUnit() : project.getPartnerName());
            item.setManagerName(project.getManagerName());
            item.setProjectStatus(project.getProjectStatusFlag());
            item.setOutstandingAmount(outstanding.setScale(2, RoundingMode.HALF_UP));
            item.setCurrentYearOutstanding(currentYear.setScale(2, RoundingMode.HALF_UP));
            item.setCarryoverOutstanding(carryover.setScale(2, RoundingMode.HALF_UP));
            item.setYearGroup(currentYear.compareTo(ZERO)>0 && carryover.compareTo(ZERO)>0 ? "MIXED"
                    : currentYear.compareTo(ZERO)>0 ? "CURRENT_YEAR" : "CARRYOVER");
            item.setOverdueDays(overdueDays);
            item.setPeriodCount(periods.size());
            item.setEarliestDueDate(earliestDue == null ? null : earliestDue.toString());
            item.setLatestPaymentDate(latestPaymentDate);
            item.setPeriods(periods);
            return item;
        }
    }
}
