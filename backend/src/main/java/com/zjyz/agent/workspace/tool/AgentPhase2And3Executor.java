package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.pojo.entity.CompensationDocumentEntity;
import com.zjyz.pojo.entity.MaterialInfoEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.ProjectMaterialPricingEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import com.zjyz.pojo.entity.SettlementDocumentEntity;
import com.zjyz.pojo.entity.SettlementRentFeeSegmentEntity;
import com.zjyz.pojo.param.req.CreateDocumentParam;
import com.zjyz.pojo.param.req.LateFeePreviewParam;
import com.zjyz.pojo.param.req.QueryInventoryMaterialsParam;
import com.zjyz.pojo.param.req.QueryMarketMaterialListParam;
import com.zjyz.pojo.param.req.QueryMaterialGovernanceParam;
import com.zjyz.pojo.param.req.QueryMaterialInfoParam;
import com.zjyz.pojo.param.req.RecommendForEstimateParam;
import com.zjyz.pojo.param.req.SettlementPreviewParam;
import com.zjyz.pojo.param.ret.ContractInfoRet;
import com.zjyz.pojo.param.ret.InventoryMaterialItemRet;
import com.zjyz.pojo.param.ret.InventoryMaterialListRet;
import com.zjyz.pojo.param.ret.LateFeeDocumentRet;
import com.zjyz.pojo.param.ret.MarketMaterialListRet;
import com.zjyz.pojo.param.ret.MarketRecommendRet;
import com.zjyz.pojo.param.ret.SiteDistributionMaterialRet;
import com.zjyz.pojo.param.ret.MaterialEstimateMatchRet;
import com.zjyz.pojo.param.ret.MaterialGovernanceRet;
import com.zjyz.pojo.param.ret.MaterialInfoRet;
import com.zjyz.pojo.param.ret.ReconciliationPreviewRet;
import com.zjyz.pojo.param.ret.SettlementDocumentInfoRet;
import com.zjyz.pojo.param.ret.SettlementPeriodListRet;
import com.zjyz.pojo.param.ret.SettlementPreviewRet;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 第二阶段（执行型 Agent：HITL 草稿试算预览 + 数据治理体检）与
 * 第三阶段（助手型 Agent：单项目副驾 Project Copilot + 公司经营参谋 Enterprise Advisor）
 * 确定性执行引擎。所有方法均在只读沙箱内运行，不直接修改正式业务库。
 */
final class AgentPhase2And3Executor {

    private AgentPhase2And3Executor() {
    }

    static boolean supports(String code) {
        return AgentToolCodes.DOCUMENT_PREVIEW_DRAFT.equals(code)
                || AgentToolCodes.SETTLEMENT_PREVIEW_DRAFT.equals(code)
                || AgentToolCodes.RECONCILIATION_PREVIEW_DRAFT.equals(code)
                || AgentToolCodes.FINANCE_LATE_FEE_TRIAL.equals(code)
                || AgentToolCodes.GOVERNANCE_MATERIAL_ALIAS_AUDIT.equals(code)
                || AgentToolCodes.COPILOT_ORDER_FULFILLMENT_EVAL.equals(code)
                || AgentToolCodes.COPILOT_CONTRACT_EXECUTION_AUDIT.equals(code)
                || AgentToolCodes.COPILOT_PRICE_BREAKEVEN_SANDBOX.equals(code)
                || AgentToolCodes.COPILOT_SETTLEMENT_DISPUTE_ATTRIBUTION.equals(code)
                || AgentToolCodes.ADVISOR_MATERIAL_BCG_MATRIX.equals(code)
                || AgentToolCodes.ADVISOR_COUNTERPARTY_CREDIT_PROFILE.equals(code)
                || AgentToolCodes.ADVISOR_SUBLEASE_ARBITRAGE_RADAR.equals(code)
                || AgentToolCodes.ADVISOR_CROSS_SITE_TRANSFER_PLAN.equals(code);
    }

    static Map<String, Object> execute(AgentBusinessQueryService s,
                                       String code,
                                       Map<String, Object> a,
                                       AgentRuntimeRecords.Workspace w) {
        return execute(s, code, a, null, w);
    }

    static Map<String, Object> execute(AgentBusinessQueryService s,
                                       String code,
                                       Map<String, Object> a,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace w) {
        Map<String, Object> result;
        switch (code) {
            case AgentToolCodes.DOCUMENT_PREVIEW_DRAFT:
                result = previewDocumentDraft(s, a, originalMessage, w);
                break;
            case AgentToolCodes.SETTLEMENT_PREVIEW_DRAFT:
                result = previewSettlementDraft(s, a, w);
                break;
            case AgentToolCodes.RECONCILIATION_PREVIEW_DRAFT:
                result = previewReconciliationDraft(s, a, w);
                break;
            case AgentToolCodes.FINANCE_LATE_FEE_TRIAL:
                result = trialLateFee(s, a, w);
                break;
            case AgentToolCodes.GOVERNANCE_MATERIAL_ALIAS_AUDIT:
                result = auditMaterialGovernance(s, a);
                break;
            case AgentToolCodes.COPILOT_ORDER_FULFILLMENT_EVAL:
                result = evaluateOrderFulfillment(s, a, originalMessage, w);
                break;
            case AgentToolCodes.COPILOT_CONTRACT_EXECUTION_AUDIT:
                result = auditContractExecution(s, a, w);
                break;
            case AgentToolCodes.COPILOT_PRICE_BREAKEVEN_SANDBOX:
                result = runPriceBreakevenSandbox(s, a, originalMessage, w);
                break;
            case AgentToolCodes.COPILOT_SETTLEMENT_DISPUTE_ATTRIBUTION:
                result = attributeSettlementDispute(s, a, w);
                break;
            case AgentToolCodes.ADVISOR_MATERIAL_BCG_MATRIX:
                result = buildMaterialBcgMatrix(s, a);
                break;
            case AgentToolCodes.ADVISOR_COUNTERPARTY_CREDIT_PROFILE:
                result = buildCounterpartyCreditProfile(s, a, w);
                break;
            case AgentToolCodes.ADVISOR_SUBLEASE_ARBITRAGE_RADAR:
                result = scanSubleaseArbitrage(s, a);
                break;
            case AgentToolCodes.ADVISOR_CROSS_SITE_TRANSFER_PLAN:
                result = planCrossSiteTransfer(s, a, w);
                break;
            default:
                throw new IllegalArgumentException("Unsupported Phase 2/3 capability: " + code);
        }
        return enrichPyramidOutput(code, result);
    }

    // =========================================================================
    // 0. document.preview_draft — 业务单据建单核查与操作引导 (Phase 2 Guided Workflow)
    // =========================================================================
    private static Map<String, Object> previewDocumentDraft(AgentBusinessQueryService s,
                                                            Map<String, Object> a,
                                                            String originalMessage,
                                                            AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String cid = w.getCid();
        String projectName = resolveProjectDisplayName(s, cid, projectId);
        String docType = str(a, "documentType");
        if (!StringUtils.hasText(docType)) {
            String msg = safe(originalMessage);
            if (msg.contains("归还")) docType = "RETURN";
            else if (msg.contains("赔偿")) docType = "COMPENSATION";
            else if (msg.contains("退租")) docType = "RENT_IN_RETURN";
            else if (msg.contains("租入")) docType = "RENT_IN";
            else docType = "RENT_OUT";
        }
        String tabLabel;
        String workflow;
        switch (docType) {
            case "RETURN":
                tabLabel = "归还单";
                workflow = "returnOrder";
                break;
            case "COMPENSATION":
                tabLabel = "赔偿单";
                workflow = "compensationOrder";
                break;
            case "RENT_IN":
                tabLabel = "租入单";
                workflow = "rentIn";
                break;
            case "RENT_IN_RETURN":
                tabLabel = "退租单";
                workflow = "rentInReturn";
                break;
            default:
                docType = "RENT_OUT";
                tabLabel = "租出单";
                workflow = "rentOut";
                break;
        }
        String startDate = StringUtils.hasText(str(a, "startDate"))
                ? str(a, "startDate") : LocalDate.now().toString();
        String keyword = str(a, "keyword");

        List<RequestedMaterialSpec> requestedSpecs = parseRequestedMaterials(keyword, originalMessage);
        List<InventoryMaterialItemRet> allInventory = loadAllInventoryMaterials(s, null, 500);
        List<ProjectMaterialPricingEntity> projectPricings = s.projectMaterialPricingMapper != null
                ? s.projectMaterialPricingMapper.selectList(new QueryWrapper<ProjectMaterialPricingEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        List<Map<String, Object>> inventoryCheckList = new ArrayList<>();
        List<Map<String, Object>> contractPricingList = new ArrayList<>();
        int sufficientCount = 0;
        int shortageCount = 0;
        int pricedCount = 0;
        int unpricedCount = 0;
        double estimatedDailyRentTotal = 0d;

        if (!requestedSpecs.isEmpty()) {
            for (RequestedMaterialSpec req : requestedSpecs) {
                InventoryMaterialItemRet bestInv = matchBestInventoryItem(req, allInventory);
                Map<String, Object> invRow = new LinkedHashMap<>();
                if (bestInv != null) {
                    int stockQty = bestInv.getInventoryQuantity() == null ? 0 : bestInv.getInventoryQuantity();
                    int rentedQty = bestInv.getRentedQuantity() == null ? 0 : bestInv.getRentedQuantity();
                    String unit = StringUtils.hasText(bestInv.getInventoryUnit()) ? bestInv.getInventoryUnit() : req.unit;
                    invRow.put("materialId", bestInv.getMaterialId());
                    invRow.put("materialName", bestInv.getMaterialName());
                    invRow.put("materialSpecification", bestInv.getMaterialSpecification());
                    invRow.put("inventoryUnit", unit);
                    invRow.put("inventoryQuantity", stockQty);
                    invRow.put("rentedQuantity", rentedQty);
                    if (req.quantity > 0d) {
                        invRow.put("requiredQty", round2(req.quantity));
                    }
                    if (stockQty > 0 && (req.quantity <= 0d || stockQty >= req.quantity)) {
                        sufficientCount++;
                        invRow.put("stockCheckStatus", String.format(Locale.ROOT,
                                "库存充足（当前可用 %,d %s%s）",
                                stockQty, safe(unit),
                                req.quantity > 0d ? "，满足计划数量 " + (int) req.quantity : ""));
                    } else if (stockQty > 0) {
                        shortageCount++;
                        invRow.put("stockCheckStatus", String.format(Locale.ROOT,
                                "库存部分满足（当前可用 %,d %s，计划 %,d，缺口 %,d）",
                                stockQty, safe(unit), (int) req.quantity, (int) Math.ceil(req.quantity - stockQty)));
                    } else {
                        shortageCount++;
                        invRow.put("stockCheckStatus", String.format(Locale.ROOT,
                                "库存不足（当前可用 0 %s%s，建单前请先采购入库或跨工地调拨）",
                                safe(unit), req.quantity > 0d ? "，计划数量 " + (int) req.quantity : ""));
                    }
                } else {
                    shortageCount++;
                    invRow.put("materialName", req.rawName);
                    invRow.put("materialSpecification", req.specHint);
                    invRow.put("inventoryUnit", req.unit);
                    invRow.put("inventoryQuantity", 0);
                    if (req.quantity > 0d) {
                        invRow.put("requiredQty", round2(req.quantity));
                    }
                    invRow.put("stockCheckStatus", "企业库房未查到该规格现货（当前可用 0，请核对材料库建档名称或先办理入库）");
                }
                inventoryCheckList.add(invRow);

                ProjectMaterialPricingEntity bestPrice = matchBestContractPricing(req, bestInv, projectPricings);
                Map<String, Object> priceRow = new LinkedHashMap<>();
                if (bestPrice != null) {
                    double dailyRent = bestPrice.getDailyRent() != null ? bestPrice.getDailyRent().doubleValue()
                            : bestPrice.getSaleUnitPrice() != null ? bestPrice.getSaleUnitPrice().doubleValue() : 0d;
                    double compPrice = bestPrice.getCompensationUnitPrice() != null
                            ? bestPrice.getCompensationUnitPrice().doubleValue() : 0d;
                    double loadFee = bestPrice.getLoadingFeeUnitPrice() != null
                            ? bestPrice.getLoadingFeeUnitPrice().doubleValue() : 0d;
                    double unloadFee = bestPrice.getUnloadingFeeUnitPrice() != null
                            ? bestPrice.getUnloadingFeeUnitPrice().doubleValue() : 0d;
                    double freightFee = bestPrice.getFreightUnitPrice() != null
                            ? bestPrice.getFreightUnitPrice().doubleValue() : 0d;
                    double qtyForCalc = req.quantity > 0d ? req.quantity
                            : (bestPrice.getPredictRentNumber() != null && bestPrice.getPredictRentNumber() > 0
                                ? bestPrice.getPredictRentNumber() : 1d);
                    double estItemDaily = round2(dailyRent * qtyForCalc);
                    estimatedDailyRentTotal = round2(estimatedDailyRentTotal + estItemDaily);
                    priceRow.put("materialName", bestPrice.getMaterialName());
                    priceRow.put("materialSpecification", bestPrice.getMaterialSpecification());
                    priceRow.put("contractPricingUnit", StringUtils.hasText(bestPrice.getContractPricingUnit())
                            ? bestPrice.getContractPricingUnit() : bestPrice.getCountingUnit());
                    priceRow.put("dailyRent", round2(dailyRent));
                    priceRow.put("compensationUnitPrice", round2(compPrice));
                    priceRow.put("estimatedItemDailyRent", estItemDaily);
                    priceRow.put("miscFeeStandard", String.format(Locale.ROOT,
                            "装车费 %,.2f / 卸车费 %,.2f / 运费 %,.2f", loadFee, unloadFee, freightFee));
                    if (dailyRent > 0d) {
                        pricedCount++;
                        priceRow.put("pricingCheckStatus", "已配置合同单价（建单时系统自动带出）");
                    } else {
                        unpricedCount++;
                        priceRow.put("pricingCheckStatus", "合同日租金为 0，请先在「项目合同」核定单价");
                    }
                } else {
                    unpricedCount++;
                    priceRow.put("materialName", bestInv != null ? bestInv.getMaterialName() : req.rawName);
                    priceRow.put("materialSpecification", bestInv != null ? bestInv.getMaterialSpecification() : req.specHint);
                    priceRow.put("contractPricingUnit", bestInv != null ? bestInv.getInventoryUnit() : req.unit);
                    priceRow.put("dailyRent", 0d);
                    priceRow.put("estimatedItemDailyRent", 0d);
                    priceRow.put("miscFeeStandard", "待配置（按项目合同装卸及运费条款执行）");
                    priceRow.put("pricingCheckStatus", "该项目合同暂未配置此规格单价，正式建单前请先在「项目合同」补充材料定价");
                }
                contractPricingList.add(priceRow);
            }
        } else {
            for (ProjectMaterialPricingEntity p : projectPricings) {
                if (contractPricingList.size() >= 10) break;
                double dailyRent = p.getDailyRent() != null ? p.getDailyRent().doubleValue()
                        : p.getSaleUnitPrice() != null ? p.getSaleUnitPrice().doubleValue() : 0d;
                double loadFee = p.getLoadingFeeUnitPrice() != null ? p.getLoadingFeeUnitPrice().doubleValue() : 0d;
                double unloadFee = p.getUnloadingFeeUnitPrice() != null ? p.getUnloadingFeeUnitPrice().doubleValue() : 0d;
                double freightFee = p.getFreightUnitPrice() != null ? p.getFreightUnitPrice().doubleValue() : 0d;
                double qty = p.getPredictRentNumber() != null && p.getPredictRentNumber() > 0 ? p.getPredictRentNumber() : 1d;
                double estDaily = round2(dailyRent * qty);
                estimatedDailyRentTotal = round2(estimatedDailyRentTotal + estDaily);
                if (dailyRent > 0d) pricedCount++; else unpricedCount++;
                Map<String, Object> priceRow = new LinkedHashMap<>();
                priceRow.put("materialName", p.getMaterialName());
                priceRow.put("materialSpecification", p.getMaterialSpecification());
                priceRow.put("contractPricingUnit", StringUtils.hasText(p.getContractPricingUnit())
                        ? p.getContractPricingUnit() : p.getCountingUnit());
                priceRow.put("dailyRent", round2(dailyRent));
                priceRow.put("estimatedItemDailyRent", estDaily);
                priceRow.put("miscFeeStandard", String.format(Locale.ROOT,
                        "装车费 %,.2f / 卸车费 %,.2f / 运费 %,.2f", loadFee, unloadFee, freightFee));
                priceRow.put("pricingCheckStatus", dailyRent > 0d ? "已配置合同单价" : "待补充合同单价");
                contractPricingList.add(priceRow);
            }
            for (InventoryMaterialItemRet inv : allInventory) {
                int stock = inv.getInventoryQuantity() == null ? 0 : inv.getInventoryQuantity();
                int rented = inv.getRentedQuantity() == null ? 0 : inv.getRentedQuantity();
                if (stock == 0 && rented == 0) continue;
                if (inventoryCheckList.size() >= 10) break;
                if (stock > 0) sufficientCount++; else shortageCount++;
                Map<String, Object> invRow = new LinkedHashMap<>();
                invRow.put("materialId", inv.getMaterialId());
                invRow.put("materialName", inv.getMaterialName());
                invRow.put("materialSpecification", inv.getMaterialSpecification());
                invRow.put("inventoryUnit", inv.getInventoryUnit());
                invRow.put("inventoryQuantity", stock);
                invRow.put("rentedQuantity", rented);
                invRow.put("stockCheckStatus", stock > 0
                        ? String.format(Locale.ROOT, "库存充足（当前可用 %,d %s）", stock, safe(inv.getInventoryUnit()))
                        : "当前可用库存为 0");
                inventoryCheckList.add(invRow);
            }
        }

        List<String> operationSteps = Arrays.asList(
                "第 1 步【直达建单页面】：点击本卡片下方「打开项目业务页面（直达" + tabLabel + "）」按钮，直接进入项目「" + projectName + "」的「" + tabLabel + "」管理页签。",
                "第 2 步【核对前置条件】：若上方核查提示某规格基地库存不足或尚未配置项目合同单价，请先前往「库存管理」办理入库/调拨，或在项目「合同管理」补充日租金与装卸杂费单价。",
                "第 3 步【新建" + tabLabel + "录入明细】：在「" + tabLabel + "」页面点击右上角「新增" + tabLabel + "」，选择业务日期（计划日期：" + startDate + "），从下拉框选择系统建档材料规格并填入数量（系统将自动带出合同日租金、换算系数及装卸运输杂费）。",
                "第 4 步【保存并复核生效】：填写运输车辆、司机与双方负责人后点击「保存」，再由复核人点击「复核」通过后正式生效并更新库存台账。"
        );

        String pricingSummaryText = pricedCount > 0
                ? String.format(Locale.ROOT, "已匹配合同定价 %d 项（预计每日租金参考 %,.2f 元/天）", pricedCount, estimatedDailyRentTotal)
                : "当前项目尚未配置所选规格的合同日租金单价（预计每日租金与装卸杂费将在「项目合同」补充定价并在建单页面选材后自动计算）";

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】已为您完成项目「%s」%s建单前置核查与操作引导（计划日期 %s）：① 基地库存核查共 %d 项（库存充足 %d 项、库存不足需补货 %d 项）；② 合同单价与杂费核查：%s；③ 工程系统已内置完整的「%s」录单与复核流程，请核对下方准备清单后，直接点击卡片底部按钮跳转至该项目「%s」页面正式开单。",
                projectName, tabLabel, startDate,
                inventoryCheckList.size(), sufficientCount, shortageCount,
                pricingSummaryText, tabLabel, tabLabel);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("draftStatus", "GUIDED_PRECHECK_AND_NAVIGATION");
        result.put("hitlConfirmationRequired", Boolean.TRUE);
        result.put("actionHint", "工程系统已具备完整的「" + tabLabel + "」录入与自动计价能力；小云已为您核对好库存与合同单价底数，请点击下方按钮一键直达「" + tabLabel + "」页面完成正式建单。");
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("targetPageName", "项目详情 -> " + tabLabel);
        result.put("startDate", startDate);
        result.put("enoughCount", sufficientCount);
        result.put("shortageCount", shortageCount);
        result.put("configuredMaterialCount", pricedCount);
        result.put("unconfiguredMaterialCount", unpricedCount);
        result.put("documentTypeLabel", tabLabel);
        result.put("estimatedDailyRent", round2(estimatedDailyRentTotal));
        result.put("operationSteps", operationSteps);
        result.put("inventoryCheckList", inventoryCheckList);
        result.put("contractPricingList", contractPricingList);
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", workflow);
        return result;
    }

