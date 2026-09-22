package com.zjyz.agent.service.impl;

import com.zjyz.agent.dao.AgentTenantTaskUsageMapper;
import com.zjyz.agent.dao.AgentUsageRecordMapper;
import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.model.AgentQuotaInfo;
import com.zjyz.agent.model.AgentUsageRecordEntity;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.membership.domain.EntitlementKeys;
import com.zjyz.membership.service.MembershipEntitlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

@Slf4j
@Service
public class AgentQuotaServiceImpl implements AgentQuotaService {

    @Value("${agent.quota.defaultUserMonthlyCallLimit:120}")
    private int defaultUserMonthlyCallLimit;

    @Value("${agent.quota.defaultUserDailyCallLimit:20}")
    private int defaultUserDailyCallLimit;

    @Value("${agent.quota.defaultTenantMonthlyTaskLimit:300}")
    private int defaultTenantMonthlyTaskLimit;

    @Value("${agent.quota.defaultUserMonthlyPromptTokenLimit:250000}")
    private int defaultUserMonthlyPromptTokenLimit;

    @Value("${agent.quota.defaultUserMonthlyCompletionTokenLimit:80000}")
    private int defaultUserMonthlyCompletionTokenLimit;

    @Value("${agent.quota.defaultPremiumModelMonthlyLimit:10}")
    private int defaultPremiumModelMonthlyLimit;

    @Value("${agent.quota.defaultTenantMonthlyBudgetCny:500}")
    private double defaultTenantMonthlyBudgetCny;

    @Value("${agent.quota.requestMaxInputChars:1500}")
    private int requestMaxInputChars;

    @Value("${agent.quota.requestMaxInputTokens:4000}")
    private int requestMaxInputTokens;

    @Value("${agent.quota.requestMaxOutputTokens:1200}")
    private int requestMaxOutputTokens;

    @Value("${agent.llm.deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${agent.llm.deepseek.inputPricePerMillionCny:2}")
    private double deepseekInputPricePerMillionCny;

    @Value("${agent.llm.deepseek.outputPricePerMillionCny:3}")
    private double deepseekOutputPricePerMillionCny;

    @Value("${agent.llm.fallback.model:gpt-4o-mini}")
    private String fallbackModel;

    @Value("${agent.llm.fallback.inputPricePerMillionCny:1.8}")
    private double fallbackInputPricePerMillionCny;

    @Value("${agent.llm.fallback.outputPricePerMillionCny:14.4}")
    private double fallbackOutputPricePerMillionCny;

    @Autowired
    private AgentUsageRecordMapper usageRecordMapper;

    @Autowired
    private AgentTenantTaskUsageMapper tenantTaskUsageMapper;

    @Autowired
    private MembershipEntitlementService membershipEntitlementService;

