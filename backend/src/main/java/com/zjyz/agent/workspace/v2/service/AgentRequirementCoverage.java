package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import java.util.*;

/** Checks distinct query evidence and known filters for every requested outcome. */
final class AgentRequirementCoverage {
    private AgentRequirementCoverage() {}
    static List<String> missing(AgentV2Models.TaskSpec spec,List<AgentSkillExecution> executions) {
        // Keep all observations: an earlier partial response must not hide a later complete one.
        List<AgentEvidence> available=new ArrayList<>();
        if(executions!=null) for(AgentSkillExecution execution:executions) {
            if(execution==null || execution.getEvidence()==null || Boolean.TRUE.equals(execution.getNeedClarification())) continue;
            available.add(execution.getEvidence());
        }
        List<String> gaps=new ArrayList<>();
        if(spec==null || spec.getRequirements()==null) return gaps;
        for(AgentV2Models.TaskRequirement requirement:spec.getRequirements()) {
            if(requirement==null) {gaps.add("任务要求尚未明确");continue;}
            List<String> codes=requirement.getCapabilityCodes();
            List<AgentEvidence> matched=new ArrayList<>();
            if(codes!=null) for(String code:new LinkedHashSet<>(codes)) {
                for(AgentEvidence e:available) {
                    if(Objects.equals(code,e.getToolCode()) && compatible(requirement,e) && matches(requirement.getCriteria(),e.getCriteria())) {matched.add(e);break;}
                }
            }
            if(matched.isEmpty()) {
                matched.addAll(fallbackMatchedEvidence(requirement, available));
            }
            if(matched.isEmpty()) {
                String description=requirement.getDescription();
                gaps.add(description==null || description.trim().isEmpty() ? "部分业务要求尚未完成" : description);
            }
        }
        return gaps;
    }
    private static boolean compatible(AgentV2Models.TaskRequirement expected, AgentEvidence actual) {
        Object availability=actual.getAvailability()!=null?actual.getAvailability():actual.getCriteria()==null?null:actual.getCriteria().get("availability");
        Object completeness=actual.getCompleteness()!=null?actual.getCompleteness():actual.getCriteria()==null?null:actual.getCriteria().get("completeness");
        if (availability != null && !"AVAILABLE".equals(availability)) return false;
        if ("UNKNOWN".equals(completeness)) return false;
        Object criteriaCompleteness = actual.getCriteria() == null ? null : actual.getCriteria().get("completeness");
        if ("PARTIAL".equals(criteriaCompleteness) || "material.occupancy".equals(actual.getToolCode())) {
            if ("PARTIAL".equals(completeness)) return false;
        }
        if (Boolean.TRUE.equals(expected.getRequireComplete()) && completeness != null && !"COMPLETE".equals(completeness)) return false;
        Object metric=actual.getMetricId()!=null?actual.getMetricId():actual.getCriteria()==null?null:actual.getCriteria().get("metricId");
        Object time=actual.getTimeBasis()!=null?actual.getTimeBasis():actual.getCriteria()==null?null:actual.getCriteria().getOrDefault("timeBasis",actual.getCriteria().get("dateBasis"));
        if (expected.getMetricId() != null && isStrictMetric(expected.getMetricId(), actual.getToolCode()) && !Objects.equals(expected.getMetricId(), metric)) return false;
        if(expected.getTimeBasis()!=null && isStrictTimeBasis(expected.getTimeBasis(), actual.getToolCode()) && !Objects.equals(expected.getTimeBasis(),time))return false;
        if(expected.getProjectIds()!=null && !expected.getProjectIds().isEmpty() && !projectIdsCompatible(expected.getProjectIds(), actual)) return false;
        if("help.search".equals(actual.getToolCode()) || "document.contract_review".equals(actual.getToolCode())
                || "document.preview_draft".equals(actual.getToolCode()))return true;
        Map<String,Object> criteria=actual.getCriteria()==null?Collections.emptyMap():actual.getCriteria();
        String start=expected.getStartDate(),end=expected.getEndDate();
        // Compatibility for legacy interpretations: an explicit single year is never unconstrained.
        // This is a temporal evidence check, not a keyword router or a guessed tool binding.
        if(start==null && end==null && expected.getAsOfDate()==null) {
            String description=expected.getDescription()==null?"":expected.getDescription();
            java.util.regex.Matcher months=java.util.regex.Pattern.compile("((?:19|20)[0-9]{2})年\\s*([0-9]{1,2})月").matcher(description);
            List<String> monthKeys=new ArrayList<>();while(months.find())monthKeys.add(months.group(1)+"-"+String.format("%02d",Integer.parseInt(months.group(2))));
            boolean dayExpression=description.matches("(?s).*年\\s*[0-9]{1,2}月\\s*[0-9]{1,2}日.*");
            if(monthKeys.size()==1 && !dayExpression && !description.contains("截至")) {
                try {java.time.YearMonth month=java.time.YearMonth.parse(monthKeys.get(0));start=month.atDay(1).toString();end=month.atEndOfMonth().toString();}
                catch(java.time.DateTimeException invalid){return false;}
            } else if(monthKeys.isEmpty()) {
                java.util.regex.Matcher years=java.util.regex.Pattern.compile("(?<![0-9])((?:19|20)[0-9]{2})年").matcher(description);
                Set<String> found=new LinkedHashSet<>();while(years.find())found.add(years.group(1));
                if(found.size()==1) {String year=found.iterator().next();start=year+"-01-01";end=year+"-12-31";}
                else if(found.size()>1)return false;
            }
            // Day/as-of and multi-month legacy descriptions are left to the semantic verifier;
            // do not fabricate full-year bounds for them.

        }
        boolean hasRangeBounds = evidenceDate(actual, "startDate") != null
                || evidenceDate(actual, "endDate") != null
                || isStrictPeriodTool(actual.getToolCode());
        if (hasRangeBounds) {
            Object actualStart = evidenceDate(actual, "startDate");
            Object actualEnd = evidenceDate(actual, "endDate");
            if (!dateMatches(start, actualStart)) return false;
            if (!endDateMatches(start, end, actualStart, actualEnd)) return false;
        }
        Object actualAsOf = evidenceDate(actual, "asOfDate");
        if (expected.getAsOfDate() != null && (actualAsOf != null || isStrictPeriodTool(actual.getToolCode()))) {
            if (!dateMatches(expected.getAsOfDate(), actualAsOf)) return false;
        }
        return true;
    }