    private static String resolveProjectDisplayName(AgentBusinessQueryService s, String cid, String projectId) {
        if (s.projectMapper != null && StringUtils.hasText(cid) && StringUtils.hasText(projectId)) {
            try {
                ProjectEntity entity = s.projectMapper.selectOne(new QueryWrapper<ProjectEntity>()
                        .eq("cid", cid).eq("project_id", projectId));
                if (entity != null && StringUtils.hasText(entity.getProjectName())) {
                    return entity.getProjectName();
                }
            } catch (Exception ignored) {
            }
        }
        if (s.projects != null && StringUtils.hasText(projectId)) {
            try {
                com.zjyz.pojo.param.ret.ProjectDetailInfoRet info = s.projects.queryProjectInfo(projectId);
                if (info != null && StringUtils.hasText(info.getPartnerName())) {
                    return info.getPartnerName();
                }
            } catch (Exception ignored) {
            }
        }
        return projectId;
    }

    private static final class RequestedMaterialSpec {
        final String rawName;
        final String specHint;
        final String unit;
        final double quantity;
        final List<String> tokens;

        RequestedMaterialSpec(String rawName, String specHint, String unit, double quantity, List<String> tokens) {
            this.rawName = rawName;
            this.specHint = specHint;
            this.unit = unit;
            this.quantity = quantity;
            this.tokens = tokens;
        }
    }

