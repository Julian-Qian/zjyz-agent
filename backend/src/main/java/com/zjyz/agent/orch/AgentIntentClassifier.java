package com.zjyz.agent.orch;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class AgentIntentClassifier {

    public AgentIntentType classify(String message) {
        if (isEnterpriseKnowledgeIntent(message)) {
            return AgentIntentType.ENTERPRISE_KNOWLEDGE_UNSUPPORTED;
        }
        // Procedural/system-help wording must win before any business-data keyword matcher.
        // Otherwise nouns such as "库存", "合同" or "项目" can incorrectly route a how-to
        // question to a deterministic data tool that cannot satisfy help.search evidence.
        if (isHelpKnowledgeIntent(message)) {
            return AgentIntentType.HELP_KNOWLEDGE;
        }
        if (AgentUnsupportedMetricPolicy.containsUnsupportedMetric(message)) {
            return AgentIntentType.UNSUPPORTED_BUSINESS;
        }
        if (AgentOwnerActionIntentPolicy.hasUnknownRiskDimension(message)) {
            return AgentIntentType.UNSUPPORTED_BUSINESS;
        }
        if (AgentOwnerActionIntentPolicy.isOwnerActionCenter(message)
                && AgentOwnerActionIntentPolicy.hasUnsupportedSnapshotTime(message)) {
            return AgentIntentType.UNSUPPORTED_BUSINESS;
        }
        if (AgentOwnerActionIntentPolicy.isOwnerActionCenter(message)) {
            return AgentIntentType.RISK_OWNER_ACTION_CENTER;
        }
        if (AgentFinanceIntentPolicy.isSupplierDirection(message)) {
            if (AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(message)) {
                return AgentIntentType.UNSUPPORTED_BUSINESS;
            }
            if (isSupplierPayableIntent(message)) {
                return AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY;
            }
        }
        if (isCollectionEnforcementIntent(message)) {
            return AgentIntentType.FINANCE_RECEIVABLE_COLLECTION;
        }
        if (AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(message)) {
            return AgentIntentType.UNSUPPORTED_BUSINESS;
        }
        if (isContractStatusIntent(message)) {
            return AgentIntentType.PROJECT_CONTRACT_STATUS;
        }
        if (isEnterpriseKpiIntent(message)) {
            return AgentIntentType.FINANCE_ENTERPRISE_KPI;
        }
        if (isSupplierPayableIntent(message)) {
            return AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY;
        }
        if (!AgentFinanceIntentPolicy.isSupplierDirection(message)
                && containsAny(message, "欠款", "未付款", "逾期付款", "催缴", "追缴", "应收", "回款清单")) {
            return AgentIntentType.FINANCE_RECEIVABLE_COLLECTION;
        }
        if (isReconciliationDueIntent(message)) {
            return AgentIntentType.PROJECT_RECONCILIATION_DUE;
        }
        if (isProjectRentMaterialsIntent(message)) {
            return AgentIntentType.PROJECT_RENT_MATERIALS;
        }
        if (isProjectListIntent(message)) {
            return AgentIntentType.PROJECT_LIST;
        }
        if (isMaterialEstimateIntent(message)) {
            return AgentIntentType.MATERIAL_ESTIMATE;
        }
        if (containsAny(message, "下载", "单据", "导出", "附件", "采购", "出库", "入库", "归还单", "租出单", "租入单", "退租单", "对账", "结算", "财务")) {
            return AgentIntentType.DOCUMENT_SEARCH;
        }
        if (containsAny(message, "库存", "材料", "趋势", "下降", "六个月", "半年")) {
            return AgentIntentType.INVENTORY_SUMMARY;
        }
        if ((containsAny(message, "风险") && !containsAny(message, "项目"))
                || (containsAny(message, "供应商") && containsAny(message, "履约"))) {
            return AgentIntentType.UNSUPPORTED_BUSINESS;
        }
        return AgentIntentType.PROJECT_SUMMARY;
    }

    private boolean isProjectListIntent(String message) {
        String source = stripEvaluationPrefix(message);
        if (isProjectTypeOrStatusCountIntent(source) || isPlainProjectCountIntent(source)) {
            return true;
        }
        return containsAny(source, "项目")
                && containsAny(source, "哪些", "列表", "清单", "新建", "录入", "创建", "新增", "多少个", "项目数", "今年", "本年", "本月");
    }

    private boolean isCollectionEnforcementIntent(String message) {
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

    private boolean isEnterpriseKpiIntent(String message) {
        return AgentFinanceIntentPolicy.isEnterpriseKpi(message);
    }

    private boolean isSupplierPayableIntent(String message) {
        return AgentFinanceIntentPolicy.isSupplierPayable(message);
    }

    private boolean isPlainProjectCountIntent(String message) {
        boolean projectCount = containsAny(message, "项目数量", "项目数", "多少个项目", "几个项目")
                || (containsAny(message, "项目") && containsAny(message, "数量")
                && containsAny(message, "统计", "全部", "所有", "当前", "合计"));
        boolean materialOrDocumentFlow = containsAny(message, "材料", "物料", "单据", "流水", "租出单", "归还单",
                "赔偿单", "租入单", "退租单", "单数", "次数", "租出材料", "归还材料");
        return projectCount && !materialOrDocumentFlow;
    }

    private boolean isProjectTypeOrStatusCountIntent(String message) {
        boolean projectContext = containsAny(message, "项目");
        boolean projectDimension = containsAny(message, "租入", "租出", "进行中", "已完成", "项目状态", "项目类型");
        boolean aggregate = containsAny(message, "统计", "数量", "多少", "几个", "合计", "汇总", "占比");
        boolean materialOrDocumentFlow = containsAny(message, "材料", "物料", "单据", "流水", "租出单", "归还单",
                "赔偿单", "租入单", "退租单", "单数", "次数");
        return projectContext && projectDimension && aggregate && !materialOrDocumentFlow;
    }

    private boolean isReconciliationDueIntent(String message) {
        return containsAny(message, "对账", "结算")
                && containsAny(message, "项目", "哪些", "哪个", "应该", "需要", "要进行", "待", "本月", "这个月", "月份", "月");
    }

    private boolean isContractStatusIntent(String message) {
        if (!containsAny(message, "合同")
                || containsAny(message, "合同模板", "字段", "必填", "怎么", "如何", "怎样")) {
            return false;
        }
        return containsAny(message, "到期", "有效", "未开始", "合同状态", "合同期限", "起止日期",
                "开始日期", "结束日期", "未录合同", "没有合同", "未签合同", "缺少合同",
                "没有录入合同", "未录入合同");
    }

    private boolean isProjectRentMaterialsIntent(String message) {
        boolean currentOccupancy = containsAny(message, "在租", "租出中", "租入中", "在租材料", "租出材料", "租赁材料", "未归还材料");
        boolean compensationMoney = containsAny(message, "赔偿")
                && containsAny(message, "金额", "费用", "收费", "收入", "应收");
        boolean transactionAggregate = !isProjectTypeOrStatusCountIntent(message)
                && !compensationMoney
                && containsAny(message, "租出", "归还", "赔偿", "租入", "退租")
                && containsAny(message, "最多", "最少", "排行", "排名", "前", "top", "累计", "合计", "汇总",
                "多少", "数量", "次数", "频次", "哪些", "明细", "本月", "这个月", "今年", "期间", "单数", "流水");
        return (containsAny(message, "材料", "物料") && currentOccupancy) || transactionAggregate;
    }

    private boolean isMaterialEstimateIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return containsAny(message,
                "材料预估", "用量估算", "估算一下", "估一下", "需要多少材料",
                "库存够不够", "能不能接", "接单分析", "报价估算", "脚手架预估");
    }

    private boolean isHelpKnowledgeIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        boolean explicitHelp = containsAny(message,
                "怎么用", "怎么使用", "如何使用", "如何操作", "怎么操作",
                "使用说明", "操作说明", "使用方法", "新手指引", "教程", "帮助文档",
                "帮助中心", "不会用", "怎么上手");
        boolean configuration = containsAny(message, "设置", "配置", "阈值", "参数配置", "开关配置");
        boolean fieldRule = containsAny(message, "必填", "必填项", "字段规则", "字段说明", "字段要求",
                "哪些字段", "需要填什么");
        boolean operationFailure = containsAny(message, "报错", "操作失败", "提交失败", "保存失败", "导入失败",
                "无法操作", "不能操作", "提交不了", "保存不了", "不生效", "系统异常");
        boolean locationOrFlow = containsAny(message, "在哪里", "在哪儿", "入口在哪", "入口位置",
                "操作步骤", "操作流程", "办理步骤", "办理流程",
                "这些文档", "这些文章", "上面的文档", "文档在哪里", "文档在哪",
                "在哪里可以找到", "到哪里找", "哪里看");
        boolean how = containsAny(message, "怎么", "如何", "怎样");
        boolean createOrOperate = containsAny(message, "新建", "创建", "录入", "登记", "添加", "上传", "导入", "导出",
                "提交", "保存", "审核", "撤回", "删除", "打印", "操作", "查", "查询", "查看", "搜索",
                "填", "填写", "改", "修改", "编辑", "选择", "维护");
        if (explicitHelp || configuration || fieldRule || operationFailure || locationOrFlow
                || (how && createOrOperate)) {
            return true;
        }
        return containsAny(message, "项目管理", "进销存", "库存管理", "租出管理", "租入管理", "对账管理")
                && containsAny(message, "使用", "流程", "步骤", "说明", "教程", "指引", "上手");
    }

    private boolean isEnterpriseKnowledgeIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        boolean knownEnterprisePhrase = containsAny(message, "公司制度", "企业制度", "内部规定", "公司规定",
                "企业知识", "内部知识", "内部模板", "公司模板", "企业模板", "内部标准流程");
        boolean enterpriseScope = containsAny(message, "公司上传", "企业上传", "内部上传", "公司内部", "企业内部");
        boolean knowledgeAsset = containsAny(message, "制度", "规定", "知识", "合同模板", "模板", "操作规范", "管理规范", "文档");
        boolean enterpriseAssetLookup = containsAny(message, "查询", "查找", "搜索", "检索", "查看", "列出", "有哪些")
                && containsAny(message, "公司", "企业", "内部", "上传")
                && knowledgeAsset;
        return knownEnterprisePhrase || (enterpriseScope && knowledgeAsset) || enterpriseAssetLookup;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String stripEvaluationPrefix(String message) {
        return StringUtils.hasText(message)
                ? message.replaceFirst("(?i)^\\s*\\[?E2E[^\\]：:]{0,40}\\]?\\s*[：:]\\s*", "")
                : message;
    }
}
