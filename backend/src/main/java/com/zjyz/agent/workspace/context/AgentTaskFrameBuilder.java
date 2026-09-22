package com.zjyz.agent.workspace.context;

import com.zjyz.agent.orch.AgentUnsupportedMetricPolicy;
import com.zjyz.agent.orch.AgentFinanceIntentPolicy;
import com.zjyz.agent.orch.AgentOwnerActionIntentPolicy;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.routing.AgentIntentToolBindingTable;
import com.zjyz.agent.workspace.tool.AgentToolCodes;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentTaskFrameBuilder {
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})[年\\-/](0?[1-9]|1[0-2])(?:月)?");
    private static final Pattern MONTH_ONLY = Pattern.compile("(?<!\\d)(0?[1-9]|1[0-2])月份?");

    public AgentTaskFrame build(String currentMessage,
                                String previousUserMessage,
                                AgentRuntimeRecords.Workspace workspace,
                                ZoneId zoneId) {
        String message = StringUtils.hasText(currentMessage) ? currentMessage.trim() : "";
        String currentDomain = classifyDomain(message);
        String previousDomain = classifyDomain(previousUserMessage);
        boolean followUp = isFollowUp(message, previousUserMessage, currentDomain, previousDomain);
        if (followUp && "GENERAL".equals(currentDomain)) {
            currentDomain = previousDomain;
        }

        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setTaskType(classifyTaskType(message, currentDomain));
        frame.setDomain(currentDomain);
        frame.setUserGoal(message);
        frame.setTimeRange(resolveTimeRange(message, zoneId == null ? ZoneId.of("Asia/Shanghai") : zoneId));
        frame.setSelectionMode(resolveSelectionMode(workspace));
        frame.setProjectIds(workspace == null || workspace.getProjectIds() == null
                ? new ArrayList<>() : new ArrayList<>(workspace.getProjectIds()));
        frame.setFollowUp(followUp);
        List<String> minimumRequiredTools = resolveRequiredToolCodes(message, workspace);
        frame.setMinimumRequiredTools(minimumRequiredTools);
        frame.setBindingVersion(AgentIntentToolBindingTable.instance().bindingVersion());
        AgentTaskCoverage coverage = resolveCoverage(message, minimumRequiredTools);
        frame.setCoverage(coverage);
        frame.setRequiresBusinessData(coverage == AgentTaskCoverage.UNSUPPORTED_BUSINESS
                || (coverage == AgentTaskCoverage.SUPPORTED_TOOL
                && !minimumRequiredTools.equals(java.util.Collections.singletonList(AgentToolCodes.HELP_SEARCH))));
        return frame;
    }

    private List<String> resolveRequiredToolCodes(String message,
                                                  AgentRuntimeRecords.Workspace workspace) {
        List<String> required = new ArrayList<>();
        String source = message == null ? "" : message;
        if (isEnterpriseKnowledgeQuery(source)) {
            return required;
        }
        if (isSystemHelpQuery(source)) {
            required.add(AgentToolCodes.HELP_SEARCH);
            return required;
        }
        if (AgentUnsupportedMetricPolicy.containsUnsupportedMetric(source)) {
            return required;
        }
        if (AgentOwnerActionIntentPolicy.hasUnknownRiskDimension(source)) {
            return required;
        }
        if (isHowExpression(source) && !isSupportedHowFactQuery(source, workspace)) {
            return required;
        }
        if (AgentOwnerActionIntentPolicy.isOwnerActionCenter(source)
                && AgentOwnerActionIntentPolicy.hasUnsupportedSnapshotTime(source)) {
            return required;
        }
        if (AgentOwnerActionIntentPolicy.isScopedProjectAction(source) && !isSingleProject(workspace)) {
            return required;
        }
        if (AgentOwnerActionIntentPolicy.isOwnerActionCenter(source)) {
            required.add(AgentToolCodes.RISK_OWNER_ACTION_CENTER);
            return required;
        }
        if (AgentFinanceIntentPolicy.isSupplierDirection(source)) {
            if (AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(source)) {
                return required;
            }
            if (isSupplierPayableQuery(source)) {
                required.add(AgentToolCodes.FINANCE_SUPPLIER_PAYABLE_SUMMARY);
                return required;
            }
        }
        if (isCollectionEnforcementQuery(source)) {
            required.add(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST);
            if (isReconciliationQuery(source)) {
                required.add(AgentToolCodes.PROJECT_RECONCILIATION_DUE);
            }
            return required;
        }
        if (AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(source)) {
            return required;
        }
        if (AgentFinanceIntentPolicy.isScopedProjectLedgerKpi(source) && !isSingleProject(workspace)) {
            return required;
        }
        if (isMaterialEstimateQuery(source)) {
            required.add(AgentToolCodes.MATERIAL_ESTIMATE);
            return required;
        }
        if (isEnterpriseKpiQuery(source)) {
            required.add(AgentToolCodes.FINANCE_ENTERPRISE_KPI);
            return required;
        }
        if (isSupplierPayableQuery(source)) {
            required.add(AgentToolCodes.FINANCE_SUPPLIER_PAYABLE_SUMMARY);
            return required;
        }
        if (isProjectTypeOrStatusCountQuery(source)) {
            required.add(AgentToolCodes.PROJECT_LIST);
            return required;
        }
        boolean materialLifecycleQuery = containsAny(source, "多还", "赔偿率",
                "只租不还", "租龄", "材料周转");
        if (materialLifecycleQuery) {
            required.add(AgentToolCodes.MATERIAL_LIFECYCLE_ANALYTICS);
        }
        if (containsAny(source, "价差", "价格差异", "定价不一致", "租金不一致", "价格比较")
                || (containsAny(source, "合同")
                && containsAny(source, "字段缺失", "字段完整", "信息不完整", "缺少税率", "缺少结算周期"))) {
            required.add(AgentToolCodes.CONTRACT_COMMERCIAL_ANALYTICS);
        }
        boolean materialAggregateQuery = !materialLifecycleQuery && isMaterialAggregateQuery(source);
        if (materialAggregateQuery) {
            required.add(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE);
        }
        if (!AgentFinanceIntentPolicy.isSupplierDirection(source)
                && containsAny(source, "欠款", "未付款", "应收", "已收", "回款", "回款率")) {
            required.add(isSingleProject(workspace)
                    ? AgentToolCodes.PROJECT_SUMMARY
                    : AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST);
        }
        if (containsAny(source, "未对账", "从未对账", "没有对账", "应该对账", "需要对账",
                "待对账", "对账情况", "对账缺口", "结算周期")) {
            required.add(AgentToolCodes.PROJECT_RECONCILIATION_DUE);
        }
        if (isContractStatusQuery(source)) {
            required.add(AgentToolCodes.PROJECT_CONTRACT_STATUS);
        }
        if (!materialLifecycleQuery && containsAny(source, "在租材料", "材料在租", "未归还", "没有归还",
                "还没归还", "尚未归还", "材料占用", "占用情况", "仍在租")) {
            required.add(AgentToolCodes.PROJECT_MATERIAL_OCCUPANCY);
        }
        if (!materialAggregateQuery && (containsAny(source, "业务活动", "没有发生", "无活动", "最后一次业务", "多久没有")
                || (containsAny(source, "最近", "近", "过去")
                && containsAny(source, "租出", "归还", "退租", "单据")))) {
            required.add(AgentToolCodes.PROJECT_ACTIVITY);
        }
        boolean inventoryOperationsQuery = containsAny(source, "负库存", "库存为负", "库存异常",
                "在租为负", "租入未退为负", "长期无流水", "沉淀库存", "盘点差异");
        boolean inventoryLedgerQuery = !inventoryOperationsQuery
                && containsAny(source, "库存台账", "材料台账");
        if (inventoryOperationsQuery) {
            required.add(AgentToolCodes.INVENTORY_OPERATIONS_SUMMARY);
        } else if (inventoryLedgerQuery) {
            required.add(AgentToolCodes.INVENTORY_LEDGER_TRACE);
        } else if (containsAny(source, "库存", "库存预警", "低库存", "仓库")) {
            required.add(AgentToolCodes.INVENTORY_SUMMARY);
        }
        if (isSingleProject(workspace) && containsAny(source,
                "经营情况", "经营概况", "经营分析", "项目概况", "经营风险", "风险和建议", "经营建议")) {
            required.add(AgentToolCodes.PROJECT_SUMMARY);
        }
        if (isProjectCatalogQuery(source)) {
            required.add(AgentToolCodes.PROJECT_LIST);
        }
        if (containsAny(source, "待复核", "未复核", "没有复核", "没复核",
                "待审核", "未审核", "需要审核", "审核单据")) {
            required.add(AgentToolCodes.DOCUMENT_AUDIT_LIST);
        }
        if (isDocumentSearchQuery(source)
                && !required.contains(AgentToolCodes.PROJECT_ACTIVITY)
                && !required.contains(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE)) {
            if (isSingleProject(workspace)) {
                required.add(AgentToolCodes.DOCUMENT_SEARCH);
            }
        }
        // 版本化绑定表只在全部守卫分支通过后作为下限并入；
        // 前面各分支的提前 return（帮助、企业知识、不支持指标等）刻意跳过下限，保持诚实拒答。
        return AgentIntentToolBindingTable.instance()
                .applyFloor(source, selectionKind(workspace), required);
    }

    private String selectionKind(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            return "UNKNOWN";
        }
        if ("ALL".equalsIgnoreCase(workspace.getSelectionMode())) {
            return "ALL";
        }
        if (workspace.getProjectIds() != null && workspace.getProjectIds().size() > 1) {
            return "MULTI";
        }
        return isSingleProject(workspace) ? "SINGLE" : "UNKNOWN";
    }

    String classifyDomain(String message) {
        if (!StringUtils.hasText(message)) {
            return "GENERAL";
        }
        if (isEnterpriseKnowledgeQuery(message)) {
            return "ENTERPRISE_KNOWLEDGE";
        }
        if (isSystemHelpQuery(message)) {
            return "HELP";
        }
        if (AgentUnsupportedMetricPolicy.containsUnsupportedMetric(message)
                || AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(message)) {
            return "FINANCE";
        }
        if (AgentOwnerActionIntentPolicy.isOwnerActionCenter(message)) {
            return "RISK";
        }
        if (containsAny(message, "欠款", "未付款", "逾期付款", "催缴", "追缴", "应收", "应付",
                "已收", "已付", "实收", "实付", "回款", "租金")) {
            return "FINANCE";
        }
        if (containsAny(message, "对账", "结算周期", "结算单")) {
            return "SETTLEMENT";
        }
        if (containsAny(message, "库存", "库存预警", "低库存", "仓库")) {
            return "INVENTORY";
        }
        if (containsAny(message, "材料预估", "用量估算", "脚手架", "材料", "物料")) {
            return "MATERIAL";
        }
        if (containsAny(message, "单据", "租出单", "归还单", "赔偿单", "租入单", "退租单", "出库单", "入库单")) {
            return "DOCUMENT";
        }
        if (containsAny(message, "项目", "经营情况", "经营分析", "负责人", "客户")) {
            return "PROJECT";
        }
        return "GENERAL";
    }

    private String classifyTaskType(String message, String domain) {
        if ("HELP".equals(domain)) {
            return "HELP";
        }
        if (containsAny(message, "预估", "估算", "测算", "计算需要")) {
            return "ESTIMATE";
        }
        if (containsAny(message, "总结", "汇总", "分析", "经营情况", "概况", "统计", "排行", "排名", "最多", "最少")) {
            return "SUMMARY";
        }
        return "QUERY";
    }

    private boolean isFollowUp(String current,
                               String previous,
                               String currentDomain,
                               String previousDomain) {
        if (!StringUtils.hasText(current) || !StringUtils.hasText(previous) || current.length() > 100) {
            return false;
        }
        boolean referencesPrevious = containsAny(current,
                "继续", "接着", "刚才", "上面", "这些", "它们", "其中", "分别呢", "再看", "那它", "那这些", "这个清单");
        if (!referencesPrevious) {
            return false;
        }
        return "GENERAL".equals(currentDomain) || currentDomain.equals(previousDomain);
    }

    private AgentTaskCoverage resolveCoverage(String message, List<String> minimumRequiredTools) {
        if (minimumRequiredTools != null && !minimumRequiredTools.isEmpty()) {
            return AgentTaskCoverage.SUPPORTED_TOOL;
        }
        if (isEnterpriseKnowledgeQuery(message)) {
            return AgentTaskCoverage.UNSUPPORTED_BUSINESS;
        }
        if (isExplicitNonBusiness(message)) {
            return AgentTaskCoverage.NON_BUSINESS;
        }
        return StringUtils.hasText(message)
                ? AgentTaskCoverage.UNSUPPORTED_BUSINESS
                : AgentTaskCoverage.NON_BUSINESS;
    }

    private boolean isMaterialAggregateQuery(String message) {
        if (containsAny(message, "欠款", "应收", "应付", "已收", "已付", "实收", "实付",
                "回款", "催缴", "追缴", "逾期付款", "未付款")) {
            return false;
        }
        if (containsAny(message, "未归还", "没有归还", "还没归还", "尚未归还", "仍在租", "在租材料",
                "材料占用", "占用情况")) {
            return false;
        }
        if (containsAny(message, "赔偿") && containsAny(message, "金额", "费用", "收费", "收入", "应收")) {
            return false;
        }
        boolean flow = containsAny(message, "租出", "归还", "赔偿", "租入", "退租");
        boolean aggregate = containsAny(message, "最多", "最少", "排行", "排名", "前", "top", "累计", "合计",
                "多少", "数量", "次数", "频次", "哪些", "明细", "本月", "这个月", "今年", "期间");
        return flow && aggregate;
    }

    private boolean isProjectTypeOrStatusCountQuery(String message) {
        boolean projectContext = containsAny(message, "项目");
        boolean projectDimension = containsAny(message, "租入", "租出", "进行中", "已完成", "项目状态", "项目类型");
        boolean aggregate = containsAny(message, "统计", "数量", "多少", "几个", "合计", "汇总", "占比");
        boolean materialOrDocumentFlow = containsAny(message, "材料", "物料", "单据", "流水", "租出单", "归还单",
                "赔偿单", "租入单", "退租单", "单数", "次数");
        return projectContext && projectDimension && aggregate && !materialOrDocumentFlow;
    }

    private boolean isSystemHelpQuery(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        boolean explicitHelp = containsAny(message, "怎么用", "如何使用", "怎么使用", "如何操作", "怎么操作",
                "帮助", "教程", "使用说明", "帮助中心");
        boolean configuration = containsAny(message, "设置", "配置", "阈值", "参数配置", "开关配置");
        boolean fieldRule = containsAny(message, "必填", "必填项", "字段规则", "字段说明", "字段要求",
                "哪些字段", "需要填什么");
        boolean operationFailure = containsAny(message, "报错", "操作失败", "提交失败", "保存失败", "导入失败",
                "无法操作", "不能操作", "提交不了", "保存不了", "不生效", "系统异常");
        boolean locationOrFlow = containsAny(message, "在哪里", "在哪儿", "入口在哪", "入口位置",
                "操作步骤", "操作流程", "办理步骤", "办理流程");
        boolean how = isHowExpression(message);
        boolean createOrOperate = containsAny(message, "新建", "创建", "录入", "登记", "添加", "上传", "导入", "导出",
                "提交", "保存", "审核", "撤回", "删除", "打印", "操作", "查", "查询", "查看", "搜索",
                "填", "填写", "改", "修改", "编辑", "选择", "维护");
        return explicitHelp || configuration || fieldRule || operationFailure || locationOrFlow
                || (how && createOrOperate);
    }

    private boolean isHowExpression(String message) {
        return containsAny(message, "怎么", "如何", "怎样");
    }

    private boolean isSupportedHowFactQuery(String message,
                                            AgentRuntimeRecords.Workspace workspace) {
        boolean inventoryFact = containsAny(message, "库存情况如何", "库存现状如何", "当前库存如何",
                "仓库库存如何");
        boolean singleProjectSummary = isSingleProject(workspace)
                && containsAny(message, "经营情况如何", "经营概况如何", "项目概况如何");
        return inventoryFact || singleProjectSummary;
    }

    private boolean isEnterpriseKnowledgeQuery(String message) {
        boolean knownEnterprisePhrase = containsAny(message, "公司制度", "企业制度", "内部规定", "公司规定", "企业知识", "内部知识",
                "内部模板", "公司模板", "企业模板", "内部标准流程");
        boolean enterpriseScope = containsAny(message, "公司上传", "企业上传", "内部上传", "公司内部", "企业内部");
        boolean knowledgeAsset = containsAny(message, "制度", "规定", "知识", "合同模板", "模板", "操作规范", "管理规范", "文档");
        boolean enterpriseAssetLookup = containsAny(message, "查询", "查找", "搜索", "检索", "查看", "列出", "有哪些")
                && containsAny(message, "公司", "企业", "内部", "上传")
                && knowledgeAsset;
        return knownEnterprisePhrase || (enterpriseScope && knowledgeAsset) || enterpriseAssetLookup;
    }

    private boolean isCollectionEnforcementQuery(String message) {
        if (AgentFinanceIntentPolicy.isSupplierDirection(message)
                || AgentFinanceIntentPolicy.hasNonCurrentCutoffReference(message)) {
            return false;
        }
        boolean explicitEnforcement = containsAny(message, "催缴", "追缴", "催款", "回款清单");
        boolean receivableDirection = containsAny(message, "租出", "客户", "应收", "欠款");
        boolean listIntent = containsAny(message, "哪些", "有哪些", "列表", "清单");
        boolean dueOrDebt = containsAny(message, "到期未付", "到期没付", "逾期", "欠款");
        boolean explicitOverdueOrDue = containsAny(message, "到期未付", "到期没付", "逾期");
        return explicitEnforcement
                || (receivableDirection && dueOrDebt && (listIntent || explicitOverdueOrDue));
    }

    private boolean isReconciliationQuery(String message) {
        return containsAny(message, "未对账", "从未对账", "没有对账", "应该对账", "需要对账",
                "待对账", "对账情况", "对账缺口", "结算周期");
    }

    private boolean isEnterpriseKpiQuery(String message) {
        return AgentFinanceIntentPolicy.isEnterpriseKpi(message);
    }

    private boolean isSupplierPayableQuery(String message) {
        return AgentFinanceIntentPolicy.isSupplierPayable(message);
    }

    private boolean isContractStatusQuery(String message) {
        if (!containsAny(message, "合同")
                || containsAny(message, "合同模板", "字段", "必填", "怎么", "如何", "怎样")) {
            return false;
        }
        return containsAny(message, "到期", "有效", "未开始", "合同状态", "合同期限", "起止日期",
                "开始日期", "结束日期", "未录合同", "没有合同", "未签合同", "缺少合同",
                "没有录入合同", "未录入合同");
    }

    private boolean isMaterialEstimateQuery(String message) {
        return containsAny(message, "材料预估", "物料预估", "预估材料", "用量估算", "材料估算", "估算材料",
                "材料测算", "测算材料", "需要多少材料", "需要哪些材料", "脚手架预估");
    }

    private boolean isProjectCatalogQuery(String message) {
        String source = stripEvaluationPrefix(message);
        if (isContractStatusQuery(source)) {
            return false;
        }
        if (isPlainProjectCountQuery(source)) {
            return true;
        }
        if (containsAny(source, "项目清单", "项目负责人")) {
            return true;
        }
        if (containsAny(source, "项目")
                && containsAny(source, "新建", "创建", "录入")
                && containsAny(source, "今年", "本年", "本月", "这个月", "哪些", "多少", "查询", "查看", "列出", "已新建", "已创建", "已录入")) {
            return true;
        }
        String normalized = source == null ? "" : source.replaceAll("[，。？！?\\s]", "");
        return normalized.equals("哪些项目")
                || normalized.equals("有哪些项目")
                || normalized.equals("公司有哪些项目")
                || normalized.equals("公司有多少项目")
                || normalized.equals("项目有哪些")
                || normalized.equals("给我所有项目的列表")
                || normalized.equals("给我全部项目的列表")
                || normalized.equals("列出所有项目")
                || normalized.equals("列出全部项目")
                || normalized.equals("所有项目的列表")
                || normalized.equals("全部项目的列表");
    }

    private boolean isPlainProjectCountQuery(String message) {
        boolean projectCount = containsAny(message, "项目数量", "项目数", "多少个项目", "几个项目")
                || (containsAny(message, "项目") && containsAny(message, "数量")
                && containsAny(message, "统计", "全部", "所有", "当前", "合计"));
        boolean materialOrDocumentFlow = containsAny(message, "材料", "物料", "单据", "流水", "租出单", "归还单",
                "赔偿单", "租入单", "退租单", "单数", "次数", "租出材料", "归还材料");
        return projectCount && !materialOrDocumentFlow;
    }

    private String stripEvaluationPrefix(String message) {
        return StringUtils.hasText(message)
                ? message.replaceFirst("(?i)^\\s*\\[?E2E[^\\]：:]{0,40}\\]?\\s*[：:]\\s*", "")
                : message;
    }

    private boolean isDocumentSearchQuery(String message) {
        if (containsAny(message, "待审核", "未审核", "需要审核", "审核单据")) {
            return false;
        }
        return containsAny(message, "租出单", "归还单", "赔偿单", "租入单", "退租单", "对账单", "结算单")
                && containsAny(message, "查询", "查找", "搜索", "编号", "最近", "有哪些", "列出", "明细");
    }

    private boolean isExplicitNonBusiness(String message) {
        if (!StringUtils.hasText(message)) {
            return true;
        }
        String normalized = message.trim().replaceAll("[，。？！?\\s]", "").toLowerCase(Locale.ROOT);
        if (normalized.equals("你好") || normalized.equals("您好") || normalized.equals("谢谢")
                || normalized.equals("感谢") || normalized.equals("再见") || normalized.equals("辛苦了")
                || normalized.equals("讲个笑话") || normalized.equals("你是谁")
                || normalized.equals("你能做什么") || normalized.equals("你会什么")
                || normalized.equals("介绍一下自己")) {
            return true;
        }
        boolean communicationAdvice = containsAny(message, "客户沟通", "商务沟通", "沟通技巧")
                && containsAny(message, "注意什么", "怎么沟通", "如何沟通", "有什么建议", "应该注意");
        boolean teamActivityAdvice = containsAny(message, "团建", "聚餐", "旅游", "出游")
                && containsAny(message, "去哪", "哪里", "怎么安排", "有什么建议", "比较好");
        return communicationAdvice || teamActivityAdvice;
    }

    private boolean isSingleProject(AgentRuntimeRecords.Workspace workspace) {
        return workspace != null
                && workspace.getProjectIds() != null
                && workspace.getProjectIds().size() == 1
                && StringUtils.hasText(workspace.getProjectId());
    }

    private String resolveTimeRange(String message, ZoneId zoneId) {
        LocalDate today = LocalDate.now(zoneId);
        Matcher yearMonth = YEAR_MONTH.matcher(message == null ? "" : message);
        if (yearMonth.find()) {
            YearMonth value = YearMonth.of(Integer.parseInt(yearMonth.group(1)), Integer.parseInt(yearMonth.group(2)));
            return value.atDay(1) + "~" + value.atEndOfMonth();
        }
        Matcher monthOnly = MONTH_ONLY.matcher(message == null ? "" : message);
        if (monthOnly.find()) {
            YearMonth value = YearMonth.of(today.getYear(), Integer.parseInt(monthOnly.group(1)));
            return value.atDay(1) + "~" + value.atEndOfMonth();
        }
        if (containsAny(message, "今年", "本年", "年度")) {
            return LocalDate.of(today.getYear(), 1, 1) + "~" + today;
        }
        if (containsAny(message, "本月", "这个月", "当月")) {
            YearMonth value = YearMonth.from(today);
            return value.atDay(1) + "~" + today;
        }
        if (containsAny(message, "今天", "截至今天", "当前")) {
            return "asOf=" + today;
        }
        return "UNSPECIFIED";
    }

    private String resolveSelectionMode(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !StringUtils.hasText(workspace.getSelectionMode())) {
            return "EXPLICIT";
        }
        return workspace.getSelectionMode().toUpperCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