    @Override
    public String resolveCurrentUserKey() {
        String uid = AuthContext.getUid();
        if (StringUtils.hasText(uid)) {
            return uid;
        }

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "anonymous";
        }
        HttpServletRequest request = attrs.getRequest();
        String userId = request.getHeader("X-User-Id");
        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }
        return "anonymous";
    }

    @Override
    public AgentQuotaDecision preCheck(String userKey, String message, boolean premiumRequested) {
        AgentQuotaDecision decision = new AgentQuotaDecision();
        decision.setPremiumAllowed(true);
        String content = message == null ? "" : message;
        int estimatedPromptTokens = estimatePromptTokens(content);
        decision.setEstimatedPromptTokens(estimatedPromptTokens);

        String monthKey = YearMonth.now().toString();
        String cid = CommonUtil.getCid();
        AgentUsageRecordEntity userRecord = getOrCreateRecord(userKey, cid, monthKey);
        BigDecimal tenantMonthlyCost = BigDecimal.valueOf(usageRecordMapper.sumTenantMonthlyCost(cid, monthKey));

        if (content.length() > requestMaxInputChars) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("输入字数超限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        if (estimatedPromptTokens > requestMaxInputTokens) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("输入token超限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        String dayKey = LocalDate.now().toString();
        int effectiveDailyCalls = dayKey.equals(userRecord.getDayKey()) ? userRecord.getDailyCalls() : 0;
        int dailyCallLimit = effectiveUserDailyCallLimit(userKey, cid);
        if (effectiveDailyCalls + 1 > dailyCallLimit) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("日调用次数已达上限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        if (!isUserMonthlyCallUnlimited(userKey, cid)
                && userRecord.getMonthlyCalls() + 1 > effectiveUserMonthlyCallLimit(cid)) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("月调用次数已达上限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        if (!isUserMonthlyPromptTokenUnlimited(userKey, cid)
                && userRecord.getMonthlyPromptTokens() + estimatedPromptTokens > defaultUserMonthlyPromptTokenLimit) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("月输入token已达上限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        if (tenantMonthlyCost.compareTo(effectiveTenantMonthlyBudgetCny(cid)) >= 0) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("租户月预算已达上限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        if (premiumRequested && userRecord.getMonthlyPremiumCalls() >= defaultPremiumModelMonthlyLimit) {
            decision.setPremiumAllowed(false);
        }

        decision.setAllow(true);
        decision.setDegradeMode(false);
        decision.setReason("ok");
        decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
        return decision;
    }

    @Override
    public AgentQuotaDecision consumeTenantTask(String userKey) {
        AgentQuotaDecision decision = new AgentQuotaDecision();
        decision.setPremiumAllowed(true);
        String cid = CommonUtil.getCid();
        String monthKey = YearMonth.now().toString();
        AgentUsageRecordEntity userRecord = getOrCreateRecord(userKey, cid, monthKey);
        BigDecimal tenantMonthlyCost = BigDecimal.valueOf(usageRecordMapper.sumTenantMonthlyCost(cid, monthKey));

        if (tenantMonthlyCost.compareTo(effectiveTenantMonthlyBudgetCny(cid)) >= 0) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("企业月预算已达上限");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        int limit = effectiveTenantMonthlyTaskLimit(cid);
        tenantTaskUsageMapper.ensureMonth(cid, monthKey);
        if (tenantTaskUsageMapper.incrementIfBelowLimit(cid, monthKey, limit) == 0) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("企业本月共享任务额度已用完");
            decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
            return decision;
        }

        decision.setAllow(true);
        decision.setDegradeMode(false);
        decision.setReason("ok");
        decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
        return decision;
    }

    @Override
    public void releaseTenantTask(String cid, String monthKey) {
        if (!StringUtils.hasText(cid) || !StringUtils.hasText(monthKey)) {
            return;
        }
        tenantTaskUsageMapper.decrement(cid, monthKey);
    }

    @Override
    public AgentQuotaDecision preCheckModelCall(String userKey) {
        AgentQuotaDecision decision = new AgentQuotaDecision();
        decision.setPremiumAllowed(true);
        String cid = CommonUtil.getCid();
        String monthKey = YearMonth.now().toString();
        AgentUsageRecordEntity userRecord = getOrCreateRecord(userKey, cid, monthKey);
        BigDecimal tenantMonthlyCost = BigDecimal.valueOf(usageRecordMapper.sumTenantMonthlyCost(cid, monthKey));
        if (tenantMonthlyCost.compareTo(effectiveTenantMonthlyBudgetCny(cid)) >= 0) {
            decision.setAllow(false);
            decision.setDegradeMode(true);
            decision.setReason("企业月预算已达上限");
        } else {
            decision.setAllow(true);
            decision.setDegradeMode(false);
            decision.setReason("ok");
        }
        decision.setQuota(buildQuota(userRecord, tenantMonthlyCost));
        return decision;
    }

    @Override
    public void recordUsage(String userKey, int promptTokens, int completionTokens, boolean premiumUsed, double estimatedCostCny) {
        String monthKey = YearMonth.now().toString();
        String dayKey = LocalDate.now().toString();
        String cid = CommonUtil.getCid();

        // 确保记录存在
        getOrCreateRecord(userKey, cid, monthKey);

        int updated = usageRecordMapper.incrementUsage(
                userKey, cid, monthKey, dayKey,
                Math.max(promptTokens, 0),
                Math.max(completionTokens, 0),
                premiumUsed ? 1 : 0,
                Math.max(estimatedCostCny, 0d)
        );
        if (updated == 0) {
            log.warn("recordUsage: 更新行数为0, userKey={}, monthKey={}", userKey, monthKey);
        }
    }

    @Override
    public void recordModelUsage(String userKey, int promptTokens, int completionTokens, double estimatedCostCny) {
        String monthKey = YearMonth.now().toString();
        String cid = CommonUtil.getCid();
        getOrCreateRecord(userKey, cid, monthKey);
        int updated = usageRecordMapper.incrementModelUsage(
                userKey,
                cid,
                monthKey,
                Math.max(promptTokens, 0),
                Math.max(completionTokens, 0),
                Math.max(estimatedCostCny, 0d)
        );
        if (updated == 0) {
            log.warn("recordModelUsage: 更新行数为0, userKey={}, monthKey={}", userKey, monthKey);
        }
    }

    @Override
    public AgentQuotaInfo getQuota(String userKey) {
        String monthKey = YearMonth.now().toString();
        String cid = CommonUtil.getCid();
        AgentUsageRecordEntity record = getOrCreateRecord(userKey, cid, monthKey);
        BigDecimal tenantMonthlyCost = BigDecimal.valueOf(usageRecordMapper.sumTenantMonthlyCost(cid, monthKey));
        return buildQuota(record, tenantMonthlyCost);
    }

    @Override
    public int getRequestMaxOutputTokens() {
        return requestMaxOutputTokens;
    }

    @Override
    public double estimateCostCny(String modelName, int promptTokens, int completionTokens) {
        String normalizedModel = modelName == null ? "" : modelName.trim().toLowerCase();
        double inputPricePerMillion = deepseekInputPricePerMillionCny;
        double outputPricePerMillion = deepseekOutputPricePerMillionCny;

        if (StringUtils.hasText(fallbackModel) && normalizedModel.contains(fallbackModel.toLowerCase())) {
            inputPricePerMillion = fallbackInputPricePerMillionCny;
            outputPricePerMillion = fallbackOutputPricePerMillionCny;
        } else if (StringUtils.hasText(deepseekModel) && normalizedModel.contains(deepseekModel.toLowerCase())) {
            inputPricePerMillion = deepseekInputPricePerMillionCny;
            outputPricePerMillion = deepseekOutputPricePerMillionCny;
        }

        double inputCost = Math.max(promptTokens, 0) * inputPricePerMillion / 1_000_000d;
        double outputCost = Math.max(completionTokens, 0) * outputPricePerMillion / 1_000_000d;
        return inputCost + outputCost;
    }

    // -------------------- 内部工具方法 --------------------

    private int estimatePromptTokens(String message) {
        if (!StringUtils.hasText(message)) {
            return 0;
        }
        // 对中文场景做保守估算：1 字约 1.2 token。
        return (int) Math.ceil(message.length() * 1.2);
    }

    /**
     * 查询当月记录，不存在则初始化插入一行。
     * 使用 INSERT IGNORE 避免并发重复插入。
     */
    private AgentUsageRecordEntity getOrCreateRecord(String userKey, String cid, String monthKey) {
        AgentUsageRecordEntity record = usageRecordMapper.selectByUserMonth(userKey, cid, monthKey);
        if (record != null) {
            return record;
        }
        // 初始化新记录
        AgentUsageRecordEntity newRecord = new AgentUsageRecordEntity();
        newRecord.setUserKey(userKey);
        newRecord.setCid(cid);
        newRecord.setDayKey(LocalDate.now().toString());
        newRecord.setMonthKey(monthKey);
        newRecord.setDailyCalls(0);
        newRecord.setMonthlyCalls(0);
        newRecord.setMonthlyPromptTokens(0);
        newRecord.setMonthlyCompletionTokens(0);
        newRecord.setMonthlyPremiumCalls(0);
        newRecord.setMonthlyCostCny(BigDecimal.ZERO);
        try {
            usageRecordMapper.insert(newRecord);
        } catch (Exception e) {
            // 并发场景下可能已被其他线程插入，忽略唯一键冲突，重新查询
            log.debug("getOrCreateRecord insert conflict, re-query: userKey={}, monthKey={}", userKey, monthKey);
        }
        AgentUsageRecordEntity result = usageRecordMapper.selectByUserMonth(userKey, cid, monthKey);
        return result != null ? result : newRecord;
    }

    private AgentQuotaInfo buildQuota(AgentUsageRecordEntity record, BigDecimal tenantMonthlyCost) {
        AgentQuotaInfo quotaInfo = new AgentQuotaInfo();

        String dayKey = LocalDate.now().toString();
        int effectiveDailyCalls = dayKey.equals(record.getDayKey()) ? record.getDailyCalls() : 0;

        quotaInfo.setUserDailyUsed(effectiveDailyCalls);
        quotaInfo.setUserDailyLimit(effectiveUserDailyCallLimit(record.getUserKey(), record.getCid()));
        quotaInfo.setUserMonthlyUsed(record.getMonthlyCalls());
        boolean monthlyCallUnlimited = isUserMonthlyCallUnlimited(record.getUserKey(), record.getCid());
        quotaInfo.setUserMonthlyCallUnlimited(monthlyCallUnlimited);
        if (monthlyCallUnlimited) {
            quotaInfo.setUserMonthlyLimit(null);
            quotaInfo.setUserMonthlyRemaining(null);
        } else {
            int monthlyCallLimit = effectiveUserMonthlyCallLimit(record.getCid());
            quotaInfo.setUserMonthlyLimit(monthlyCallLimit);
            quotaInfo.setUserMonthlyRemaining(Math.max(0, monthlyCallLimit - record.getMonthlyCalls()));
        }

        int tenantTaskLimit = effectiveTenantMonthlyTaskLimit(record.getCid());
        int tenantTaskUsed = tenantTaskUsageMapper.currentTaskCount(record.getCid(), record.getMonthKey());
        quotaInfo.setTenantMonthlyTaskUsed(tenantTaskUsed);
        quotaInfo.setTenantMonthlyTaskLimit(tenantTaskLimit);
        quotaInfo.setTenantMonthlyTaskRemaining(Math.max(0, tenantTaskLimit - tenantTaskUsed));

        boolean promptTokenUnlimited = isUserMonthlyPromptTokenUnlimited(record.getUserKey(), record.getCid());
        quotaInfo.setUserMonthlyPromptTokenUsed(record.getMonthlyPromptTokens());
        quotaInfo.setUserMonthlyPromptTokenLimit(promptTokenUnlimited ? null : defaultUserMonthlyPromptTokenLimit);
        quotaInfo.setUserMonthlyPromptTokenUnlimited(promptTokenUnlimited);
        quotaInfo.setUserMonthlyCompletionTokenUsed(record.getMonthlyCompletionTokens());
        quotaInfo.setUserMonthlyCompletionTokenLimit(defaultUserMonthlyCompletionTokenLimit);

        BigDecimal budget = effectiveTenantMonthlyBudgetCny(CommonUtil.getCid()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal used = tenantMonthlyCost.setScale(4, RoundingMode.HALF_UP);
        quotaInfo.setTenantMonthlyBudgetCny(budget);
        quotaInfo.setTenantMonthlyUsedCny(used);
        quotaInfo.setTenantBudgetRemainingCny(budget.subtract(used).max(BigDecimal.ZERO));
        return quotaInfo;
    }

    private int effectiveUserDailyCallLimit(String userKey, String cid) {
        Integer override = usageRecordMapper.selectDailyCallLimitOverride(userKey, cid);
        if (override == null || override <= 0) {
            return defaultUserDailyCallLimit;
        }
        return override;
    }

    private boolean isUserMonthlyPromptTokenUnlimited(String userKey, String cid) {
        Integer override = usageRecordMapper.selectMonthlyPromptTokenUnlimitedOverride(userKey, cid);
        return override != null && override == 1;
    }

    private boolean isUserMonthlyCallUnlimited(String userKey, String cid) {
        Integer override = usageRecordMapper.selectMonthlyCallUnlimitedOverride(userKey, cid);
        return override != null && override == 1;
    }

    private int effectiveUserMonthlyCallLimit(String cid) {
        long value = membershipEntitlementService.requirePositiveLimit(
                cid, EntitlementKeys.AI_USER_MONTHLY_CALL_LIMIT, defaultUserMonthlyCallLimit);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private int effectiveTenantMonthlyTaskLimit(String cid) {
        long value = membershipEntitlementService.requirePositiveLimit(
                cid, EntitlementKeys.AI_TENANT_MONTHLY_TASK_LIMIT, defaultTenantMonthlyTaskLimit);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private BigDecimal effectiveTenantMonthlyBudgetCny(String cid) {
        long cents = membershipEntitlementService.requirePositiveLimit(
                cid,
                EntitlementKeys.AI_TENANT_MONTHLY_BUDGET_CENTS,
                Math.round(defaultTenantMonthlyBudgetCny * 100));
        return BigDecimal.valueOf(Math.max(0L, cents), 2);
    }
}
