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
            if(codes==null || codes.isEmpty() || matched.size()!=new LinkedHashSet<>(codes).size()) {
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
        if ("PARTIAL".equals(completeness) || "UNKNOWN".equals(completeness)) return false;
        if (Boolean.TRUE.equals(expected.getRequireComplete()) && !"COMPLETE".equals(completeness)) return false;
        Object metric=actual.getMetricId()!=null?actual.getMetricId():actual.getCriteria()==null?null:actual.getCriteria().get("metricId");
        Object time=actual.getTimeBasis()!=null?actual.getTimeBasis():actual.getCriteria()==null?null:actual.getCriteria().getOrDefault("timeBasis",actual.getCriteria().get("dateBasis"));
        if (expected.getMetricId() != null && !Objects.equals(expected.getMetricId(), metric)) return false;
        if(expected.getTimeBasis()!=null && !Objects.equals(expected.getTimeBasis(),time))return false;
        if(expected.getProjectIds()!=null && !expected.getProjectIds().isEmpty()
                && (actual.getProjectIds()==null || !new HashSet<>(expected.getProjectIds()).equals(new HashSet<>(actual.getProjectIds()))))return false;
        if("help.search".equals(actual.getToolCode()) || "document.contract_review".equals(actual.getToolCode()))return true;
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
        return dateMatches(start,evidenceDate(actual,"startDate")) && dateMatches(end,evidenceDate(actual,"endDate"))
            && dateMatches(expected.getAsOfDate(),criteria.get("asOfDate"));
    }

    private static Object evidenceDate(AgentEvidence evidence,String key) {
        Map<String,Object> criteria=evidence.getCriteria()==null?Collections.emptyMap():evidence.getCriteria();
        if(criteria.get(key)!=null)return criteria.get(key);
        if(criteria.get("targetMonth")!=null)try {
            java.time.YearMonth month=java.time.YearMonth.parse(String.valueOf(criteria.get("targetMonth")));
            return ("startDate".equals(key)?month.atDay(1):month.atEndOfMonth()).toString();
        } catch(java.time.DateTimeException ignored) {return null;}
        String range=evidence.getTimeRange();
        if(range!=null && range.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}~[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            return range.split("~")["startDate".equals(key)?0:1];
        return null;
    }

    private static boolean dateMatches(String expected,Object actual) {
        return expected==null || (actual!=null && expected.equals(String.valueOf(actual)));
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
            if(executions!=null) for(AgentSkillExecution execution:executions) {
                if(execution==null || execution.getEvidence()==null || Boolean.TRUE.equals(execution.getNeedClarification()))continue;
                AgentEvidence e=execution.getEvidence();
                if(requirement.getCapabilityCodes()!=null && requirement.getCapabilityCodes().contains(e.getToolCode())
                    && compatible(requirement,e) && matches(requirement.getCriteria(),e.getCriteria()) && e.getEvidenceId()!=null) refs.add(e.getEvidenceId());
            }
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("id",requirement.getRequirementId()==null?"r"+(i+1):requirement.getRequirementId());
            value.put("description",requirement.getDescription());value.put("status",satisfied?"SATISFIED":"UNSATISFIED");
            value.put("evidenceIds",refs);values.add(value);
        }
        return values;
    }
    private static boolean matches(Map<String,Object> expected,Map<String,Object> actual) {
        if(expected==null || expected.isEmpty()) return true;
        if(actual==null) return false;
        for(Map.Entry<String,Object> entry:expected.entrySet()) {
            Object value=actual.get(entry.getKey());
            if(entry.getValue() instanceof Number && value instanceof Number) {
                if(new java.math.BigDecimal(entry.getValue().toString()).compareTo(new java.math.BigDecimal(value.toString()))!=0) return false;
            } else if(!Objects.equals(entry.getValue(),value)) return false;
        }
        return true;
    }
}