    private static boolean isStrictMetric(String expectedMetric, String toolCode) {
        return isStrictMetricTool(toolCode)
                || "settled_rent".equals(expectedMetric)
                || "cash_receipts".equals(expectedMetric)
                || "material.settled_rent_by_settlement_end".equals(expectedMetric)
                || "material.outstanding_physical_quantity".equals(expectedMetric)
                || "document.contract_review".equals(expectedMetric);
    }

    private static boolean isStrictTimeBasis(String expectedTime, String toolCode) {
        return isStrictMetricTool(toolCode)
                || "SETTLEMENT_END_DATE".equals(expectedTime)
                || "SETTLEMENT_PERIOD_END".equals(expectedTime);
    }

    private static boolean isStrictMetricTool(String toolCode) {
        return "finance.material_settled_rent".equals(toolCode)
                || "material.occupancy".equals(toolCode)
                || "document.contract_review".equals(toolCode);
    }

    private static boolean isStrictPeriodTool(String toolCode) {
        return "finance.material_settled_rent".equals(toolCode)
                || "project.reconciliation_schedule".equals(toolCode);
    }

    private static boolean projectIdsCompatible(List<String> expectedIds, AgentEvidence actual) {
        if (actual == null) return false;
        List<String> actualIds = actual.getProjectIds();
        if (actualIds == null || actualIds.isEmpty()) {
            return isTenantOrMarketScoped(actual);
        }
        Set<String> expectedSet = new HashSet<>(expectedIds);
        Set<String> actualSet = new HashSet<>(actualIds);
        if (expectedSet.equals(actualSet)) return true;
        if (actualSet.size() == 1 && expectedSet.size() > 1 && expectedSet.containsAll(actualSet)) return true;
        if (expectedSet.size() == 1 && actualSet.size() > 1 && actualSet.containsAll(expectedSet)) return true;
        return false;
    }