    private static List<RequestedMaterialSpec> parseRequestedMaterials(String keywordArg, String originalMessage) {
        List<RequestedMaterialSpec> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (StringUtils.hasText(originalMessage)) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+(?:\\.\\d+)?)\\s*([根个只米吨套块张件榀])\\s*([^\\s，,。；;：:（()）和与及]+(?:\\s*[^\\s，,。；;：:（()）和与及]+)?)")
                    .matcher(originalMessage);
            while (m.find()) {
                double qty = 0d;
                try {
                    qty = Double.parseDouble(m.group(1));
                } catch (Exception ignored) {
                }
                String unit = m.group(2).trim();
                String raw = m.group(3).trim()
                        .replaceAll("^(?:的|出库|入库|租出|归还)", "")
                        .trim();
                if (StringUtils.hasText(raw) && seen.add(raw)) {
                    list.add(buildRequestedSpec(raw, unit, qty));
                }
            }
        }
        if (list.isEmpty() && StringUtils.hasText(keywordArg)) {
            for (String part : keywordArg.split("[,，、;；/和与及]+")) {
                String clean = part.trim();
                if (StringUtils.hasText(clean) && seen.add(clean)) {
                    list.add(buildRequestedSpec(clean, "", 0d));
                }
            }
        }
        return list;
    }

    private static RequestedMaterialSpec buildRequestedSpec(String raw, String unit, double qty) {
        List<String> tokens = new ArrayList<>();
        String specHint = "";
        java.util.regex.Matcher specMatcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?\\s*[*×xX-]\\s*\\d+(?:\\.\\d+)?)")
                .matcher(raw);
        if (specMatcher.find()) {
            specHint = specMatcher.group(1).replaceAll("\\s+", "");
            tokens.add(specHint.toLowerCase(Locale.ROOT));
        }
        String remaining = raw.replace(specHint, " ").trim();
        if (remaining.contains("十字扣件") || remaining.contains("十字扣")) {
            if (!StringUtils.hasText(specHint)) specHint = "十字扣";
            tokens.add("十字扣");
            tokens.add("十字扣件");
            tokens.add("扣件");
        } else if (remaining.contains("转向扣件") || remaining.contains("转向扣")) {
            if (!StringUtils.hasText(specHint)) specHint = "转向扣";
            tokens.add("转向扣");
            tokens.add("扣件");
        } else if (remaining.contains("接头扣件") || remaining.contains("接头扣") || remaining.contains("对接扣")) {
            if (!StringUtils.hasText(specHint)) specHint = "接头扣";
            tokens.add("接头扣");
            tokens.add("扣件");
        }
        for (String p : remaining.split("[\\s/()（）-]+")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(t) && !tokens.contains(t)) {
                tokens.add(t);
            }
        }
        if (tokens.isEmpty()) {
            tokens.add(raw.toLowerCase(Locale.ROOT));
        }
        return new RequestedMaterialSpec(raw, specHint, unit, qty, tokens);
    }

    private static InventoryMaterialItemRet matchBestInventoryItem(RequestedMaterialSpec req,
                                                                   List<InventoryMaterialItemRet> allInventory) {
        if (allInventory == null || allInventory.isEmpty()) return null;
        InventoryMaterialItemRet best = null;
        int bestScore = 0;
        for (InventoryMaterialItemRet item : allInventory) {
            if (item == null) continue;
            String name = safe(item.getMaterialName()).toLowerCase(Locale.ROOT);
            String spec = safe(item.getMaterialSpecification()).toLowerCase(Locale.ROOT);
            String cat = safe(item.getMaterialCategory()).toLowerCase(Locale.ROOT);
            String combined = name + " " + spec + " " + cat;
            int matchedTokens = 0;
            for (String token : req.tokens) {
                if (combined.contains(token)) {
                    matchedTokens++;
                }
            }
            if (matchedTokens == 0) continue;
            int score = matchedTokens * 30;
            if (StringUtils.hasText(req.specHint) && spec.contains(req.specHint.toLowerCase(Locale.ROOT))) {
                score += 60;
            }
            int stock = item.getInventoryQuantity() == null ? 0 : item.getInventoryQuantity();
            int total = item.getTotalQuantity() == null ? 0 : item.getTotalQuantity();
            int rented = item.getRentedQuantity() == null ? 0 : item.getRentedQuantity();
            if (stock > 0) {
                score += 100;
            } else if (total > 0 || rented > 0) {
                score += 40;
            }
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static ProjectMaterialPricingEntity matchBestContractPricing(RequestedMaterialSpec req,
                                                                         InventoryMaterialItemRet bestInv,
                                                                         List<ProjectMaterialPricingEntity> pricings) {
        if (pricings == null || pricings.isEmpty()) return null;
        if (bestInv != null && StringUtils.hasText(bestInv.getMaterialId())) {
            for (ProjectMaterialPricingEntity p : pricings) {
                if (p != null && bestInv.getMaterialId().equals(p.getMid())) {
                    return p;
                }
            }
        }
        ProjectMaterialPricingEntity best = null;
        int bestScore = 0;
        for (ProjectMaterialPricingEntity p : pricings) {
            if (p == null) continue;
            String name = safe(p.getMaterialName()).toLowerCase(Locale.ROOT);
            String spec = safe(p.getMaterialSpecification()).toLowerCase(Locale.ROOT);
            String combined = name + " " + spec;
            int matchedTokens = 0;
            for (String token : req.tokens) {
                if (combined.contains(token)) {
                    matchedTokens++;
                }
            }
            if (matchedTokens == 0) continue;
            int score = matchedTokens * 30;
            if (StringUtils.hasText(req.specHint) && spec.contains(req.specHint.toLowerCase(Locale.ROOT))) {
                score += 60;
            }
            if (p.getDailyRent() != null && p.getDailyRent().doubleValue() > 0d) {
                score += 50;
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    // =========================================================================
    // 1. settlement.preview_draft — 财务结算单草稿试算预览 (Phase 2 HITL)
    // =========================================================================
    private static Map<String, Object> previewSettlementDraft(AgentBusinessQueryService s,
                                                              Map<String, Object> a,
                                                              AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String startDate = str(a, "startDate");
        String endDate = str(a, "endDate");
        if (!StringUtils.hasText(startDate) || !StringUtils.hasText(endDate)) {
            if (s.settlements != null) {
                SettlementPeriodListRet cycles = s.settlements.querySettlementCycleList(projectId);
                if (cycles != null && cycles.getSettlementPeriodList() != null
                        && !cycles.getSettlementPeriodList().isEmpty()) {
                    SettlementPeriodListRet.SettlementPeriod first = cycles.getSettlementPeriodList().get(0);
                    if (!StringUtils.hasText(startDate)) {
                        startDate = first.getStartDate();
                    }
                    if (!StringUtils.hasText(endDate)) {
                        endDate = first.getEndDate();
                    }
                }
            }
            LocalDate today = LocalDate.now();
            if (!StringUtils.hasText(startDate)) {
                startDate = today.withDayOfMonth(1).toString();
            }
            if (!StringUtils.hasText(endDate)) {
                endDate = today.toString();
            }
        }

        SettlementPreviewParam param = new SettlementPreviewParam();
        param.setProjectId(projectId);
        param.setStartDate(startDate);
        param.setEndDate(endDate);
        SettlementPreviewRet preview = s.settlements != null ? s.settlements.previewSettlement(param) : null;
        SettlementPreviewRet.Summary summary = preview != null ? preview.getSummary() : null;
        String projectName = resolveProjectDisplayName(s, w != null ? w.getCid() : null, projectId);

        String docName = preview != null && StringUtils.hasText(preview.getDocumentName())
                ? preview.getDocumentName() : (projectName + " 结算试算草稿");
        double currentRentFee = summary != null ? bd(summary.getCurrentRentFee()) : 0d;
        double incrementRentFee = summary != null ? bd(summary.getIncrementRentFee()) : 0d;
        double compensationFee = summary != null ? bd(summary.getCompensationFee()) : 0d;
        double miscFee = summary != null
                ? bd(summary.getMiscFee()) + bd(summary.getLoadingFee()) + bd(summary.getUnloadingFee())
                + bd(summary.getFreightFee()) + bd(summary.getPackingFee()) + bd(summary.getRepairMiscFee())
                + bd(summary.getExtraFee()) + bd(summary.getRepairFee()) : 0d;
        double stoppageDeduction = summary != null ? bd(summary.getStoppageDeductionAmount()) : 0d;
        double negotiationDiscount = summary != null ? bd(summary.getNegotiationDiscountAmount()) : 0d;
        double finalReceivable = summary != null ? bd(summary.getFinalReceivableAmount()) : 0d;
        double finalPayable = summary != null ? bd(summary.getFinalPayableAmount()) : 0d;
        double periodTotalFee = finalReceivable > 0d ? finalReceivable
                : finalPayable > 0d ? finalPayable
                : Math.max(0d, currentRentFee + incrementRentFee + compensationFee + miscFee - stoppageDeduction - negotiationDiscount);
        int rowCount = preview != null && preview.getRentFeeInfos() != null ? preview.getRentFeeInfos().size() : 0;

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】已试算项目「%s」实际采用的起止日期 %s 至 %s 财务结算单草稿：基础租金 %,.2f 元、递增租金 %,.2f 元、赔偿 %,.2f 元、杂费 %,.2f 元、报停减免 %,.2f 元；应收汇总 %,.2f 元、应付汇总 %,.2f 元（本期应结总额 %,.2f 元），共 %d 项材料租金分段。当前仅预览，不保存正式单据，已提供项目「财务对账」页面入口供复核。",
                docName, startDate, endDate, currentRentFee, incrementRentFee, compensationFee, miscFee, stoppageDeduction, finalReceivable, finalPayable, periodTotalFee, rowCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("draftStatus", "DRAFT_PREVIEW_ONLY");
        result.put("hitlConfirmationRequired", Boolean.TRUE);
        result.put("actionHint", "本结果仅为试算草稿（未写入正式账本），请点击下方按钮打开项目「财务对账」页面复核并保存正式结算单。");
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("documentName", docName);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("periodTotalFee", round2(periodTotalFee));
        result.put("finalReceivableAmount", round2(finalReceivable));
        result.put("finalPayableAmount", round2(finalPayable));
        result.put("periodRentFee", round2(currentRentFee));
        result.put("incrementRentTotal", round2(incrementRentFee));
        result.put("periodCompensationFee", round2(compensationFee));
        result.put("periodIncidentalFee", round2(miscFee));
        result.put("stoppageDeductionAmount", round2(stoppageDeduction));
        result.put("negotiationDiscountAmount", round2(negotiationDiscount));
        result.put("operationSteps", Arrays.asList(
                "第 1 步【直达财务对账页面】：点击本卡片下方「打开项目业务页面（直达财务对账）」按钮，进入该项目「财务对账」模块。",
                "第 2 步【选择结算账期】：点击「新增财务对账单」，选择结算起止日期（" + startDate + " 至 " + endDate + "），系统将自动计算各分段租金与杂费。",
                "第 3 步【核对减免与保存复核】：核对报停减免、赔偿与协商让利金额后点击「保存」，复核通过后正式计入客户应收账本。"
        ));
        result.put("settlementRows", preview != null && preview.getRentFeeInfos() != null
                ? preview.getRentFeeInfos() : Collections.emptyList());
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "financeCheck");
        return result;
    }

    // =========================================================================
    // 2. reconciliation.preview_draft — 物料对账单草稿试算预览 (Phase 2 HITL)
    // =========================================================================
    private static Map<String, Object> previewReconciliationDraft(AgentBusinessQueryService s,
                                                                  Map<String, Object> a,
                                                                  AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        LocalDate today = LocalDate.now();
        String startDate = StringUtils.hasText(str(a, "startDate"))
                ? str(a, "startDate") : today.withDayOfMonth(1).toString();
        String endDate = StringUtils.hasText(str(a, "endDate"))
                ? str(a, "endDate") : today.toString();

        CreateDocumentParam param = new CreateDocumentParam();
        param.setProjectId(projectId);
        param.setType("8");
        param.setStartDate(startDate);
        param.setEndDate(endDate);
        ReconciliationPreviewRet preview = s.reconciliation != null
                ? s.reconciliation.previewReconciliationDocument(param) : null;

        List<Map<String, Object>> previewRows = new ArrayList<>();
        if (preview != null && preview.getSections() != null) {
            for (ReconciliationPreviewRet.Section section : preview.getSections()) {
                if (section == null || section.getRows() == null) {
                    continue;
                }
                for (Map<String, Object> row : section.getRows()) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("sectionTitle", section.getTitle());
                    if (row != null) {
                        copy.putAll(row);
                    }
                    previewRows.add(copy);
                }
            }
        }
        List<String> warnings = preview != null && preview.getWarnings() != null
                ? preview.getWarnings() : Collections.emptyList();
        List<String> validationErrors = preview != null && preview.getValidationErrors() != null
                ? preview.getValidationErrors() : Collections.emptyList();
        String projectName = resolveProjectDisplayName(s, w != null ? w.getCid() : null, projectId);
        String title = preview != null && StringUtils.hasText(preview.getTitle())
                ? preview.getTitle() : (projectName + " 物料对账预览");

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】已生成项目「%s」%s 至 %s 物料对账单试算草稿：共核对 %d 行材料期初/本期租还/期末结存明细，发现 %d 项提示、%d 项阻断校验。当前为试算预览（未正式保存），请点击下方入口前往「物料对账」确认生成正式对账单。",
                title, startDate, endDate, previewRows.size(), warnings.size(), validationErrors.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("draftStatus", "DRAFT_PREVIEW_ONLY");
        result.put("hitlConfirmationRequired", Boolean.TRUE);
        result.put("actionHint", "本结果仅为物料对账试算草稿（未生成正式单据），请点击下方按钮前往「物料对账」页面复核并保存。");
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("title", title);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("materialRowCount", previewRows.size());
        result.put("warningCount", warnings.size());
        result.put("operationSteps", Arrays.asList(
                "第 1 步【直达物料对账页面】：点击本卡片下方「打开项目业务页面（直达物料对账）」按钮，进入该项目「物料对账」模块。",
                "第 2 步【处理未复核单据】：若上方存在校验提示（如期间内有未复核的租出单或归还单），请先完成单据复核。",
                "第 3 步【生成正式对账单】：在「物料对账」页面选择对账区间（" + startDate + " 至 " + endDate + "），核对期初、本期发生与期末在租数量后保存并导出。"
        ));
        result.put("validationWarnings", warnings);
        result.put("validationErrors", validationErrors);
        result.put("previewRows", previewRows);
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "materialReconciliation");
        return result;
    }

    // =========================================================================
    // 3. finance.late_fee_trial — 逾期账款滞纳金试算器 (Phase 2 HITL)
    // =========================================================================
    private static Map<String, Object> trialLateFee(AgentBusinessQueryService s,
                                                    Map<String, Object> a,
                                                    AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String endDate = StringUtils.hasText(str(a, "endDate"))
                ? str(a, "endDate") : LocalDate.now().toString();
        String dailyRateStr = StringUtils.hasText(str(a, "dailyRatePercent"))
                ? str(a, "dailyRatePercent") : "0.05";
        BigDecimal dailyRate;
        try {
            dailyRate = new BigDecimal(dailyRateStr);
        } catch (Exception e) {
            dailyRate = new BigDecimal("0.05");
        }

        LateFeePreviewParam param = new LateFeePreviewParam();
        param.setProjectId(projectId);
        param.setCalculationEndDate(endDate);
        param.setDailyRatePercent(dailyRate);
        LateFeeDocumentRet preview = s.lateFees != null ? s.lateFees.preview(param) : null;

        BigDecimal accrued = preview != null && preview.getCumulativeAssessedAmount() != null
                ? preview.getCumulativeAssessedAmount() : BigDecimal.ZERO;
        BigDecimal paid = preview != null && preview.getCumulativePaidAmount() != null
                ? preview.getCumulativePaidAmount() : BigDecimal.ZERO;
        BigDecimal unpaid = preview != null && preview.getCurrentOutstandingAmount() != null
                ? preview.getCurrentOutstandingAmount() : BigDecimal.ZERO;
        List<LateFeeDocumentRet.Period> periods = preview != null && preview.getPeriods() != null
                ? preview.getPeriods() : Collections.emptyList();
        String projectName = resolveProjectDisplayName(s, w != null ? w.getCid() : null, projectId);
        String docName = preview != null && StringUtils.hasText(preview.getDocumentName())
                ? preview.getDocumentName() : projectName;

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】截至 %s 按日利率 %s%% 试算项目「%s」逾期滞纳金：共命中 %d 个逾期账期，累计应计滞纳金 %,.2f 元，已收 %,.2f 元，当前未结清滞纳金 %,.2f 元（只读试算未入账）。",
                endDate, dailyRate.toPlainString(), docName, periods.size(),
                accrued.doubleValue(), paid.doubleValue(), unpaid.doubleValue());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("draftStatus", "TRIAL_CALCULATION_ONLY");
        result.put("hitlConfirmationRequired", Boolean.TRUE);
        result.put("actionHint", "滞纳金试算不会自动写入客户账单；如需正式登记滞纳金单，请前往「财务对账」页面确认。");
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("documentName", docName);
        result.put("endDate", endDate);
        result.put("dailyRatePercent", dailyRate);
        result.put("overduePeriodCount", periods.size());
        result.put("accumulatedAccruedAmount", accrued);
        result.put("accumulatedPaidAmount", paid);
        result.put("currentUnpaidAmount", unpaid);
        result.put("periods", periods);
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "financeCheck");
        return result;
    }

    // =========================================================================
    // 4. governance.material_alias_audit — 材料主数据与旧系统迁移治理体检 (Phase 2)
    // =========================================================================
    private static Map<String, Object> auditMaterialGovernance(AgentBusinessQueryService s,
                                                               Map<String, Object> a) {
        String keyword = str(a, "keyword");
        String status = str(a, "status");
        QueryMaterialGovernanceParam gp = new QueryMaterialGovernanceParam();
        gp.setKeyword(keyword);
        gp.setStatus(status);
        gp.setPageNum(1);
        gp.setPageSize(100);

        MaterialGovernanceRet<MaterialGovernanceRet.BatchItem> batchRet = s.materialGovernance != null
                ? s.materialGovernance.queryBatches(gp) : null;
        MaterialGovernanceRet<MaterialGovernanceRet.MigrationItem> itemRet = s.materialGovernance != null
                ? s.materialGovernance.queryMigrationItems(gp) : null;
        MaterialGovernanceRet<MaterialGovernanceRet.AliasItem> aliasRet = s.materialGovernance != null
                ? s.materialGovernance.queryAliases(gp) : null;

        List<MaterialGovernanceRet.BatchItem> batches = batchRet != null && batchRet.getList() != null
                ? batchRet.getList() : Collections.emptyList();
        List<MaterialGovernanceRet.MigrationItem> items = itemRet != null && itemRet.getList() != null
                ? itemRet.getList() : Collections.emptyList();
        List<MaterialGovernanceRet.AliasItem> aliases = aliasRet != null && aliasRet.getList() != null
                ? aliasRet.getList() : Collections.emptyList();

        long pendingBatchCount = batches.stream()
                .filter(b -> b != null && !"CONFIRMED".equalsIgnoreCase(b.getStatus()))
                .count();
        long pendingItemCount = items.stream()
                .filter(i -> i != null && ("PENDING".equalsIgnoreCase(i.getMappingStatus())
                        || !StringUtils.hasText(i.getConfirmedMid())))
                .count();

        List<Map<String, Object>> masterAnomalies = new ArrayList<>();
        int missingUnitCount = 0;
        int duplicateSpecCount = 0;
        if (s.materials != null) {
            QueryMaterialInfoParam mp = new QueryMaterialInfoParam();
            mp.setPageNum(1);
            mp.setPageSize(200);
            mp.setKeyword(keyword);
            MaterialInfoRet masterRet = s.materials.queryMaterialInfo(mp);
            List<MaterialInfoRet.MaterialInfo> list = masterRet != null && masterRet.getMaterialInfos() != null
                    ? masterRet.getMaterialInfos() : Collections.emptyList();
            Map<String, List<MaterialInfoRet.MaterialInfo>> byNameSpec = new LinkedHashMap<>();
            for (MaterialInfoRet.MaterialInfo m : list) {
                if (m == null) {
                    continue;
                }
                boolean noCounting = !StringUtils.hasText(m.getCountingUnit());
                boolean noPricing = !StringUtils.hasText(m.getRentalUnit());
                if (noCounting || noPricing) {
                    missingUnitCount++;
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("issueType", "MISSING_UNIT");
                    issue.put("mid", m.getMid());
                    issue.put("materialName", m.getMaterialName());
                    issue.put("materialSpecification", m.getMaterialSpecification());
                    issue.put("detail", (noCounting ? "缺少计数单位 " : "") + (noPricing ? "缺少计价单位" : ""));
                    masterAnomalies.add(issue);
                }
                String key = safe(m.getMaterialName()) + "|" + safe(m.getMaterialSpecification());
                byNameSpec.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            for (Map.Entry<String, List<MaterialInfoRet.MaterialInfo>> entry : byNameSpec.entrySet()) {
                if (entry.getValue().size() > 1) {
                    duplicateSpecCount++;
                    MaterialInfoRet.MaterialInfo sample = entry.getValue().get(0);
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("issueType", "DUPLICATE_NAME_SPEC");
                    issue.put("mid", sample.getMid());
                    issue.put("materialName", sample.getMaterialName());
                    issue.put("materialSpecification", sample.getMaterialSpecification());
                    issue.put("detail", "同名同规格重复录入 " + entry.getValue().size() + " 条，建议合并为一物多别名");
                    masterAnomalies.add(issue);
                }
            }
        }

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】企业材料主数据与迁移体检完成：待确认迁移批次 %d 个、待映射旧系统材料 %d 条、已沉淀标准别名 %d 条；主数据缺失单位 %d 项、同名同规格重复 %d 组。",
                pendingBatchCount, pendingItemCount, aliases.size(), missingUnitCount, duplicateSpecCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("actionHint", "请点击「打开业务功能」进入「企业材料库」确认待映射条目、补全双单位或合并重复规格。");
        result.put("pendingBatchCount", pendingBatchCount);
        result.put("pendingItemCount", pendingItemCount);
        result.put("aliasCount", aliases.size());
        result.put("missingUnitCount", missingUnitCount);
        result.put("duplicateSpecCount", duplicateSpecCount);
        result.put("masterAnomalies", masterAnomalies);
        result.put("migrationItems", items.size() > 20 ? items.subList(0, 20) : items);
        result.put("aliases", aliases.size() > 20 ? aliases.subList(0, 20) : aliases);
        result.put("_navigationTarget", "MATERIALS");
        return result;
    }

    // =========================================================================
    // 5. copilot.order_fulfillment_eval — 接单综合评估与商城寻源闭环 (Phase 3A)
    // =========================================================================
    private static Map<String, Object> evaluateOrderFulfillment(AgentBusinessQueryService s,
                                                                Map<String, Object> a,
                                                                String originalMessage,
                                                                AgentRuntimeRecords.Workspace w) {
        String estimateId = str(a, "estimateId");
        String keyword = str(a, "keyword");
        double requiredQty = a.get("requiredQty") instanceof Number
                ? ((Number) a.get("requiredQty")).doubleValue() : 100d;

        if (StringUtils.hasText(estimateId) && s.estimates != null) {
            MaterialEstimateMatchRet match = s.estimates.queryEstimateMatch(estimateId);
            int enough = match != null && match.getEnoughCount() != null ? match.getEnoughCount() : 0;
            int shortage = match != null && match.getShortageCount() != null ? match.getShortageCount() : 0;
            int totalItems = enough + shortage;
            double fulfillmentRate = totalItems > 0 ? round2((enough * 100.0) / totalItems) : 100.0;
            BigDecimal totalGap = match != null && match.getTotalGapQty() != null ? match.getTotalGapQty() : BigDecimal.ZERO;

            List<MarketRecommendRet.ShortageItem> marketRecs = Collections.emptyList();
            if (shortage > 0 && s.market != null) {
                RecommendForEstimateParam rp = new RecommendForEstimateParam();
                rp.setEstimateId(estimateId);
                rp.setTopN(3);
                MarketRecommendRet rec = s.market.recommendForEstimate(rp);
                if (rec != null && rec.getShortageItems() != null) {
                    marketRecs = rec.getShortageItems();
                }
            }
            String verdict = shortage == 0 ? "CAN_ACCEPT_DIRECTLY"
                    : fulfillmentRate >= 60.0 ? "ACCEPT_WITH_MARKET_SOURCING" : "HIGH_SHORTAGE_CAUTION";
            String conclusion = String.format(Locale.ROOT,
                    "【30秒结论】预估单「%s」接单评估：共 %d 项材料需求，本企业现货充足 %d 项、缺口 %d 项（品种满足率 %.1f%%，总缺口量 %s），已自动联动租赁商城匹配 %d 项外部补货推荐。",
                    estimateId, totalItems, enough, shortage, fulfillmentRate, totalGap.toPlainString(), marketRecs.size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conclusion30s", conclusion);
            result.put("decisionVerdict", verdict);
            result.put("estimateId", estimateId);
            result.put("fulfillmentRate", fulfillmentRate);
            result.put("enoughCount", enough);
            result.put("shortageCount", shortage);
            result.put("totalGapQty", totalGap);
            result.put("sufficientItems", match != null ? match.getSufficientItems() : Collections.emptyList());
            result.put("shortageItems", match != null ? match.getShortageItems() : Collections.emptyList());
            result.put("marketRecommendations", marketRecs);
            result.put("actionHint", shortage > 0
                    ? "存在库存缺口材料，可点击下方按钮打开「租赁商城」联系推荐站点补货或外租调入。"
                    : "本企业自有现货可100%满足该预估单需求，建议直接接单排期发料。");
            result.put("_navigationTarget", "MARKET");
            return result;
        }

        List<RequestedMaterialSpec> requestedSpecs = parseRequestedMaterials(keyword, originalMessage);
        boolean hasExplicitMultiOrQuantifiedSpecs = requestedSpecs.size() > 1
                || (requestedSpecs.size() == 1 && requestedSpecs.get(0).quantity > 0d);
        if (hasExplicitMultiOrQuantifiedSpecs) {
            List<InventoryMaterialItemRet> allInventory = loadAllInventoryMaterials(s, null, 500);
            List<Map<String, Object>> sufficient = new ArrayList<>();
            List<Map<String, Object>> shortages = new ArrayList<>();
            List<Map<String, Object>> marketMatches = new ArrayList<>();
            Map<String, List<MarketMaterialListRet.MarketMaterialItem>> marketCache = new LinkedHashMap<>();

            for (RequestedMaterialSpec req : requestedSpecs) {
                double itemReqQty = req.quantity > 0d ? req.quantity : requiredQty;
                InventoryMaterialItemRet bestInv = matchBestInventoryItem(req, allInventory);
                double avail = bestInv != null && bestInv.getInventoryQuantity() != null
                        ? bestInv.getInventoryQuantity().doubleValue() : 0d;
                String matName = bestInv != null && StringUtils.hasText(bestInv.getMaterialName())
                        ? bestInv.getMaterialName() : req.rawName;
                String matSpec = bestInv != null && StringUtils.hasText(bestInv.getMaterialSpecification())
                        ? bestInv.getMaterialSpecification() : req.specHint;
                String unit = bestInv != null && StringUtils.hasText(bestInv.getInventoryUnit())
                        ? bestInv.getInventoryUnit() : req.unit;

                Map<String, Object> row = new LinkedHashMap<>();
                if (bestInv != null && StringUtils.hasText(bestInv.getMaterialId())) {
                    row.put("materialId", bestInv.getMaterialId());
                }
                row.put("materialName", matName);
                row.put("materialSpecification", matSpec);
                row.put("countingUnit", unit);
                row.put("requiredQty", round2(itemReqQty));
                row.put("availableQty", round2(avail));

                if (avail >= itemReqQty) {
                    row.put("gapQty", 0d);
                    sufficient.add(row);
                } else {
                    double gap = round2(itemReqQty - Math.max(0d, avail));
                    row.put("gapQty", gap);
                    shortages.add(row);
                    if (s.market != null && marketMatches.size() < 6 && StringUtils.hasText(matName)) {
                        List<MarketMaterialListRet.MarketMaterialItem> cachedList = marketCache.computeIfAbsent(
                                matName, keyName -> {
                                    QueryMarketMaterialListParam mp = new QueryMarketMaterialListParam();
                                    mp.setKeyword(keyName);
                                    mp.setPageNum(1);
                                    mp.setPageSize(3);
                                    MarketMaterialListRet mRet = s.market.queryMaterialList(mp);
                                    return mRet != null && mRet.getList() != null ? mRet.getList() : Collections.emptyList();
                                });
                        for (MarketMaterialListRet.MarketMaterialItem mi : cachedList) {
                            if (marketMatches.size() >= 6) break;
                            Map<String, Object> mRow = new LinkedHashMap<>();
                            mRow.put("materialName", mi.getMaterialName());
                            mRow.put("materialSpecification", mi.getMaterialSpecification());
                            mRow.put("stationName", mi.getStationName());
                            mRow.put("city", mi.getCity());
                            mRow.put("availableQty", mi.getAvailableQty());
                            mRow.put("marketPrice", mi.getMarketPrice());
                            mRow.put("marketPriceUnit", mi.getMarketPriceUnit());
                            marketMatches.add(mRow);
                        }
                    }
                }
            }

            int total = sufficient.size() + shortages.size();
            double rate = total > 0 ? round2((sufficient.size() * 100.0) / total) : 0.0;
            String verdict = total == 0 ? "NO_MATCHING_MATERIAL"
                    : shortages.isEmpty() ? "CAN_ACCEPT_DIRECTLY"
                    : rate >= 50.0 ? "ACCEPT_WITH_MARKET_SOURCING" : "HIGH_SHORTAGE_CAUTION";
            String specSummary = requestedSpecs.stream()
                    .map(r -> (r.quantity > 0d ? ((int) r.quantity + safe(r.unit) + " ") : "") + r.rawName)
                    .collect(Collectors.joining("、"));
            String conclusion = String.format(Locale.ROOT,
                    "【30秒结论】针对本次要货清单（%s）完成接单综合评估：共核查 %d 项材料，本企业自有现货充足 %d 项、存在缺口 %d 项（品种满足率 %.1f%%），已从公开租赁商城匹配 %d 条同行可供货源。",
                    specSummary, total, sufficient.size(), shortages.size(), rate, marketMatches.size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conclusion30s", conclusion);
            result.put("decisionVerdict", verdict);
            result.put("fulfillmentRate", rate);
            result.put("enoughCount", sufficient.size());
            result.put("shortageCount", shortages.size());
            result.put("scanTruncated", false);
            result.put("scannedCount", total);
            result.put("totalCount", total);
            result.put("sufficientItems", sufficient);
            result.put("shortageItems", shortages);
            result.put("marketRecommendations", marketMatches);
            result.put("actionHint", shortages.isEmpty()
                    ? "本企业自有现货可100%满足本次要货需求，建议立即接单排期发料。"
                    : "部分材料存在库存缺口，建议先行发出自有充足材料，并点击下方按钮打开「租赁商城」联系同行补齐缺口。");
            result.put("_navigationTarget", "MARKET");
            if (!shortages.isEmpty()) {
                Object firstShortageName = shortages.get(0).get("materialName");
                if (firstShortageName instanceof String && StringUtils.hasText((String) firstShortageName)) {
                    result.put("_defaultKeyword", firstShortageName);
                }
            }
            return result;
        }

        QueryInventoryMaterialsParam ip = new QueryInventoryMaterialsParam();
        ip.setPageNum(1);
        ip.setPageSize(50);
        ip.setKeyword(keyword);
        InventoryMaterialListRet invRet = s.inventory != null ? s.inventory.queryInventoryMaterials(ip) : null;
        List<InventoryMaterialItemRet> invItems = invRet != null && invRet.getList() != null
                ? invRet.getList() : Collections.emptyList();

        List<Map<String, Object>> sufficient = new ArrayList<>();
        List<Map<String, Object>> shortages = new ArrayList<>();
        List<Map<String, Object>> marketMatches = new ArrayList<>();
        Map<String, List<MarketMaterialListRet.MarketMaterialItem>> marketCache = new LinkedHashMap<>();

        for (InventoryMaterialItemRet item : invItems) {
            double avail = item.getInventoryQuantity() == null ? 0d : item.getInventoryQuantity().doubleValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", item.getMaterialId());
            row.put("materialName", item.getMaterialName());
            row.put("materialSpecification", item.getMaterialSpecification());
            row.put("countingUnit", item.getInventoryUnit());
            row.put("requiredQty", requiredQty);
            row.put("availableQty", avail);
            if (avail >= requiredQty) {
                row.put("gapQty", 0d);
                sufficient.add(row);
            } else {
                double gap = round2(requiredQty - Math.max(0d, avail));
                row.put("gapQty", gap);
                shortages.add(row);
                if (s.market != null && marketMatches.size() < 5 && StringUtils.hasText(item.getMaterialName())) {
                    List<MarketMaterialListRet.MarketMaterialItem> cachedList = marketCache.computeIfAbsent(
                            item.getMaterialName(), matName -> {
                                QueryMarketMaterialListParam mp = new QueryMarketMaterialListParam();
                                mp.setKeyword(matName);
                                mp.setPageNum(1);
                                mp.setPageSize(3);
                                MarketMaterialListRet mRet = s.market.queryMaterialList(mp);
                                return mRet != null && mRet.getList() != null ? mRet.getList() : Collections.emptyList();
                            });
                    for (MarketMaterialListRet.MarketMaterialItem mi : cachedList) {
                        if (marketMatches.size() >= 5) {
                            break;
                        }
                        Map<String, Object> mRow = new LinkedHashMap<>();
                        mRow.put("materialName", mi.getMaterialName());
                        mRow.put("materialSpecification", mi.getMaterialSpecification());
                        mRow.put("stationName", mi.getStationName());
                        mRow.put("city", mi.getCity());
                        mRow.put("availableQty", mi.getAvailableQty());
                        mRow.put("marketPrice", mi.getMarketPrice());
                        mRow.put("marketPriceUnit", mi.getMarketPriceUnit());
                        marketMatches.add(mRow);
                    }
                }
            }
        }
        int total = sufficient.size() + shortages.size();
        int totalMatchedCount = invRet != null && invRet.getTotalNum() != null ? invRet.getTotalNum().intValue() : total;
        boolean scanTruncated = totalMatchedCount > invItems.size();
        double rate = total > 0 ? round2((sufficient.size() * 100.0) / total) : 0.0;
        String verdict = total == 0 ? "NO_MATCHING_MATERIAL"
                : shortages.isEmpty() ? "CAN_ACCEPT_DIRECTLY"
                : rate >= 60.0 ? "ACCEPT_WITH_MARKET_SOURCING" : "HIGH_SHORTAGE_CAUTION";

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】按需求量 %.0f 评估%s库存满足率：共核查 %d 项材料，自有现货充足 %d 项、存在缺口 %d 项（满足率 %.1f%%），已从公开商城检索到 %d 条同行可供货源。%s",
                requiredQty, StringUtils.hasText(keyword) ? "「" + keyword + "」" : "企业",
                total, sufficient.size(), shortages.size(), rate, marketMatches.size(),
                scanTruncated ? String.format(Locale.ROOT, "（注：匹配材料共 %d 项，本次仅抽样前 %d 项，建议传入 keyword 精准评估）", totalMatchedCount, invItems.size()) : "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("decisionVerdict", verdict);
        result.put("fulfillmentRate", rate);
        result.put("enoughCount", sufficient.size());
        result.put("shortageCount", shortages.size());
        result.put("scanTruncated", scanTruncated);
        result.put("scannedCount", invItems.size());
        result.put("totalCount", totalMatchedCount);
        result.put("sufficientItems", sufficient);
        result.put("shortageItems", shortages);
        result.put("marketRecommendations", marketMatches);
        result.put("actionHint", "可点击下方按钮打开「租赁商城」查看同行报价与联系方式。");
        result.put("_navigationTarget", "MARKET");
        if (!StringUtils.hasText(keyword) && !shortages.isEmpty()) {
            Object firstShortageName = shortages.get(0).get("materialName");
            if (firstShortageName instanceof String && StringUtils.hasText((String) firstShortageName)) {
                result.put("_defaultKeyword", firstShortageName);
            }
        }
        return result;
    }

    // =========================================================================
    // 6. copilot.contract_execution_audit — 合同配置与执行双层合规审查 (Phase 3A)
    // =========================================================================
    private static Map<String, Object> auditContractExecution(AgentBusinessQueryService s,
                                                              Map<String, Object> a,
                                                              AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String cid = w.getCid();
        ContractInfoRet contract = s.contracts != null ? s.contracts.queryContract(projectId) : null;

        List<ProjectMaterialPricingEntity> pricings = s.projectMaterialPricingMapper != null
                ? s.projectMaterialPricingMapper.selectList(new QueryWrapper<ProjectMaterialPricingEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        List<RentDocumentEntity> rentDocs = s.rentDocumentMapper != null
                ? s.rentDocumentMapper.selectList(new QueryWrapper<RentDocumentEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        List<ReturnDocumentEntity> returnDocs = s.returnDocumentMapper != null
                ? s.returnDocumentMapper.selectList(new QueryWrapper<ReturnDocumentEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        List<CompensationDocumentEntity> compDocs = s.compensationDocumentMapper != null
                ? s.compensationDocumentMapper.selectList(new QueryWrapper<CompensationDocumentEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        List<Map<String, Object>> findings = new ArrayList<>();

        if (contract == null || !StringUtils.hasText(contract.getContractId())) {
            findings.add(finding("MISSING_CONTRACT", "HIGH", "项目尚未录入正式合同",
                    "请尽快在项目合同页补录合同起止日、结算周期与税率条款"));
        } else {
            List<String> missingFields = new ArrayList<>();
            if (!StringUtils.hasText(contract.getStartDate())) missingFields.add("合同开始日");
            if (!StringUtils.hasText(contract.getEndDate())) missingFields.add("合同结束日");
            if (contract.getSettlementCycle() == null) missingFields.add("对账结算周期");
            if (contract.getTaxRate() == null) missingFields.add("税率");
            if (!missingFields.isEmpty()) {
                findings.add(finding("MISSING_CONTRACT_TERMS", "MEDIUM",
                        "合同关键条款缺失：" + String.join("、", missingFields),
                        "补全合同结算周期、起止日期及税率，避免自动对账试算受阻"));
            }
        }

        Set<String> pricedMids = new LinkedHashSet<>();
        Set<String> pricedNames = new LinkedHashSet<>();
        int zeroPriceCount = 0;
        for (ProjectMaterialPricingEntity p : pricings) {
            if (StringUtils.hasText(p.getMid())) {
                pricedMids.add(p.getMid());
            }
            if (StringUtils.hasText(p.getMaterialName())) {
                pricedNames.add(p.getMaterialName().trim());
            }
            double up = p.getDailyRent() != null ? p.getDailyRent().doubleValue()
                    : p.getSaleUnitPrice() != null ? p.getSaleUnitPrice().doubleValue() : 0d;
            if (up <= 0d || "PENDING".equalsIgnoreCase(p.getResolutionStatus())) {
                zeroPriceCount++;
                findings.add(finding("ZERO_OR_PENDING_MATERIAL_PRICE", "HIGH",
                        "合同材料「" + safe(p.getMaterialName()) + "」日租金为0或处于待映射状态",
                        "请在项目材料页核定日租金单价并完成标准材料映射"));
            }
        }

        int unpricedDeliveryCount = 0;
        int afterExpiryDeliveryCount = 0;
        int unreviewedDocCount = 0;
        String contractEnd = contract != null ? contract.getEndDate() : null;

        List<String> rentDocIds = new ArrayList<>();
        for (RentDocumentEntity rd : rentDocs) {
            rentDocIds.add(rd.getRentDocumentId());
            if (!Integer.valueOf(1).equals(rd.getReviewStatus())) {
                unreviewedDocCount++;
            }
            if (StringUtils.hasText(contractEnd) && StringUtils.hasText(rd.getRentDate())
                    && rd.getRentDate().compareTo(contractEnd) > 0) {
                afterExpiryDeliveryCount++;
                findings.add(finding("DELIVERY_AFTER_CONTRACT_END", "HIGH",
                        "租出单「" + safe(rd.getRentDocumentName()) + "」业务日期(" + rd.getRentDate() + ")晚于合同到期日(" + contractEnd + ")",
                        "请确认是否已签署补充延期协议并更新合同截止日期"));
            }
        }
        for (ReturnDocumentEntity retDoc : returnDocs) {
            if (!Integer.valueOf(1).equals(retDoc.getReviewStatus())) {
                unreviewedDocCount++;
            }
        }
        for (CompensationDocumentEntity cd : compDocs) {
            if (!Integer.valueOf(1).equals(cd.getReviewStatus())) {
                unreviewedDocCount++;
            }
        }

        if (!rentDocIds.isEmpty() && s.rentDocumentMaterialMapper != null) {
            List<RentDocumentMaterialEntity> deliveredItems = s.rentDocumentMaterialMapper.selectList(
                    new QueryWrapper<RentDocumentMaterialEntity>()
                            .eq("cid", cid).in("document_id", rentDocIds));
            Set<String> reportedUnpriced = new LinkedHashSet<>();
            for (RentDocumentMaterialEntity dm : deliveredItems) {
                String mName = safe(dm.getMaterialName());
                boolean priced = (StringUtils.hasText(dm.getMaterialId()) && pricedMids.contains(dm.getMaterialId()))
                        || (StringUtils.hasText(mName) && pricedNames.contains(mName));
                if (!priced && reportedUnpriced.add(mName + "|" + safe(dm.getMaterialSpecification()))) {
                    unpricedDeliveryCount++;
                    findings.add(finding("UNPRICED_MATERIAL_DELIVERY", "HIGH",
                            "已发料材料「" + mName + " " + safe(dm.getMaterialSpecification()) + "」未在项目合同材料中配置单价",
                            "请立即在「项目材料」中补录该材料合同单价，否则财务结算将漏算租金"));
                }
            }
        }

        if (unreviewedDocCount > 0) {
            findings.add(finding("UNREVIEWED_DOCUMENT_BACKLOG", "MEDIUM",
                    "当前项目存在 " + unreviewedDocCount + " 张已保存但未复核的业务单据",
                    "建议在对账结算前完成全部租出、归还、赔偿单据复核"));
        }

        String complianceLevel = findings.stream().anyMatch(f -> "HIGH".equals(f.get("severity")))
                ? "HIGH_RISK" : findings.isEmpty() ? "PASS" : "WARNING";
        String projectName = resolveProjectDisplayName(s, cid, projectId);

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】项目「%s」合同配置与实际执行双层合规审查完成：整体评级 %s，共发现 %d 项合规风险（无合同定价发料 %d 项、零单价配置 %d 项、超合同期发料 %d 单、未复核单据 %d 张）。",
                projectName, "HIGH_RISK".equals(complianceLevel) ? "高风险待整改" : "WARNING".equals(complianceLevel) ? "存在预警" : "合规通过",
                findings.size(), unpricedDeliveryCount, zeroPriceCount, afterExpiryDeliveryCount, unreviewedDocCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("complianceLevel", complianceLevel);
        result.put("findingCount", findings.size());
        result.put("configuredMaterialCount", pricings.size());
        result.put("zeroPriceCount", zeroPriceCount);
        result.put("unpricedDeliveryCount", unpricedDeliveryCount);
        result.put("afterExpiryDeliveryCount", afterExpiryDeliveryCount);
        result.put("unreviewedDocCount", unreviewedDocCount);
        result.put("findings", findings);
        result.put("actionHint", "请点击下方按钮打开项目「合同管理」或「项目材料」页闭环整改上述风险项。");
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "contract");
        return result;
    }

    private static Map<String, Object> finding(String code, String severity, String detail, String suggestion) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("findingCode", code);
        map.put("severity", severity);
        map.put("detail", detail);
        map.put("suggestion", suggestion);
        return map;
    }

    // =========================================================================
    // 7. copilot.price_breakeven_sandbox — 项目报价让利与免租期盈亏平衡沙盘 (Phase 3A)
    // =========================================================================
    private static Map<String, Object> runPriceBreakevenSandbox(AgentBusinessQueryService s,
                                                                Map<String, Object> a,
                                                                String originalMessage,
                                                                AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String cid = w.getCid();
        String projectName = resolveProjectDisplayName(s, cid, projectId);
        int leaseDays = a.get("leaseDays") instanceof Number && ((Number) a.get("leaseDays")).intValue() > 0
                ? ((Number) a.get("leaseDays")).intValue() : 90;
        Double customDiscount = a.get("discountPercent") instanceof Number
                ? ((Number) a.get("discountPercent")).doubleValue() : null;
        Integer customFreeDays = a.get("freeRentDays") instanceof Number
                ? ((Number) a.get("freeRentDays")).intValue() : null;

        List<ProjectMaterialPricingEntity> pricings = s.projectMaterialPricingMapper != null
                ? s.projectMaterialPricingMapper.selectList(new QueryWrapper<ProjectMaterialPricingEntity>()
                        .eq("cid", cid).eq("project_id", projectId))
                : Collections.emptyList();

        double baselineDailyRent = 0d;
        int pricedMaterialCount = 0;
        for (ProjectMaterialPricingEntity p : pricings) {
            double unitPrice = p.getDailyRent() != null ? p.getDailyRent().doubleValue()
                    : p.getSaleUnitPrice() != null ? p.getSaleUnitPrice().doubleValue() : 0d;
            if (unitPrice <= 0d) {
                continue;
            }
            pricedMaterialCount++;
            double qty = p.getPredictRentNumber() != null && p.getPredictRentNumber() > 0
                    ? p.getPredictRentNumber().doubleValue() : 100d;
            baselineDailyRent += unitPrice * qty;
        }
        if (baselineDailyRent <= 0d) {
            baselineDailyRent = 1000d;
        }
        baselineDailyRent = round2(baselineDailyRent);
        double baselineTotalRent = round2(baselineDailyRent * leaseDays);

        String basisNote = pricedMaterialCount > 0
                ? String.format(Locale.ROOT,
                        "按该项目合同配价表已配置的 %d 项材料单价与预计租赁规模核算日租金基数（%,.2f 元/天）；如当前尚未发生已复核租出单据或实物在租余额为 0，仍以该合同预计配置作为沙盘推演基线",
                        pricedMaterialCount, baselineDailyRent)
                : String.format(Locale.ROOT, "按默认基准日租金 %,.2f 元/天核算沙盘推演基线", baselineDailyRent);

        String msg = safe(originalMessage);
        boolean explicitlyThreeTiers = (msg.contains("三档") || msg.contains("3档")
                || (msg.contains("95折") && msg.contains("90折"))) && !msg.contains("85折");

        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(buildScenario("基准方案（原价无免租）", 100.0, 0, leaseDays, baselineDailyRent, baselineTotalRent));
        scenarios.add(buildScenario("常规让利（95折 + 3天免租）", 95.0, 3, leaseDays, baselineDailyRent, baselineTotalRent));
        scenarios.add(buildScenario("深度让利（90折 + 7天免租）", 90.0, 7, leaseDays, baselineDailyRent, baselineTotalRent));
        if (!explicitlyThreeTiers) {
            scenarios.add(buildScenario("战略大客户（85折 + 10天免租）", 85.0, 10, leaseDays, baselineDailyRent, baselineTotalRent));
        }
        if (customDiscount != null || customFreeDays != null) {
            double disc = customDiscount != null && customDiscount > 0 ? customDiscount : 92.0;
            int free = customFreeDays != null && customFreeDays >= 0 ? customFreeDays : 5;
            boolean alreadyIncluded = scenarios.stream().anyMatch(sc ->
                    Math.abs(num(sc.get("discountPercent")) - disc) < 0.01d
                            && numInt(sc.get("freeRentDays")) == free);
            if (!alreadyIncluded) {
                scenarios.add(buildScenario(
                        String.format(Locale.ROOT, "自定义方案（%.1f折 + %d天免租）", disc, free),
                        disc, free, leaseDays, baselineDailyRent, baselineTotalRent));
            }
        }

        List<String> tierSummaries = new ArrayList<>();
        for (int i = 1; i < scenarios.size(); i++) {
            Map<String, Object> sc = scenarios.get(i);
            tierSummaries.add(String.format(Locale.ROOT,
                    "%s预计总租金 %,.2f 元（让利 %,.2f 元，需延长 %d 天至 %d 天补回差额）",
                    sc.get("scenarioName"),
                    num(sc.get("scenarioTotalRent")),
                    num(sc.get("concessionAmount")),
                    numInt(sc.get("extraDaysToRecoverConcession")),
                    numInt(sc.get("breakevenTotalLeaseDays"))));
        }
        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】项目「%s」按当前日租金基数 %,.2f 元/天、基准租期 %d 天（原价方案预计总租金 %,.2f 元，让利 0.00 元）推演：%s。",
                projectName, baselineDailyRent, leaseDays, baselineTotalRent,
                String.join("；", tierSummaries));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("pricedMaterialCount", pricedMaterialCount);
        result.put("basisNote", basisNote);
        result.put("baselineDailyRent", baselineDailyRent);
        result.put("leaseDays", leaseDays);
        result.put("baselineTotalRent", baselineTotalRent);
        result.put("scenarios", scenarios);
        result.put("actionHint", "商务谈判让利时建议在合同中绑定对应档位的「最低起租天数」（如 95折+3天免租绑定 98 天、90折+7天免租绑定 107 天）或设置「提前退租阶梯补差条款」，可点击下方按钮打开「项目材料」调整定价。");
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "projectMaterial");
        return result;
    }

    private static Map<String, Object> buildScenario(String name,
                                                     double discountPercent,
                                                     int freeRentDays,
                                                     int leaseDays,
                                                     double baselineDailyRent,
                                                     double baselineTotalRent) {
        int billingDays = Math.max(0, leaseDays - freeRentDays);
        double discountedDaily = round2(baselineDailyRent * (discountPercent / 100.0));
        double totalRent = round2(discountedDaily * billingDays);
        double concession = round2(Math.max(0d, baselineTotalRent - totalRent));
        int extraDays = discountedDaily > 0d ? (int) Math.ceil(concession / discountedDaily) : 0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenarioName", name);
        row.put("discountPercent", discountPercent);
        row.put("freeRentDays", freeRentDays);
        row.put("effectiveBillingDays", billingDays);
        row.put("discountedDailyRent", discountedDaily);
        row.put("scenarioTotalRent", totalRent);
        row.put("concessionAmount", concession);
        row.put("extraDaysToRecoverConcession", extraDays);
        row.put("breakevenTotalLeaseDays", leaseDays + extraDays);
        return row;
    }

    // =========================================================================
    // 8. copilot.settlement_dispute_attribution — 结算金额变动五因子归因解释 (Phase 3A)
    // =========================================================================
    private static Map<String, Object> attributeSettlementDispute(AgentBusinessQueryService s,
                                                                  Map<String, Object> a,
                                                                  AgentRuntimeRecords.Workspace w) {
        String projectId = s.project(a, w);
        String cid = w.getCid();
        String projectName = resolveProjectDisplayName(s, cid, projectId);
        List<SettlementDocumentEntity> docs = s.settlementMapper != null
                ? s.settlementMapper.selectList(new QueryWrapper<SettlementDocumentEntity>()
                        .eq("cid", cid).eq("project_id", projectId)
                        .orderByDesc("end_date", "update_time"))
                : Collections.emptyList();

        SettlementDocumentEntity current = findSettlement(docs, str(a, "currentSettlementId"), 0);
        SettlementDocumentEntity previous = findSettlement(docs, str(a, "previousSettlementId"), 1);

        if (current == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("conclusion30s", "【30秒结论】当前项目尚无已生成的正式财务结算单，暂无法进行跨期金额变动五因子归因；您可以先运行「财务结算单草稿试算预览」。");
            empty.put("projectId", projectId);
            empty.put("projectName", projectName);
            empty.put("factorBreakdown", Collections.emptyList());
            empty.put("_navigationTarget", "PROJECT_WORKFLOW");
            empty.put("_workflow", "financeCheck");
            return empty;
        }

        SettlementDocumentInfoRet curInfo = s.settlements != null
                ? s.settlements.querySettlementDocumentInfo(current.getSettlementDocumentId()) : null;
        SettlementDocumentInfoRet prevInfo = previous != null && s.settlements != null
                ? s.settlements.querySettlementDocumentInfo(previous.getSettlementDocumentId()) : null;

        SettlementPreviewRet.Summary curSum = curInfo != null ? curInfo.getSummary() : null;
        SettlementPreviewRet.Summary prevSum = prevInfo != null ? prevInfo.getSummary() : null;

        double curBase = curSum != null ? bd(curSum.getCurrentRentFee()) : dbl(curInfo != null ? curInfo.getThisRentFee() : 0d);
        double curInc = curSum != null ? bd(curSum.getIncrementRentFee()) : bd(current.getIncrementRentAmount());
        double curComp = curSum != null ? bd(curSum.getCompensationFee()) : dbl(curInfo != null ? curInfo.getThisCompensationFee() : 0d);
        double curMisc = curSum != null ? bd(curSum.getMiscFee()) + bd(curSum.getExtraFee()) + bd(curSum.getRepairFee())
                : dbl(curInfo != null ? curInfo.getThisIncidentalFee() : 0d);
        double curDeduct = curSum != null ? -(bd(curSum.getStoppageDeductionAmount()) + bd(curSum.getNegotiationDiscountAmount())) : 0d;
        double curTotal = bd(current.getFinalAmount()) > 0d ? bd(current.getFinalAmount())
                : (curInfo != null && curInfo.getThisTotalFee() != null ? curInfo.getThisTotalFee() : (curBase + curInc + curComp + curMisc + curDeduct));

        double prevBase = prevSum != null ? bd(prevSum.getCurrentRentFee()) : dbl(prevInfo != null ? prevInfo.getThisRentFee() : 0d);
        double prevInc = prevSum != null ? bd(prevSum.getIncrementRentFee()) : (previous != null ? bd(previous.getIncrementRentAmount()) : 0d);
        double prevComp = prevSum != null ? bd(prevSum.getCompensationFee()) : dbl(prevInfo != null ? prevInfo.getThisCompensationFee() : 0d);
        double prevMisc = prevSum != null ? bd(prevSum.getMiscFee()) + bd(prevSum.getExtraFee()) + bd(prevSum.getRepairFee())
                : dbl(prevInfo != null ? prevInfo.getThisIncidentalFee() : 0d);
        double prevDeduct = prevSum != null ? -(bd(prevSum.getStoppageDeductionAmount()) + bd(prevSum.getNegotiationDiscountAmount())) : 0d;
        double prevTotal = previous != null
                ? (bd(previous.getFinalAmount()) > 0d ? bd(previous.getFinalAmount())
                : (prevInfo != null && prevInfo.getThisTotalFee() != null ? prevInfo.getThisTotalFee() : (prevBase + prevInc + prevComp + prevMisc + prevDeduct)))
                : 0d;

        double totalDelta = round2(curTotal - prevTotal);
        List<Map<String, Object>> factors = new ArrayList<>();
        factors.add(factor("BASE_RENT_VOLUME_DAYS", "基础租金变动（在租量×计费天数）", prevBase, curBase, totalDelta));
        factors.add(factor("OVERDUE_INCREMENT_RENT", "超期递增租金变动", prevInc, curInc, totalDelta));
        factors.add(factor("DAMAGE_COMPENSATION", "损坏丢失赔偿费变动", prevComp, curComp, totalDelta));
        factors.add(factor("HANDLING_TRANSPORT_MISC", "装卸运输维修等杂费变动", prevMisc, curMisc, totalDelta));
        factors.add(factor("PAUSE_AND_CONCESSION", "报停减免与协商让利变动", prevDeduct, curDeduct, totalDelta));

        List<Map<String, Object>> materialDeltas = new ArrayList<>();
        if (s.settlementRentFeeSegmentMapper != null) {
            Map<String, Double> curByMat = loadSegmentRentByMaterial(s, cid, current.getSettlementDocumentId());
            Map<String, Double> prevByMat = previous != null
                    ? loadSegmentRentByMaterial(s, cid, previous.getSettlementDocumentId())
                    : Collections.emptyMap();
            Set<String> allKeys = new LinkedHashSet<>(curByMat.keySet());
            allKeys.addAll(prevByMat.keySet());
            for (String key : allKeys) {
                double cVal = curByMat.getOrDefault(key, 0d);
                double pVal = prevByMat.getOrDefault(key, 0d);
                double dVal = round2(cVal - pVal);
                if (Math.abs(dVal) >= 0.01d) {
                    Map<String, Object> mRow = new LinkedHashMap<>();
                    mRow.put("materialName", key);
                    mRow.put("previousRent", round2(pVal));
                    mRow.put("currentRent", round2(cVal));
                    mRow.put("deltaRent", dVal);
                    materialDeltas.add(mRow);
                }
            }
            materialDeltas.sort((x, y) -> Double.compare(
                    Math.abs(((Number) y.get("deltaRent")).doubleValue()),
                    Math.abs(((Number) x.get("deltaRent")).doubleValue())));
        }

        String curPeriod = safe(current.getStartDate()) + "~" + safe(current.getEndDate());
        String prevPeriod = previous != null ? safe(previous.getStartDate()) + "~" + safe(previous.getEndDate()) : "无上期基线";
        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】项目本期结算（%s，合计 %,.2f 元）对比上期（%s，合计 %,.2f 元）变动 %+,.2f 元：其中基础租金变动 %+,.2f 元、超期递增变动 %+,.2f 元、赔偿费变动 %+,.2f 元、杂费变动 %+,.2f 元、报停与让利变动 %+,.2f 元。",
                curPeriod, curTotal, prevPeriod, prevTotal, totalDelta,
                round2(curBase - prevBase), round2(curInc - prevInc),
                round2(curComp - prevComp), round2(curMisc - prevMisc), round2(curDeduct - prevDeduct));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("projectId", projectId);
        result.put("projectName", projectName);
        result.put("currentSettlementId", current.getSettlementDocumentId());
        result.put("currentPeriod", curPeriod);
        result.put("currentTotalFee", round2(curTotal));
        result.put("previousSettlementId", previous != null ? previous.getSettlementDocumentId() : null);
        result.put("previousPeriod", prevPeriod);
        result.put("previousTotalFee", round2(prevTotal));
        result.put("totalDelta", totalDelta);
        result.put("factorBreakdown", factors);
        result.put("materialDeltas", materialDeltas);
        result.put("actionHint", "可将上述五因子拆解结果直接发送客户核对争议项，或点击下方按钮打开「财务对账」查看结算分段快照。");
        result.put("_navigationTarget", "PROJECT_WORKFLOW");
        result.put("_workflow", "financeCheck");
        return result;
    }

    private static SettlementDocumentEntity findSettlement(List<SettlementDocumentEntity> docs,
                                                           String targetId,
                                                           int fallbackIndex) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(targetId)) {
            for (SettlementDocumentEntity d : docs) {
                if (targetId.equals(d.getSettlementDocumentId())) {
                    return d;
                }
            }
        }
        return fallbackIndex < docs.size() ? docs.get(fallbackIndex) : null;
    }

    private static Map<String, Double> loadSegmentRentByMaterial(AgentBusinessQueryService s,
                                                                 String cid,
                                                                 String settlementId) {
        if (!StringUtils.hasText(settlementId) || s.settlementRentFeeSegmentMapper == null) {
            return Collections.emptyMap();
        }
        List<SettlementRentFeeSegmentEntity> segs = s.settlementRentFeeSegmentMapper.selectList(
                new QueryWrapper<SettlementRentFeeSegmentEntity>()
                        .eq("cid", cid).eq("settlement_document_id", settlementId));
        Map<String, Double> map = new LinkedHashMap<>();
        if (segs != null) {
            for (SettlementRentFeeSegmentEntity seg : segs) {
                String key = (safe(seg.getMaterialName()) + " " + safe(seg.getMaterialSpecification())).trim();
                double fee = seg.getMaterialFee() != null ? seg.getMaterialFee().doubleValue()
                        : (bd(seg.getBaseAmount()) + bd(seg.getIncrementAmount()));
                map.merge(key, fee, Double::sum);
            }
        }
        return map;
    }

    private static Map<String, Object> factor(String code, String name, double prev, double cur, double totalDelta) {
        double delta = round2(cur - prev);
        double share = Math.abs(totalDelta) > 0.001d ? round2((delta / totalDelta) * 100.0) : 0d;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("factorCode", code);
        map.put("factorName", name);
        map.put("previousAmount", round2(prev));
        map.put("currentAmount", round2(cur));
        map.put("deltaAmount", delta);
        map.put("sharePercent", share);
        return map;
    }

    // =========================================================================
    // 9. advisor.material_bcg_matrix — 材料利用率与闲置库存分析 (Phase 3B)
    // =========================================================================
    private static Map<String, Object> buildMaterialBcgMatrix(AgentBusinessQueryService s,
                                                              Map<String, Object> a) {
        String keyword = str(a, "keyword");
        int deadStockDays = a.get("deadStockDays") instanceof Number && ((Number) a.get("deadStockDays")).intValue() > 0
                ? ((Number) a.get("deadStockDays")).intValue() : 60;

        List<InventoryMaterialItemRet> list = loadAllInventoryMaterials(s, keyword, 1000);

        Map<String, Integer> publishMap = new LinkedHashMap<>();
        if (s.materialMapper != null) {
            List<MaterialInfoEntity> masters = s.materialMapper.selectList(
                    new QueryWrapper<MaterialInfoEntity>().eq("cid", CommonUtil.getCid()));
            if (masters != null) {
                for (MaterialInfoEntity m : masters) {
                    publishMap.put(m.getMid(), m.getMarketPublishFlag());
                }
            }
        }

        int starCount = 0;
        int cashCowCount = 0;
        int questionCount = 0;
        int deadStockCount = 0;
        int unpublishedDeadStockCount = 0;
        int zeroInactiveCount = 0;
        List<Map<String, Object>> activeRows = new ArrayList<>();
        List<Map<String, Object>> zeroRows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (InventoryMaterialItemRet item : list) {
            double stock = item.getInventoryQuantity() == null ? 0d : Math.max(0d, item.getInventoryQuantity().doubleValue());
            double rented = item.getRentedQuantity() == null ? 0d : Math.max(0d, item.getRentedQuantity().doubleValue());
            double totalOwned = stock + rented;
            int noFlowDays = daysSince(item.getLastFlowDate(), today);
            boolean published = Integer.valueOf(1).equals(publishMap.get(item.getMaterialId()));

            if (totalOwned <= 0d) {
                zeroInactiveCount++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("materialId", item.getMaterialId());
                row.put("materialName", item.getMaterialName());
                row.put("materialSpecification", item.getMaterialSpecification());
                row.put("countingUnit", item.getInventoryUnit());
                row.put("inventoryQuantity", 0d);
                row.put("rentedQuantity", 0d);
                row.put("utilizationRatePercent", 0d);
                row.put("daysSinceLastFlow", noFlowDays);
                row.put("bcgQuadrant", "QUESTION");
                row.put("marketPublished", published);
                row.put("strategyAdvice", "无在库及在租实物（仅建档目录）：暂无需调拨或去库存动作");
                zeroRows.add(row);
                continue;
            }

            double utilizationRate = round2((rented * 100.0) / totalOwned);
            String quadrant;
            String strategyAdvice;
            if (utilizationRate >= 70.0) {
                quadrant = "STAR";
                strategyAdvice = "高利用率（紧缺）：出租率≥70%，在租需求旺盛，建议优先保障重点工地供应或从完工项目及时回收周转";
                starCount++;
            } else if (utilizationRate >= 45.0) {
                quadrant = "CASH_COW";
                strategyAdvice = "中利用率（正常周转）：出租率处于合理区间，建议维持现有安全库存并严控工地损耗赔偿";
                cashCowCount++;
            } else if (stock > 0d && noFlowDays >= deadStockDays && utilizationRate < 20.0) {
                quadrant = "DOG_DEAD_STOCK";
                deadStockCount++;
                if (!published) {
                    unpublishedDeadStockCount++;
                    strategyAdvice = "长期闲置积压（未上架商城）：已连续" + noFlowDays + "天无流水且库房积压，建议立即在租赁商城上架出租或同行调剂变现";
                } else {
                    strategyAdvice = "长期闲置积压（已上架商城）：建议下调商城报价、优先匹配新开工地预估单或打包清理积压库存";
                }
            } else {
                quadrant = "QUESTION";
                strategyAdvice = "低利用率（待消化）：当前库房闲置占比偏高，建议结合近期在建项目与预估单需求优先内部调配消化";
                questionCount++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", item.getMaterialId());
            row.put("materialName", item.getMaterialName());
            row.put("materialSpecification", item.getMaterialSpecification());
            row.put("countingUnit", item.getInventoryUnit());
            row.put("inventoryQuantity", stock);
            row.put("rentedQuantity", rented);
            row.put("utilizationRatePercent", utilizationRate);
            row.put("daysSinceLastFlow", noFlowDays);
            row.put("bcgQuadrant", quadrant);
            row.put("marketPublished", published);
            row.put("strategyAdvice", strategyAdvice);
            activeRows.add(row);
        }

        activeRows.sort(Comparator
                .comparingInt((Map<String, Object> r) -> quadrantPriority(String.valueOf(r.get("bcgQuadrant"))))
                .thenComparing((Map<String, Object> r) -> ((Number) r.get("inventoryQuantity")).doubleValue()
                        + ((Number) r.get("rentedQuantity")).doubleValue(), Comparator.reverseOrder()));

        List<Map<String, Object>> matrixRows = activeRows.isEmpty() ? zeroRows : activeRows;

        String highHighlight = activeRows.stream()
                .filter(r -> ((Number) r.get("utilizationRatePercent")).doubleValue() >= 45.0)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> ((Number) r.get("utilizationRatePercent")).doubleValue()).reversed()
                        .thenComparingDouble(r -> -((Number) r.get("rentedQuantity")).doubleValue()))
                .limit(3)
                .map(r -> String.format(Locale.ROOT, "%s(%s，在租%.0f%s/库存%.0f%s，利用率%.2f%%)",
                        safe((String) r.get("materialName")), safe((String) r.get("materialSpecification")),
                        ((Number) r.get("rentedQuantity")).doubleValue(), safe((String) r.get("countingUnit")),
                        ((Number) r.get("inventoryQuantity")).doubleValue(), safe((String) r.get("countingUnit")),
                        ((Number) r.get("utilizationRatePercent")).doubleValue()))
                .collect(Collectors.joining("、"));

        String idleHighlight = activeRows.stream()
                .filter(r -> ((Number) r.get("inventoryQuantity")).doubleValue() > 0d
                        && ((Number) r.get("utilizationRatePercent")).doubleValue() < 45.0)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> ((Number) r.get("inventoryQuantity")).doubleValue()).reversed())
                .limit(3)
                .map(r -> String.format(Locale.ROOT, "%s(%s，库房闲置%.0f%s/在租%.0f%s，利用率%.2f%%)",
                        safe((String) r.get("materialName")), safe((String) r.get("materialSpecification")),
                        ((Number) r.get("inventoryQuantity")).doubleValue(), safe((String) r.get("countingUnit")),
                        ((Number) r.get("rentedQuantity")).doubleValue(), safe((String) r.get("countingUnit")),
                        ((Number) r.get("utilizationRatePercent")).doubleValue()))
                .collect(Collectors.joining("、"));

        StringBuilder extraSummary = new StringBuilder();
        if (StringUtils.hasText(highHighlight)) {
            extraSummary.append(" 当前利用率较高/紧缺代表物资：").append(highHighlight).append("；");
        }
        if (StringUtils.hasText(idleHighlight)) {
            extraSummary.append(" 库房闲置较多待消化代表物资：").append(idleHighlight).append("。");
        }

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】全公司周转材料利用率与闲置分析（各规格按自身单位独立核算，不跨单位混加）：共核查有实物库存或在租的材料 %d 项%s，其中高利用率（紧缺，出租率≥70%%）%d 项、中利用率（正常周转，45%%~70%%）%d 项、低利用率（待消化）%d 项、长期闲置积压 %d 项（含 %d 项未上架租赁商城，可立即上架去库存）。%s",
                activeRows.size(),
                zeroInactiveCount > 0 ? String.format(Locale.ROOT, "（另已自动过滤 %d 项库存与在租均为0的空目录项）", zeroInactiveCount) : "",
                starCount, cashCowCount, questionCount, deadStockCount, unpublishedDeadStockCount,
                extraSummary.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("starCount", starCount);
        result.put("cashCowCount", cashCowCount);
        result.put("questionCount", questionCount);
        result.put("deadStockCount", deadStockCount);
        result.put("unpublishedDeadStockCount", unpublishedDeadStockCount);
        result.put("items", matrixRows);
        result.put("actionHint", "可点击下方按钮打开「库存管理」或「租赁商城」一键上架闲置积压物资。");
        result.put("_navigationTarget", "INVENTORY");
        return result;
    }

    private static int quadrantPriority(String q) {
        if ("STAR".equals(q)) return 1;
        if ("DOG_DEAD_STOCK".equals(q)) return 2;
        if ("CASH_COW".equals(q)) return 3;
        return 4;
    }

    private static List<InventoryMaterialItemRet> loadAllInventoryMaterials(AgentBusinessQueryService s,
                                                                            String keyword,
                                                                            int maxItems) {
        if (s.inventory == null) return Collections.emptyList();
        List<InventoryMaterialItemRet> all = new ArrayList<>();
        int pageNum = 1;
        int pageSize = 200;
        while (all.size() < maxItems) {
            QueryInventoryMaterialsParam param = new QueryInventoryMaterialsParam();
            param.setPageNum(pageNum);
            param.setPageSize(pageSize);
            param.setKeyword(keyword);
            InventoryMaterialListRet ret = s.inventory.queryInventoryMaterials(param);
            List<InventoryMaterialItemRet> page = ret != null && ret.getList() != null ? ret.getList() : Collections.emptyList();
            if (page.isEmpty()) break;
            all.addAll(page);
            int total = ret.getTotalNum() != null ? ret.getTotalNum().intValue() : all.size();
            if (all.size() >= total || page.size() < pageSize) break;
            pageNum++;
        }
        return all;
    }

    private static int daysSince(String lastFlowDate, LocalDate today) {
        if (!StringUtils.hasText(lastFlowDate)) {
            return 90;
        }
        try {
            String raw = lastFlowDate.trim();
            LocalDate d = LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw);
            return (int) Math.max(0L, ChronoUnit.DAYS.between(d, today));
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    // 10. advisor.counterparty_credit_profile — 客户与供应商360度信用风控画像 (Phase 3B)
    // =========================================================================
    private static Map<String, Object> buildCounterpartyCreditProfile(AgentBusinessQueryService s,
                                                                      Map<String, Object> a,
                                                                      AgentRuntimeRecords.Workspace w) {
        String keyword = str(a, "counterpartyKeyword");
        String directionFilter = StringUtils.hasText(str(a, "direction")) ? str(a, "direction") : "ALL";
        LocalDate today = LocalDate.now();

        FinanceLedgerSnapshotModels.Query q = new FinanceLedgerSnapshotModels.Query();
        q.setAsOfDate(today.toString());
        FinanceLedgerSnapshotModels.Result snapshot = s.financeLedgerSnapshot != null
                ? s.financeLedgerSnapshot.generate(w, q, FinanceLedgerSnapshotService.LedgerDirection.BOTH)
                : null;

        List<FinanceLedgerSnapshotModels.ProjectSummary> rentOutProjects =
                snapshot != null && snapshot.getRentOut() != null && snapshot.getRentOut().getProjects() != null
                        ? snapshot.getRentOut().getProjects() : Collections.emptyList();
        List<FinanceLedgerSnapshotModels.ProjectSummary> rentInProjects =
                snapshot != null && snapshot.getRentIn() != null && snapshot.getRentIn().getProjects() != null
                        ? snapshot.getRentIn().getProjects() : Collections.emptyList();

        Map<String, Integer> compCountByProject = new LinkedHashMap<>();
        if (s.compensationDocumentMapper != null) {
            List<CompensationDocumentEntity> comps = s.compensationDocumentMapper.selectList(
                    new QueryWrapper<CompensationDocumentEntity>().eq("cid", w.getCid()));
            if (comps != null) {
                for (CompensationDocumentEntity c : comps) {
                    compCountByProject.merge(c.getProjectId(), 1, Integer::sum);
                }
            }
        }

        Map<String, CounterpartyAccumulator> grouped = new LinkedHashMap<>();
        if ("ALL".equalsIgnoreCase(directionFilter) || "CUSTOMER".equalsIgnoreCase(directionFilter)) {
            accumulateProjects(grouped, rentOutProjects, "CUSTOMER", keyword, today, compCountByProject);
        }
        if ("ALL".equalsIgnoreCase(directionFilter) || "SUPPLIER".equalsIgnoreCase(directionFilter)) {
            accumulateProjects(grouped, rentInProjects, "SUPPLIER", keyword, today, compCountByProject);
        }

        int gradeA = 0, gradeB = 0, gradeC = 0, gradeD = 0;
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (CounterpartyAccumulator acc : grouped.values()) {
            double settled = acc.settledPrincipal.doubleValue();
            double allocated = acc.allocatedPrincipal.doubleValue();
            double overdue = acc.overduePrincipal.doubleValue();
            double fulfillmentRate = settled > 0d ? round2((allocated * 100.0) / settled) : 100.0;

            String grade;
            String riskAdvice;
            if (overdue <= 0.01d && fulfillmentRate >= 85.0) {
                grade = "A";
                gradeA++;
                riskAdvice = "A级优质守约合作方：回款/履约率高且无逾期，可给予优先排期与免租期谈判空间";
            } else if (acc.maxOverdueDays <= 30 && fulfillmentRate >= 60.0) {
                grade = "B";
                gradeB++;
                riskAdvice = "B级良性合作方：存在短期账期周转，建议按常规对账周期跟进";
            } else if (acc.maxOverdueDays <= 90 && fulfillmentRate >= 30.0) {
                grade = "C";
                gradeC++;
                riskAdvice = "C级关注预警合作方：逾期或未核销金额偏高，建议先收款/对账后再审批新发料";
            } else {
                grade = "D";
                gradeD++;
                riskAdvice = "D级高危风控合作方：长期逾期或履约率低于30%，建议暂停新增赊销发料并启动专人催收";
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("counterpartyName", acc.name);
            row.put("counterpartyDirection", acc.direction);
            row.put("creditGrade", grade);
            row.put("projectCount", acc.projectCount);
            row.put("settledPrincipal", acc.settledPrincipal);
            row.put("allocatedPrincipal", acc.allocatedPrincipal);
            row.put("outstandingPrincipal", acc.outstandingPrincipal);
            row.put("overduePrincipal", acc.overduePrincipal);
            row.put("fulfillmentRatePercent", fulfillmentRate);
            row.put("maxOverdueDays", acc.maxOverdueDays);
            row.put("compensationDocCount", acc.compensationDocCount);
            row.put("riskAdvice", riskAdvice);
            profiles.add(row);
        }

        profiles.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("creditGrade"))).reversed()
                .thenComparing((Map<String, Object> r) -> ((BigDecimal) r.get("overduePrincipal")).doubleValue(), Comparator.reverseOrder()));

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】冻结范围内合作方360°信用风控画像完成：共评估 %d 家合作单位，其中 A级优质 %d 家、B级良性 %d 家、C级预警 %d 家、D级高危 %d 家。",
                profiles.size(), gradeA, gradeB, gradeC, gradeD);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("counterpartyCount", profiles.size());
        result.put("gradeACount", gradeA);
        result.put("gradeBCount", gradeB);
        result.put("gradeCCount", gradeC);
        result.put("gradeDCount", gradeD);
        result.put("profiles", profiles);
        result.put("actionHint", "对 C/D 级客户建议收紧授信并在发料前核查逾期回款；可结合「应收催缴清单」跟进催收。");
        return result;
    }

    private static void accumulateProjects(Map<String, CounterpartyAccumulator> grouped,
                                           List<FinanceLedgerSnapshotModels.ProjectSummary> list,
                                           String direction,
                                           String keyword,
                                           LocalDate today,
                                           Map<String, Integer> compCountByProject) {
        for (FinanceLedgerSnapshotModels.ProjectSummary pl : list) {
            String name = StringUtils.hasText(pl.getCounterpartyName()) ? pl.getCounterpartyName().trim() : "未登记合作单位";
            if (StringUtils.hasText(keyword) && !name.contains(keyword)) {
                continue;
            }
            String key = direction + "|" + name;
            CounterpartyAccumulator acc = grouped.computeIfAbsent(key, k -> new CounterpartyAccumulator(name, direction));
            acc.projectCount++;
            acc.settledPrincipal = acc.settledPrincipal.add(nz(pl.getPostedPrincipal()));
            acc.allocatedPrincipal = acc.allocatedPrincipal.add(nz(pl.getAllocatedPrincipalAsOf()));
            acc.outstandingPrincipal = acc.outstandingPrincipal.add(nz(pl.getOutstandingPrincipalAsOf()));
            acc.overduePrincipal = acc.overduePrincipal.add(nz(pl.getOverdueOutstanding()));
            if (nz(pl.getOverdueOutstanding()).doubleValue() > 0d && StringUtils.hasText(pl.getEarliestDueDate())) {
                int od = daysSince(pl.getEarliestDueDate(), today);
                if (od > acc.maxOverdueDays) {
                    acc.maxOverdueDays = od;
                }
            }
            acc.compensationDocCount += compCountByProject.getOrDefault(pl.getProjectId(), 0);
        }
    }

    private static final class CounterpartyAccumulator {
        final String name;
        final String direction;
        int projectCount;
        BigDecimal settledPrincipal = BigDecimal.ZERO;
        BigDecimal allocatedPrincipal = BigDecimal.ZERO;
        BigDecimal outstandingPrincipal = BigDecimal.ZERO;
        BigDecimal overduePrincipal = BigDecimal.ZERO;
        int maxOverdueDays;
        int compensationDocCount;

        CounterpartyAccumulator(String name, String direction) {
            this.name = name;
            this.direction = direction;
        }
    }

    // =========================================================================
    // 11. advisor.sublease_arbitrage_radar — 自有闲置与外租未退止损雷达 (Phase 3B)
    // =========================================================================
    private static Map<String, Object> scanSubleaseArbitrage(AgentBusinessQueryService s,
                                                             Map<String, Object> a) {
        String keyword = str(a, "keyword");
        String cid = CommonUtil.getCid();

        List<InventoryMaterialItemRet> invItems = loadAllInventoryMaterials(s, keyword, 1000);

        Map<String, InventoryMaterialItemRet> idleByMid = new LinkedHashMap<>();
        Map<String, InventoryMaterialItemRet> idleBySpecKey = new LinkedHashMap<>();
        for (InventoryMaterialItemRet item : invItems) {
            double idle = item.getInventoryQuantity() == null ? 0d : item.getInventoryQuantity().doubleValue();
            if (idle > 0d) {
                if (StringUtils.hasText(item.getMaterialId())) {
                    idleByMid.put(item.getMaterialId(), item);
                }
                idleBySpecKey.put(specKey(item.getMaterialName(), item.getMaterialSpecification(), item.getInventoryUnit()), item);
            }
        }

        List<ProjectEntity> rentInProjects = s.projectMapper != null
                ? s.projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                        .eq("cid", cid).eq("project_business_type", "rent_in"))
                : Collections.emptyList();

        Map<String, ProjectEntity> projMap = rentInProjects.stream()
                .collect(Collectors.toMap(ProjectEntity::getProjectId, p -> p, (x, y) -> x, LinkedHashMap::new));

        MaterialBalanceService.Result balResult = (s.materialBalances != null && !projMap.isEmpty())
                ? s.materialBalances.query(cid, projMap, LocalDate.now())
                : new MaterialBalanceService.Result();

        List<Map<String, Object>> opportunities = new ArrayList<>();
        Map<String, Map<String, Double>> pricesByProject = new LinkedHashMap<>();
        double totalDailySavings = 0d;

        for (MaterialBalanceService.Balance bal : balResult.balances.values()) {
            if (bal == null || bal.outstanding().doubleValue() <= 0d) {
                continue;
            }
            InventoryMaterialItemRet idleStock = idleByMid.get(bal.materialId);
            if (idleStock == null) {
                idleStock = idleBySpecKey.get(specKey(bal.name, bal.specification, bal.unit));
            }
            if (idleStock == null || idleStock.getInventoryQuantity() == null
                    || idleStock.getInventoryQuantity().doubleValue() <= 0d) {
                continue;
            }
            ProjectEntity proj = projMap.get(bal.projectId);
            Map<String, Double> priceByMid = pricesByProject.computeIfAbsent(
                    bal.projectId, pid -> loadProjectUnitPrices(s, cid, pid));
            double warehouseIdle = idleStock.getInventoryQuantity().doubleValue();
            double externalUnreturned = bal.outstanding().doubleValue();
            double replaceableQty = Math.min(warehouseIdle, externalUnreturned);
            double unitDailyRent = priceByMid.getOrDefault(bal.materialId, 0.5d);
            double dailySaving = round2(replaceableQty * unitDailyRent);
            double monthlySaving = round2(dailySaving * 30.0);
            totalDailySavings += dailySaving;

            Map<String, Object> opp = new LinkedHashMap<>();
            opp.put("rentInProjectId", bal.projectId);
            opp.put("rentInProjectName", proj != null ? proj.getProjectName() : bal.projectId);
            opp.put("supplierName", proj != null ? proj.getTenantUnit() : "");
            opp.put("materialId", bal.materialId);
            opp.put("materialName", bal.name);
            opp.put("materialSpecification", bal.specification);
            opp.put("countingUnit", bal.unit);
            opp.put("warehouseIdleQty", warehouseIdle);
            opp.put("externalUnreturnedQty", externalUnreturned);
            opp.put("replaceableQty", replaceableQty);
            opp.put("unitDailyRent", unitDailyRent);
            opp.put("estimatedDailySavings", dailySaving);
            opp.put("estimatedMonthlySavings", monthlySaving);
            opportunities.add(opp);
        }

        int totalInvCount = invItems.size();
        boolean scanTruncated = false;
        totalDailySavings = round2(totalDailySavings);
        double totalMonthlySavings = round2(totalDailySavings * 30.0);
        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】自有库房闲置替换外租材料分析完成：共发现 %d 项可用本企业库房闲置库存直接替换退租的省钱机会，预计每天可节省外部租金 %,.2f 元（折合每月节省 %,.2f 元）。%s",
                opportunities.size(), totalDailySavings, totalMonthlySavings,
                scanTruncated ? String.format(Locale.ROOT, "（注：仓库库存共 %d 项，本次仅扫描前 %d 项）", totalInvCount, invItems.size()) : "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("opportunityCount", opportunities.size());
        result.put("scanTruncated", scanTruncated);
        result.put("scannedInventoryCount", invItems.size());
        result.put("totalInventoryCount", totalInvCount);
        result.put("totalEstimatedDailySavings", totalDailySavings);
        result.put("totalEstimatedMonthlySavings", totalMonthlySavings);
        result.put("opportunities", opportunities);
        result.put("actionHint", "请点击下方按钮打开「租入项目管理」，优先办理高日租外租材料的退租归还。");
        result.put("_navigationTarget", "PROJECT_RENT_IN");
        return result;
    }

    private static Map<String, Double> loadProjectUnitPrices(AgentBusinessQueryService s, String cid, String projectId) {
        if (s.projectMaterialPricingMapper == null) {
            return Collections.emptyMap();
        }
        List<ProjectMaterialPricingEntity> list = s.projectMaterialPricingMapper.selectList(
                new QueryWrapper<ProjectMaterialPricingEntity>()
                        .eq("cid", cid).eq("project_id", projectId));
        Map<String, Double> map = new LinkedHashMap<>();
        if (list != null) {
            for (ProjectMaterialPricingEntity p : list) {
                double price = p.getDailyRent() != null ? p.getDailyRent().doubleValue()
                        : p.getSaleUnitPrice() != null ? p.getSaleUnitPrice().doubleValue() : 0d;
                if (StringUtils.hasText(p.getMid()) && price > 0d) {
                    map.put(p.getMid(), price);
                }
            }
        }
        return map;
    }

    // =========================================================================
    // 12. advisor.cross_site_transfer_plan — 跨工地就近调拨优化建议 (Phase 3B)
    // =========================================================================
    private static Map<String, Object> planCrossSiteTransfer(AgentBusinessQueryService s,
                                                             Map<String, Object> a,
                                                             AgentRuntimeRecords.Workspace w) {
        String keyword = str(a, "keyword");
        String cid = w.getCid();

        QueryWrapper<ProjectEntity> pq = new QueryWrapper<ProjectEntity>()
                .eq("cid", cid).eq("project_business_type", "rent_out");
        if (!"ALL".equalsIgnoreCase(w.getSelectionMode()) && w.getProjectIds() != null && !w.getProjectIds().isEmpty()) {
            pq.in("project_id", w.getProjectIds());
        }
        List<ProjectEntity> projects = s.projectMapper != null ? s.projectMapper.selectList(pq) : Collections.emptyList();
        Map<String, ProjectEntity> projMap = projects.stream()
                .collect(Collectors.toMap(ProjectEntity::getProjectId, p -> p, (x, y) -> x, LinkedHashMap::new));

        MaterialBalanceService.Result balResult = (s.materialBalances != null && !projMap.isEmpty())
                ? s.materialBalances.query(cid, projMap, LocalDate.now())
                : new MaterialBalanceService.Result();

        Map<String, Double> activeByProjMid = new LinkedHashMap<>();
        List<SiteMaterialSlot> supplySlots = new ArrayList<>();
        List<SiteMaterialSlot> demandSlots = new ArrayList<>();

        for (MaterialBalanceService.Balance b : balResult.balances.values()) {
            if (b == null || b.outstanding().doubleValue() <= 0d) {
                continue;
            }
            activeByProjMid.put(b.projectId + "|" + b.materialId, b.outstanding().doubleValue());
            ProjectEntity proj = projMap.get(b.projectId);
            if (proj != null && "1".equals(proj.getProjectStatusFlag())) {
                if (!StringUtils.hasText(keyword) || safe(b.name).contains(keyword)) {
                    supplySlots.add(new SiteMaterialSlot(proj.getProjectId(), proj.getProjectName(),
                            b.materialId, b.name, b.specification,
                            b.unit, b.outstanding().doubleValue()));
                }
            }
        }

        for (ProjectEntity proj : projects) {
            if ("0".equals(proj.getProjectStatusFlag()) && s.projectMaterialPricingMapper != null) {
                List<ProjectMaterialPricingEntity> pricings = s.projectMaterialPricingMapper.selectList(
                        new QueryWrapper<ProjectMaterialPricingEntity>()
                                .eq("cid", cid).eq("project_id", proj.getProjectId()));
                if (pricings != null) {
                    for (ProjectMaterialPricingEntity p : pricings) {
                        if (StringUtils.hasText(keyword) && !safe(p.getMaterialName()).contains(keyword)) {
                            continue;
                        }
                        double expected = p.getPredictRentNumber() != null ? p.getPredictRentNumber().doubleValue() : 0d;
                        double current = activeByProjMid.getOrDefault(proj.getProjectId() + "|" + p.getMid(), 0d);
                        if (expected > current) {
                            demandSlots.add(new SiteMaterialSlot(proj.getProjectId(), proj.getProjectName(),
                                    p.getMid(), p.getMaterialName(), p.getMaterialSpecification(),
                                    p.getCountingUnit(), round2(expected - current)));
                        }
                    }
                }
            }
        }

        if ((supplySlots.isEmpty() || demandSlots.isEmpty()) && s.inventory != null && !projects.isEmpty()) {
            LocalDate today = LocalDate.now();
            Set<String> existingSupplyKeys = supplySlots.stream()
                    .map(sl -> sl.projectId + "|" + specKey(sl.materialName, sl.spec, sl.unit))
                    .collect(Collectors.toSet());
            Set<String> existingDemandKeys = demandSlots.stream()
                    .map(dl -> dl.projectId + "|" + specKey(dl.materialName, dl.spec, dl.unit))
                    .collect(Collectors.toSet());
            for (ProjectEntity proj : projects) {
                List<SiteDistributionMaterialRet> siteMaterials = s.inventory.querySiteDistributionDetail(
                        proj.getProjectId(), null, keyword);
                if (siteMaterials == null) continue;
                for (SiteDistributionMaterialRet m : siteMaterials) {
                    int exp = m.getExpectedQuantity() == null ? 0 : m.getExpectedQuantity();
                    int idleDays = daysSince(m.getLastFlowDate(), today);
                    String sk = specKey(m.getMaterialName(), m.getMaterialSpecification(), m.getInventoryUnit());
                    if (exp > 0 && ("1".equals(proj.getProjectStatusFlag()) || idleDays >= 60)) {
                        if (existingSupplyKeys.add(proj.getProjectId() + "|" + sk)) {
                            supplySlots.add(new SiteMaterialSlot(proj.getProjectId(), proj.getProjectName(),
                                    m.getMaterialId(), m.getMaterialName(), m.getMaterialSpecification(),
                                    m.getInventoryUnit(), exp));
                        }
                    } else if (exp > 0 && "0".equals(proj.getProjectStatusFlag()) && idleDays < 60) {
                        if (existingDemandKeys.add(proj.getProjectId() + "|" + sk)) {
                            demandSlots.add(new SiteMaterialSlot(proj.getProjectId(), proj.getProjectName(),
                                    m.getMaterialId(), m.getMaterialName(), m.getMaterialSpecification(),
                                    m.getInventoryUnit(), exp));
                        }
                    } else if (exp < 0) {
                        if (existingDemandKeys.add(proj.getProjectId() + "|" + sk)) {
                            demandSlots.add(new SiteMaterialSlot(proj.getProjectId(), proj.getProjectName() + "（多还待平账）",
                                    m.getMaterialId(), m.getMaterialName(), m.getMaterialSpecification(),
                                    m.getInventoryUnit(), Math.abs(exp)));
                        }
                    }
                }
            }
            for (InventoryMaterialItemRet inv : loadAllInventoryMaterials(s, keyword, 1000)) {
                double stock = inv.getInventoryQuantity() == null ? 0d : inv.getInventoryQuantity().doubleValue();
                double rented = inv.getRentedQuantity() == null ? 0d : inv.getRentedQuantity().doubleValue();
                if (stock <= 0d && rented > 0d) {
                    String sk = specKey(inv.getMaterialName(), inv.getMaterialSpecification(), inv.getInventoryUnit());
                    if (existingDemandKeys.add("WAREHOUSE|" + sk)) {
                        demandSlots.add(new SiteMaterialSlot("WAREHOUSE", "中心库房（紧缺零库存补货）",
                                inv.getMaterialId(), inv.getMaterialName(), inv.getMaterialSpecification(),
                                inv.getInventoryUnit(), rented));
                    }
                }
            }
        }

        int totalSupplyCount = supplySlots.size();
        int totalDemandCount = demandSlots.size();
        List<Map<String, Object>> transferPlans = new ArrayList<>();
        for (SiteMaterialSlot supply : supplySlots) {
            if (supply.qty <= 0d || transferPlans.size() >= 20) {
                continue;
            }
            for (SiteMaterialSlot demand : demandSlots) {
                if (demand.qty <= 0d || supply.projectId.equals(demand.projectId) || transferPlans.size() >= 20) {
                    continue;
                }
                if (specKey(supply.materialName, supply.spec, supply.unit)
                        .equals(specKey(demand.materialName, demand.spec, demand.unit))) {
                    double transferQty = round2(Math.min(supply.qty, demand.qty));
                    if (transferQty <= 0d) {
                        continue;
                    }
                    supply.qty = round2(supply.qty - transferQty);
                    demand.qty = round2(demand.qty - transferQty);
                    Map<String, Object> plan = new LinkedHashMap<>();
                    plan.put("fromProjectId", supply.projectId);
                    plan.put("fromProjectName", supply.projectName);
                    plan.put("toProjectId", demand.projectId);
                    plan.put("toProjectName", demand.projectName);
                    plan.put("materialName", supply.materialName);
                    plan.put("materialSpecification", supply.spec);
                    plan.put("countingUnit", supply.unit);
                    plan.put("transferQty", transferQty);
                    plan.put("operationAdvice", "WAREHOUSE".equals(demand.projectId)
                            ? "该材料当前库房零库存紧缺，建议优先从超期无流水工地「" + supply.projectName + "」办理归还回收补库"
                            : "建议直接办理「" + supply.projectName + "」归还单与「" + demand.projectName + "」租出/调拨单点对点直调，省去回库二次装卸");
                    transferPlans.add(plan);
                }
            }
        }

        String conclusion = String.format(Locale.ROOT,
                "【30秒结论】跨工地就近调拨优化扫描完成：识别可调出/超期沉淀工地材料 %d 项、待补料/调入需求 %d 项，成功匹配同规格同单位跨项目调拨与回收方案 %d 组，可减少回仓二次出入库装卸损耗。",
                totalSupplyCount, totalDemandCount, transferPlans.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion30s", conclusion);
        result.put("supplySiteCount", totalSupplyCount);
        result.put("demandSiteCount", totalDemandCount);
        result.put("transferPairCount", transferPlans.size());
        result.put("transferPlans", transferPlans);
        result.put("actionHint", "请点击下方按钮打开「工地材料分布」核对各工地实时结存并安排车辆直调。");
        result.put("_navigationTarget", "SITE_DISTRIBUTION");
        return result;
    }

    private static final class SiteMaterialSlot {
        final String projectId;
        final String projectName;
        final String mid;
        final String materialName;
        final String spec;
        final String unit;
        double qty;

        SiteMaterialSlot(String projectId, String projectName, String mid,
                         String materialName, String spec, String unit, double qty) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.mid = mid;
            this.materialName = materialName;
            this.spec = spec;
            this.unit = unit;
            this.qty = qty;
        }
    }

    // =========================================================================
    // Pyramid Principle (金字塔法则) Structured Output Builder
    // =========================================================================
    @SuppressWarnings("unchecked")
    private static Map<String, Object> enrichPyramidOutput(String code, Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        String rawConclusion = safe((String) result.get("conclusion30s"))
                .replaceFirst("^【30秒结论】\\s*", "");
        String actionHint = safe((String) result.get("actionHint"));
        String headline = rawConclusion;
        String markdownTable = null;
        List<String> keyPoints = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> boundaryNotes = new ArrayList<>();

        switch (code) {
            case AgentToolCodes.DOCUMENT_PREVIEW_DRAFT: {
                headline = String.format(Locale.ROOT,
                        "项目「%s」%s建单前置核查已完成（计划日期 %s）：基地库存核查 %d 项（充足 %d / 缺口 %d），%s。",
                        result.getOrDefault("projectName", result.getOrDefault("projectId", "--")),
                        result.getOrDefault("documentTypeLabel", "业务单据"),
                        result.getOrDefault("startDate", "--"),
                        sizeOf(result.get("inventoryCheckList")),
                        numInt(result.get("enoughCount")),
                        numInt(result.get("shortageCount")),
                        numInt(result.get("configuredMaterialCount")) > 0
                                ? String.format(Locale.ROOT, "已匹配合同单价 %d 项（参考日租合计 %,.2f 元/天）",
                                        numInt(result.get("configuredMaterialCount")),
                                        num(result.get("estimatedDailyRent")))
                                : "待在项目合同补充材料单价");
                keyPoints.add(String.format(Locale.ROOT,
                        "**基地库存核查**：共核查 %d 项材料规格，其中库存充足 %d 项、库存不足需补货 %d 项。",
                        sizeOf(result.get("inventoryCheckList")),
                        numInt(result.get("enoughCount")),
                        numInt(result.get("shortageCount"))));
                keyPoints.add(String.format(Locale.ROOT,
                        "**合同单价核查**：已配置合同单价 %d 项、待补充单价 %d 项（明细见下方准备清单）。",
                        numInt(result.get("configuredMaterialCount")),
                        numInt(result.get("unconfiguredMaterialCount"))));
                break;
            }
            case AgentToolCodes.SETTLEMENT_PREVIEW_DRAFT: {
                headline = String.format(Locale.ROOT,
                        "项目「%s」（%s 至 %s）结算试算本期应结总额 %,.2f 元（仅预览草稿，不自动入账）。",
                        result.getOrDefault("documentName", result.getOrDefault("projectId", "--")),
                        result.getOrDefault("startDate", "--"),
                        result.getOrDefault("endDate", "--"),
                        num(result.get("periodTotalFee")));
                keyPoints.add(String.format(Locale.ROOT,
                        "**应收应付汇总**：应收汇总 %,.2f 元、应付汇总 %,.2f 元，共涵盖 %d 项材料租金分段。",
                        num(result.get("finalReceivableAmount")),
                        num(result.get("finalPayableAmount")),
                        sizeOf(result.get("settlementRows"))));
                keyPoints.add(String.format(Locale.ROOT,
                        "**费用构成拆解**：基础租金 %,.2f 元、递增租金 %,.2f 元、赔偿 %,.2f 元、杂费 %,.2f 元、报停减免 %,.2f 元。",
                        num(result.get("periodRentFee")),
                        num(result.get("incrementRentTotal")),
                        num(result.get("periodCompensationFee")),
                        num(result.get("periodIncidentalFee")),
                        num(result.get("stoppageDeductionAmount"))));
                break;
            }
            case AgentToolCodes.RECONCILIATION_PREVIEW_DRAFT: {
                int errCnt = sizeOf(result.get("validationErrors"));
                int warnCnt = numInt(result.get("warningCount"));
                headline = String.format(Locale.ROOT,
                        "项目「%s」（%s 至 %s）物料对账单试算草稿：%s（共核对 %d 行材料明细，%d 项阻断、%d 项提示）。",
                        result.getOrDefault("title", result.getOrDefault("projectId", "--")),
                        result.getOrDefault("startDate", "--"),
                        result.getOrDefault("endDate", "--"),
                        errCnt > 0 ? "存在阻断项待修复" : warnCnt > 0 ? "可对账（含提示项）" : "账实核对通过",
                        numInt(result.get("materialRowCount")), errCnt, warnCnt);
                keyPoints.add(String.format(Locale.ROOT,
                        "**核对规模**：共覆盖 %d 行材料期初、本期租出/归还与期末结存明细。",
                        numInt(result.get("materialRowCount"))));
                if (errCnt > 0 || warnCnt > 0) {
                    keyPoints.add(String.format(Locale.ROOT,
                            "**校验发现**：发现 %d 项阻断校验、%d 项风险提示（明细见下方结果卡）。", errCnt, warnCnt));
                }
                break;
            }
            case AgentToolCodes.FINANCE_LATE_FEE_TRIAL: {
                headline = String.format(Locale.ROOT,
                        "截至 %s 项目「%s」当前未结清逾期滞纳金 %,.2f 元（命中 %d 个逾期账期，日利率 %s%%）。",
                        result.getOrDefault("endDate", "--"),
                        result.getOrDefault("documentName", result.getOrDefault("projectId", "--")),
                        num(result.get("currentUnpaidAmount")),
                        numInt(result.get("overduePeriodCount")),
                        String.valueOf(result.getOrDefault("dailyRatePercent", "0.05")));
                keyPoints.add(String.format(Locale.ROOT,
                        "**滞纳金台账**：累计应计滞纳金 %,.2f 元，已收 %,.2f 元，未结清 %,.2f 元（只读试算未入账）。",
                        num(result.get("accumulatedAccruedAmount")),
                        num(result.get("accumulatedPaidAmount")),
                        num(result.get("currentUnpaidAmount"))));
                break;
            }
            case AgentToolCodes.GOVERNANCE_MATERIAL_ALIAS_AUDIT: {
                int missingUnit = numInt(result.get("missingUnitCount"));
                int dupSpec = numInt(result.get("duplicateSpecCount"));
                long pendingItem = numLong(result.get("pendingItemCount"));
                headline = String.format(Locale.ROOT,
                        "企业材料主数据体检：%s（主数据缺失单位 %d 项、同规格重复 %d 组、待映射迁移材料 %d 条）。",
                        (missingUnit + dupSpec + pendingItem) == 0 ? "主数据标准完整" : "存在主数据治理缺口需修正",
                        missingUnit, dupSpec, pendingItem);
                keyPoints.add(String.format(Locale.ROOT,
                        "**主数据规范性**：主数据缺失单位 %d 项，同名同规格重复录入 %d 组。",
                        missingUnit, dupSpec));
                keyPoints.add(String.format(Locale.ROOT,
                        "**迁移与别名沉淀**：待确认迁移批次 %d 个、待映射旧系统材料 %d 条，已沉淀标准别名 %d 条。",
                        numLong(result.get("pendingBatchCount")), pendingItem, numInt(result.get("aliasCount"))));
                break;
            }
            case AgentToolCodes.COPILOT_ORDER_FULFILLMENT_EVAL: {
                String verdict = String.valueOf(result.getOrDefault("decisionVerdict", ""));
                String verdictLabel = "CAN_ACCEPT_DIRECTLY".equals(verdict) ? "自有现货充足，可直接接单"
                        : "ACCEPT_WITH_MARKET_SOURCING".equals(verdict) ? "部分缺口，建议联动商城补货接单"
                        : "NO_MATCHING_MATERIAL".equals(verdict) ? "未匹配到对应库存材料" : "缺口较大，建议先落实外部货源再接单";
                int enough = numInt(result.get("enoughCount"));
                int shortage = numInt(result.get("shortageCount"));
                double rate = num(result.get("fulfillmentRate"));
                int marketCnt = sizeOf(result.get("marketRecommendations"));
                headline = String.format(Locale.ROOT,
                        "接单评估结论：%s（品种满足率 %.1f%%，充足 %d 项 / 缺口 %d 项）。",
                        verdictLabel, rate, enough, shortage);
                keyPoints.add(String.format(Locale.ROOT,
                        "**自有现货满足度**：共核查 %d 项材料，自有现货充足 %d 项、存在缺口 %d 项（满足率 %.1f%%）。",
                        enough + shortage, enough, shortage, rate));
                keyPoints.add(String.format(Locale.ROOT,
                        "**商城外部寻源**：已自动从租赁商城匹配 %d 条同行可供货源推荐（详见下方结果卡）。",
                        marketCnt));
                break;
            }
            case AgentToolCodes.COPILOT_CONTRACT_EXECUTION_AUDIT: {
                String level = String.valueOf(result.getOrDefault("complianceLevel", "PASS"));
                String levelLabel = "HIGH_RISK".equals(level) ? "高风险待整改"
                        : "WARNING".equals(level) ? "存在合规预警" : "合规通过";
                int findingCount = numInt(result.get("findingCount"));
                headline = String.format(Locale.ROOT,
                        "项目「%s」合同与执行合规审查：%s（共发现 %d 项风险偏差）。",
                        result.getOrDefault("projectName", result.getOrDefault("projectId", "--")), levelLabel, findingCount);
                keyPoints.add(String.format(Locale.ROOT,
                        "**定价与发料偏差**：无合同定价发料 %d 项、零单价/待映射配置 %d 项、超合同期发料 %d 单。",
                        numInt(result.get("unpricedDeliveryCount")),
                        numInt(result.get("zeroPriceCount")),
                        numInt(result.get("afterExpiryDeliveryCount"))));
                keyPoints.add(String.format(Locale.ROOT,
                        "**单据复核积压**：当前存在 %d 张已保存但未复核的业务单据。",
                        numInt(result.get("unreviewedDocCount"))));
                break;
            }
            case AgentToolCodes.COPILOT_PRICE_BREAKEVEN_SANDBOX: {
                List<Map<String, Object>> scenarios = (List<Map<String, Object>>) result.get("scenarios");
                String projLabel = String.valueOf(result.getOrDefault("projectName", result.getOrDefault("projectId", "--")));
                double baseDaily = num(result.get("baselineDailyRent"));
                int baseDays = numInt(result.get("leaseDays"));
                double baseTotal = num(result.get("baselineTotalRent"));
                List<String> concessionBriefs = new ArrayList<>();
                List<String> breakevenBriefs = new ArrayList<>();
                if (scenarios != null) {
                    StringBuilder tbl = new StringBuilder();
                    tbl.append("| 方案档位 | 折扣比例 | 免租天数 | 实际计费天数 | 折后日租金(元/天) | 预计总租金(元) | 让利差额(元) | 补差需延长(天) | 保本总租期(天) |\n");
                    tbl.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |");
                    for (int i = 0; i < scenarios.size(); i++) {
                        Map<String, Object> sc = scenarios.get(i);
                        String scName = String.valueOf(sc.getOrDefault("scenarioName", "方案" + (i + 1)));
                        double discPct = num(sc.get("discountPercent"));
                        int freeDays = numInt(sc.get("freeRentDays"));
                        int billDays = numInt(sc.get("effectiveBillingDays"));
                        double discDaily = num(sc.get("discountedDailyRent"));
                        double totalRent = num(sc.get("scenarioTotalRent"));
                        double concAmt = num(sc.get("concessionAmount"));
                        int extraDays = numInt(sc.get("extraDaysToRecoverConcession"));
                        int beDays = numInt(sc.get("breakevenTotalLeaseDays"));
                        tbl.append(String.format(Locale.ROOT,
                                "\n| %s | %.0f%% | %d 天 | %d 天 | %,.2f | %,.2f | %,.2f | %s | %d 天 |",
                                scName, discPct, freeDays, billDays, discDaily, totalRent, concAmt,
                                extraDays > 0 ? ("+" + extraDays + " 天") : "0 天", beDays));
                        if (i > 0) {
                            concessionBriefs.add(String.format(Locale.ROOT,
                                    "%s预计总租金 %,.2f 元（让利 %,.2f 元，需延长 %d 天至 %d 天补回差额）",
                                    scName, totalRent, concAmt, extraDays, beDays));
                            breakevenBriefs.add(String.format(Locale.ROOT,
                                    "%s保本需租满 %d 天（补差 +%d 天）", scName, beDays, extraDays));
                        }
                    }
                    markdownTable = tbl.toString();
                }
                headline = String.format(Locale.ROOT,
                        "项目「%s」报价沙盘结论（基准日租金 %,.2f 元/天、%d 天原价总租金 %,.2f 元）：%s。",
                        projLabel, baseDaily, baseDays, baseTotal,
                        concessionBriefs.isEmpty() ? "已完成原价基线测算" : String.join("；", concessionBriefs));
                keyPoints.add(String.format(Locale.ROOT,
                        "**基准测算盘面**：项目「%s」当前日租金基数 %,.2f 元/天，基准租期 %d 天，原价方案预计总租金 %,.2f 元（让利 0.00 元，无需延长租期）。",
                        projLabel, baseDaily, baseDays, baseTotal));
                if (!breakevenBriefs.isEmpty()) {
                    keyPoints.add(String.format(Locale.ROOT,
                            "**多档盈亏平衡底线**：共对比 %d 档方案，其中%s。",
                            scenarios == null ? 0 : scenarios.size(),
                            String.join("；", breakevenBriefs)));
                }
                String basisNote = safe((String) result.get("basisNote"));
                if (StringUtils.hasText(basisNote)) {
                    boundaryNotes.add(basisNote + "；本次仅作只读沙盘推演，不修改正式合同。");
                }
                break;
            }
            case AgentToolCodes.COPILOT_SETTLEMENT_DISPUTE_ATTRIBUTION: {
                if (result.get("currentSettlementId") == null) {
                    headline = rawConclusion;
                    break;
                }
                double delta = num(result.get("totalDelta"));
                String projLabel = String.valueOf(result.getOrDefault("projectName", result.getOrDefault("projectId", "--")));
                headline = String.format(Locale.ROOT,
                        "项目「%s」结算跨期归因：本期结算（%,.2f 元）较上期（%,.2f 元）变动 %+,.2f 元。",
                        projLabel,
                        num(result.get("currentTotalFee")),
                        num(result.get("previousTotalFee")),
                        delta);
                List<Map<String, Object>> factors = (List<Map<String, Object>>) result.get("factorBreakdown");
                if (factors != null && !factors.isEmpty()) {
                    String factorSummary = factors.stream()
                            .map(f -> f.get("factorName") + " " + String.format(Locale.ROOT, "%+,.2f 元", num(f.get("deltaAmount"))))
                            .collect(Collectors.joining("、"));
                    keyPoints.add("**五因子变动拆解**：" + factorSummary + "。");
                    StringBuilder tbl = new StringBuilder();
                    tbl.append("| 费用构成维度 | 上期金额(元) | 本期金额(元) | 跨期变动差额(元) | 占变动总额比例 |\n");
                    tbl.append("| :--- | :--- | :--- | :--- | :--- |");
                    for (Map<String, Object> f : factors) {
                        tbl.append(String.format(Locale.ROOT,
                                "\n| %s | %,.2f | %,.2f | %+,.2f | %.1f%% |",
                                f.getOrDefault("factorName", "--"),
                                num(f.get("previousAmount")),
                                num(f.get("currentAmount")),
                                num(f.get("deltaAmount")),
                                num(f.get("sharePercent"))));
                    }
                    markdownTable = tbl.toString();
                }
                List<Map<String, Object>> matDeltas = (List<Map<String, Object>>) result.get("materialDeltas");
                if (matDeltas != null && !matDeltas.isEmpty()) {
                    Map<String, Object> topMat = matDeltas.get(0);
                    keyPoints.add(String.format(Locale.ROOT,
                            "**首要波动材料**：「%s」本期租金变动最大（%+,.2f 元），共 %d 项材料发生金额变化。",
                            topMat.getOrDefault("materialName", "--"),
                            num(topMat.get("deltaRent")),
                            matDeltas.size()));
                }
                break;
            }
            case AgentToolCodes.ADVISOR_MATERIAL_BCG_MATRIX: {
                int deadCnt = numInt(result.get("deadStockCount"));
                int unpubCnt = numInt(result.get("unpublishedDeadStockCount"));
                int starCnt = numInt(result.get("starCount"));
                int cowCnt = numInt(result.get("cashCowCount"));
                int qCnt = numInt(result.get("questionCount"));
                int total = sizeOf(result.get("items"));
                headline = String.format(Locale.ROOT,
                        "材料利用率与闲置积压诊断：共扫描 %d 项材料，识别库房闲置积压 %d 项（其中 %d 项未上架商城可立即盘活）。",
                        total, deadCnt, unpubCnt);
                keyPoints.add(String.format(Locale.ROOT,
                        "**利用率结构分布**：高利用率紧缺区 %d 项、高利用率主力区 %d 项、中利用率观察区 %d 项、低利用率闲置积压区 %d 项。",
                        starCnt, cowCnt, qCnt, deadCnt));
                keyPoints.add("**核算口径约束**：各材料规格均按自身计数单位独立核算出租率，不跨单位混加。");
                break;
            }
            case AgentToolCodes.ADVISOR_COUNTERPARTY_CREDIT_PROFILE: {
                int total = numInt(result.get("counterpartyCount"));
                int gA = numInt(result.get("gradeACount"));
                int gB = numInt(result.get("gradeBCount"));
                int gC = numInt(result.get("gradeCCount"));
                int gD = numInt(result.get("gradeDCount"));
                headline = String.format(Locale.ROOT,
                        "合作方信用风控画像：共评估 %d 家单位，识别 C/D 级预警高危合作方 %d 家（C级 %d 家、D级 %d 家）。",
                        total, gC + gD, gC, gD);
                keyPoints.add(String.format(Locale.ROOT,
                        "**信用等级分布**：A级优质守约 %d 家、B级良性周转 %d 家、C级关注预警 %d 家、D级高危风控 %d 家。",
                        gA, gB, gC, gD));
                List<Map<String, Object>> profiles = (List<Map<String, Object>>) result.get("profiles");
                if (profiles != null && !profiles.isEmpty()) {
                    Map<String, Object> top = profiles.get(0);
                    keyPoints.add(String.format(Locale.ROOT,
                            "**重点关注对象**：「%s」（%s级，未核销本金 %,.2f 元，最长逾期 %d 天）。",
                            top.getOrDefault("counterpartyName", "--"),
                            top.getOrDefault("creditGrade", "--"),
                            num(top.get("outstandingPrincipal")),
                            numInt(top.get("maxOverdueDays"))));
                }
                break;
            }
            case AgentToolCodes.ADVISOR_SUBLEASE_ARBITRAGE_RADAR: {
                int oppCnt = numInt(result.get("opportunityCount"));
                double dailySave = num(result.get("totalEstimatedDailySavings"));
                double monthlySave = num(result.get("totalEstimatedMonthlySavings"));
                headline = String.format(Locale.ROOT,
                        "外租止损雷达结论：发现 %d 项可用自有闲置库存替换退租机会，预计每月可止损节省 %,.2f 元（日省 %,.2f 元）。",
                        oppCnt, monthlySave, dailySave);
                keyPoints.add(String.format(Locale.ROOT,
                        "**止损替换空间**：共识别 %d 项同规格同单位「仓库有闲置 + 外租未退还」错配材料。",
                        oppCnt));
                if (Boolean.TRUE.equals(result.get("scanTruncated"))) {
                    keyPoints.add(String.format(Locale.ROOT,
                            "**扫描范围说明**：仓库库存共 %d 项，本次抽样扫描前 %d 项。",
                            numInt(result.get("totalInventoryCount")),
                            numInt(result.get("scannedInventoryCount"))));
                }
                break;
            }
            case AgentToolCodes.ADVISOR_CROSS_SITE_TRANSFER_PLAN: {
                int pairCnt = numInt(result.get("transferPairCount"));
                int supCnt = numInt(result.get("supplySiteCount"));
                int demCnt = numInt(result.get("demandSiteCount"));
                headline = String.format(Locale.ROOT,
                        "跨工地调拨优化结论：成功匹配 %d 组同规格工地间点对点直调方案（可省去回库二次装卸）。",
                        pairCnt);
                keyPoints.add(String.format(Locale.ROOT,
                        "**供需匹配盘面**：识别已完工可调出工地材料 %d 项、进行中待补料工地需求 %d 项，精准配对 %d 组。",
                        supCnt, demCnt, pairCnt));
                break;
            }
            default:
                break;
        }

        if (StringUtils.hasText(actionHint)) {
            actions.add(actionHint);
        }

        StringBuilder pyramid = new StringBuilder();
        pyramid.append("【核心结论】").append(headline);
        if (StringUtils.hasText(markdownTable)) {
            pyramid.append("\n\n**方案横向对比**\n\n").append(markdownTable);
        }
        if (!keyPoints.isEmpty()) {
            pyramid.append("\n\n**关键支撑**");
            for (String pt : keyPoints) {
                pyramid.append("\n- ").append(pt);
            }
        }
        if (!actions.isEmpty()) {
            pyramid.append("\n\n**行动建议**");
            for (int i = 0; i < actions.size(); i++) {
                pyramid.append("\n").append(i + 1).append(". ").append(actions.get(i));
            }
        }
        if (!boundaryNotes.isEmpty()) {
            pyramid.append("\n\n**缺口**");
            for (String note : boundaryNotes) {
                pyramid.append("\n- ").append(note);
            }
        }
        result.put("conclusion30s", "【核心结论】" + headline);
        result.put("pyramidAnswer", pyramid.toString());
        Map<String, Object> pyramidSummary = new LinkedHashMap<>();
        pyramidSummary.put("headline", headline);
        pyramidSummary.put("markdownTable", markdownTable);
        pyramidSummary.put("keyPoints", keyPoints);
        pyramidSummary.put("actions", actions);
        pyramidSummary.put("boundaryNotes", boundaryNotes);
        result.put("pyramidSummary", pyramidSummary);
        return result;
    }

    private static double num(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return 0d;
    }

    private static int numInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return 0;
    }

    private static long numLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return 0L;
    }

    private static int sizeOf(Object v) {
        if (v instanceof java.util.Collection) {
            return ((java.util.Collection<?>) v).size();
        }
        return 0;
    }

    // =========================================================================
    // Helper utilities
    // =========================================================================
    private static String str(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    private static String specKey(String name, String spec, String unit) {
        return (safe(name) + "|" + safe(spec) + "|" + safe(unit)).toLowerCase(Locale.ROOT);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double bd(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static double dbl(Double v) {
        return v == null ? 0d : v;
    }
}