    private static boolean isTenantOrMarketScoped(AgentEvidence actual) {
        String scopeType = actual.getScopeType();
        if ("TENANT_INVENTORY".equals(scopeType) || "TENANT_MASTER".equals(scopeType)
                || "PUBLIC_MARKET".equals(scopeType) || "NOT_APPLICABLE".equals(scopeType)) {
            return true;
        }
        String code = actual.getToolCode() == null ? "" : actual.getToolCode();
        return code.startsWith("inventory.") || code.startsWith("master.")
                || code.startsWith("market.") || code.startsWith("governance.")
                || code.startsWith("estimate.") || "advisor.material_bcg_matrix".equals(code)
                || "advisor.sublease_arbitrage_radar".equals(code)
                || "copilot.order_fulfillment_eval".equals(code);
    }

    private static Object evidenceDate(AgentEvidence evidence,String key) {
        Map<String,Object> criteria=evidence.getCriteria()==null?Collections.emptyMap():evidence.getCriteria();
        if(criteria.get(key)!=null)return criteria.get(key);
        if(criteria.get("targetMonth")!=null && ("startDate".equals(key) || "endDate".equals(key)))try {
            java.time.YearMonth month=java.time.YearMonth.parse(String.valueOf(criteria.get("targetMonth")));
            return ("startDate".equals(key)?month.atDay(1):month.atEndOfMonth()).toString();
        } catch(java.time.DateTimeException ignored) {return null;}
        String range=evidence.getTimeRange();
        if(range!=null) {
            if("startDate".equals(key) || "endDate".equals(key)) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2})~([0-9]{4}-[0-9]{2}-[0-9]{2})").matcher(range);
                if(m.find()) return "startDate".equals(key)?m.group(1):m.group(2);
            }
            if("asOfDate".equals(key)) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("asOf=([0-9]{4}-[0-9]{2}-[0-9]{2})").matcher(range);
                if(m.find()) return m.group(1);
            }
        }
        return null;
    }

    private static boolean dateMatches(String expected,Object actual) {
        return expected==null || (actual!=null && expected.equals(String.valueOf(actual)));
    }

    private static boolean endDateMatches(String expectedStart, String expectedEnd, Object actualStartObj, Object actualEndObj) {
        if (dateMatches(expectedEnd, actualEndObj)) return true;
        if (expectedEnd == null || actualEndObj == null) return false;
        String actualEnd = String.valueOf(actualEndObj);
        String actualStart = actualStartObj == null ? null : String.valueOf(actualStartObj);
        if (expectedStart != null && actualStart != null && expectedStart.equals(actualStart)) {
            try {
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate exp = java.time.LocalDate.parse(expectedEnd);
                java.time.LocalDate act = java.time.LocalDate.parse(actualEnd);
                java.time.LocalDate start = java.time.LocalDate.parse(expectedStart);
                if ((exp.equals(today) || act.equals(today)) && exp.getYear() == act.getYear() && start.getYear() == today.getYear()) {
                    java.time.LocalDate endOfYear = java.time.LocalDate.of(today.getYear(), 12, 31);
                    java.time.LocalDate endOfMonth = java.time.YearMonth.from(today).atEndOfMonth();
                    if ((exp.equals(today) && (act.equals(endOfYear) || act.equals(endOfMonth)))
                            || (act.equals(today) && (exp.equals(endOfYear) || exp.equals(endOfMonth)))) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    static List<Map<String,Object>> outcomes(AgentV2Models.TaskSpec spec, List<AgentSkillExecution> executions) {
        List<Map<String,Object>> values = new ArrayList<>();
        if (spec == null || spec.getRequirements() == null) return values;
        for (int i=0;i<spec.getRequirements().size();i++) {
            AgentV2Models.TaskRequirement requirement=spec.getRequirements().get(i);
            if(requirement==null) continue;
            AgentV2Models.TaskSpec one=new AgentV2Models.TaskSpec();one.setRequirements(Collections.singletonList(requirement));
            boolean satisfied=missing(one,executions).isEmpty();
            List<String> refs=new ArrayList<>();
            List<AgentEvidence> availList=new ArrayList<>();
            if(executions!=null) for(AgentSkillExecution execution:executions) {
                if(execution==null || execution.getEvidence()==null || Boolean.TRUE.equals(execution.getNeedClarification()))continue;
                AgentEvidence e=execution.getEvidence();
                availList.add(e);
                if(requirement.getCapabilityCodes()!=null && requirement.getCapabilityCodes().contains(e.getToolCode())
                    && compatible(requirement,e) && matches(requirement.getCriteria(),e.getCriteria()) && e.getEvidenceId()!=null) refs.add(e.getEvidenceId());
            }
            if(refs.isEmpty()) {
                for(AgentEvidence e:fallbackMatchedEvidence(requirement, availList)) {
                    if(e.getEvidenceId()!=null && !refs.contains(e.getEvidenceId())) refs.add(e.getEvidenceId());
                }
            }
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("id",requirement.getRequirementId()==null?"r"+(i+1):requirement.getRequirementId());
            value.put("description",requirement.getDescription());value.put("status",satisfied?"SATISFIED":"UNSATISFIED");
            value.put("evidenceIds",refs);values.add(value);
        }
        return values;
    }
    /** Only results supporting an accepted user requirement may reach answer/cards/artifacts. */
    static List<AgentSkillExecution> acceptedExecutions(AgentV2Models.TaskSpec spec,
            List<AgentSkillExecution> executions, Set<String> rejected) {
        Set<String> acceptedIds=new LinkedHashSet<>();
        for(Map<String,Object> row:outcomes(spec,executions)) {
            if("SATISFIED".equals(row.get("status")) && !rejected.contains(String.valueOf(row.get("id")))) {
                for(Object id:(Collection<?>)row.get("evidenceIds")) acceptedIds.add(String.valueOf(id));
            }
        }
        List<AgentSkillExecution> accepted=new ArrayList<>();
        for(AgentSkillExecution execution:executions) {
            if(execution!=null && execution.getEvidence()!=null
                    && acceptedIds.contains(execution.getEvidence().getEvidenceId())) accepted.add(execution);
        }
        return accepted;
    }

    private static final Set<String> STRICT_CRITERIA_KEYS = new HashSet<>(
            Arrays.asList("city", "managerName", "partnerName", "guidanceRequirementId"));
    private static final Set<String> DATE_CRITERIA_KEYS = new HashSet<>(
            Arrays.asList("startDate", "endDate", "asOfDate", "targetMonth"));

    private static List<AgentEvidence> fallbackMatchedEvidence(AgentV2Models.TaskRequirement requirement, List<AgentEvidence> available) {
        if (requirement == null || available == null || available.isEmpty()) return Collections.emptyList();
        String desc = (requirement.getDescription() == null ? "" : requirement.getDescription()).trim();
        if (desc.isEmpty() || desc.contains("自动采购") || desc.contains("未支持要求") || desc.contains("未知操作")) {
            return Collections.emptyList();
        }
        List<AgentEvidence> matched = new ArrayList<>();
        for (AgentEvidence e : available) {
            if (e == null || e.getToolCode() == null || !compatible(requirement, e)) continue;
            String tool = e.getToolCode();
            if ("risk.owner_action_center".equals(tool) && containsAny(desc,
                    "行动中心", "老板", "红灯", "黄灯", "绿灯", "健康", "风险", "事实", "建议", "优先", "10个项目", "待办")) {
                matched.add(e);
            } else if ("settlement.preview_draft".equals(tool) && containsAny(desc,
                    "结算", "草稿", "预览", "不保存", "费用", "汇总", "计算依据", "租金", "赔偿", "报停")) {
                matched.add(e);
            } else if ("document.preview_draft".equals(tool) && containsAny(desc,
                    "出租", "租出", "归还", "赔偿", "租入", "退租", "建单", "开单", "预览", "引导",
                    "起租", "出库", "合同单价", "单价", "每日租金", "日租金", "租金", "装卸", "杂费", "库存", "充足")) {
                matched.add(e);
            } else if (("document.review".equals(tool) || "document.audit_list".equals(tool) || "document.list_records".equals(tool))
                    && containsAny(desc, "审核", "预检", "关注点", "不要执行", "不执行", "不修改", "租出单", "归还单", "赔偿单", "报停单")) {
                matched.add(e);
            } else if ("navigation.project_workflow".equals(tool) && containsAny(desc, "工作流", "入口", "导航", "路径")) {
                matched.add(e);
            } else if ("finance.enterprise_kpi".equals(tool) && containsAny(desc,
                    "复盘", "在租项目总数", "结算营收", "回款率", "应收未收")) {
                matched.add(e);
            }
        }
        if (matched.isEmpty() && containsAny(desc, "仅预览", "不保存正式", "不要执行任何修改", "不执行任何修改", "只读不修改")) {
            for (AgentEvidence e : available) {
                if (e != null && compatible(requirement, e)) {
                    matched.add(e);
                    break;
                }
            }
        }
        return matched;
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    static boolean criterionEquivalent(Object exp, Object act) {
        return valueMatches(exp, act);
    }

    private static boolean matches(Map<String,Object> expected,Map<String,Object> actual) {
        if(expected==null || expected.isEmpty()) return true;
        for(Map.Entry<String,Object> entry:expected.entrySet()) {
            String key=entry.getKey();
            Object exp=entry.getValue();
            if(exp==null || DATE_CRITERIA_KEYS.contains(key)) continue;
            Object value=actual==null?null:actual.get(key);
            if(value==null && actual!=null) {
                if("workflow".equals(key)) value=actual.get("documentType");
                else if("documentType".equals(key)) value=actual.get("workflow");
            }
            if(STRICT_CRITERIA_KEYS.contains(key)) {
                if(value==null || !valueMatches(exp,value)) return false;
            } else if(value!=null) {
                if(!valueMatches(exp,value) && !fuzzyCriterionMatches(key, exp, value)) return false;
            }
        }
        return true;
    }

    private static boolean fuzzyCriterionMatches(String key, Object exp, Object act) {
        if (!Arrays.asList("keyword", "materialName", "specification").contains(key)) {
            return false;
        }
        String e = String.valueOf(exp).trim().toLowerCase(Locale.ROOT);
        String a = String.valueOf(act).trim().toLowerCase(Locale.ROOT);
        return e.length() >= 2 && a.length() >= 2 && (e.contains(a) || a.contains(e));
    }

    private static boolean valueMatches(Object exp, Object act) {
        if (exp instanceof Collection<?>) {
            for (Object item : (Collection<?>) exp) {
                if (valueMatches(item, act)) return true;
            }
            return false;
        }
        if (act instanceof Collection<?>) {
            for (Object item : (Collection<?>) act) {
                if (valueMatches(exp, item)) return true;
            }
            return false;
        }
        if (exp instanceof Number && act instanceof Number) {
            return new java.math.BigDecimal(exp.toString()).compareTo(new java.math.BigDecimal(act.toString())) == 0;
        }
        if (Objects.equals(exp, act)) return true;
        String expStr = String.valueOf(exp);
        if (expStr.matches(".*[、,/|，].*")) {
            for (String part : expStr.split("[、,/|，\\s]+")) {
                if (!part.isEmpty() && valueMatches(part, act)) return true;
            }
        }
        String es = normalizeCriterionValue(expStr);
        String as = normalizeCriterionValue(String.valueOf(act));
        return es.equalsIgnoreCase(as);
    }

    private static String normalizeCriterionValue(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        switch (v) {
            case "出租":
            case "出租单":
            case "租出":
            case "租出单": return "RENT_OUT";
            case "归还":
            case "归还单": return "RETURN";
            case "赔偿":
            case "赔偿单": return "COMPENSATION";
            case "租入":
            case "租入单": return "RENT_IN";
            case "退租":
            case "退租单": return "RENT_IN_RETURN";
            case "财务对账":
            case "财务对账单":
            case "财务结算":
            case "财务结算单": return "financeCheck";
            case "物料对账":
            case "物料对账单": return "materialReconciliation";
            case "在租":
            case "进行中": return "ONGOING";
            case "已完成": return "COMPLETED";
            case "材料名称":
            case "按材料名称":
            case "材料": return "MATERIAL_NAME";
            case "材料规格":
            case "按材料规格":
            case "规格": return "MATERIAL_SPEC";
            case "项目":
            case "按项目":
            case "项目名称": return "PROJECT";
            case "数量":
            case "主数量": return "QUANTITY";
            case "单据数":
            case "次数": return "DOCUMENT_COUNT";
            default: return v;
        }
    }
}
