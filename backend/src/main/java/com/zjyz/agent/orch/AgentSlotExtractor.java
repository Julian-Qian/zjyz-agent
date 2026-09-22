package com.zjyz.agent.orch;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentSlotExtractor {
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("\\b[0-9A-Za-z]{12,}\\b");
    private static final Pattern PROJECT_NO_PATTERN = Pattern.compile("\\b\\d+(?:-[0-9A-Za-z]+)+\\b");
    private static final Pattern PROJECT_NAME_PHRASE_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_-]{2,40}项目[\\u4e00-\\u9fa5A-Za-z0-9_-]{0,20})");

    private static final Pattern BUILDING_AREA_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万)?\\s*(?:平米|平方米|平方|㎡|m2|m²|方)");
    private static final Pattern FLOOR_BELOW_PATTERN = Pattern.compile("(?:地下|负)\\s*(\\d{1,2})\\s*层");
    private static final Pattern FLOOR_ABOVE_PATTERN = Pattern.compile("(?<!地下)(?<!负)(\\d{1,2})\\s*层");
    private static final Pattern WALL_PERIMETER_PATTERN = Pattern.compile("(?:外墙周长|周长)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:米|m)");
    private static final Pattern BUILDING_HEIGHT_PATTERN = Pattern.compile("(?:建筑高度|高度)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:米|m)");
    private static final Pattern RENTAL_MONTH_PATTERN = Pattern.compile("(?:租期|租赁周期|租赁|租)\\s*(\\d+(?:\\.\\d+)?)\\s*个?月");
    private static final Pattern RENTAL_DAY_PATTERN = Pattern.compile("(?:租期|租赁周期|租赁|租)\\s*(\\d+(?:\\.\\d+)?)\\s*天");
    private static final Pattern ESTIMATE_PROJECT_NAME_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_-]{2,40}(?:一期|二期|三期|四期|五期|项目|工程|地块|标段))");

    public AgentSlotBag extract(String message, Map<String, Object> context, AgentIntentType intentType) {
        AgentSlotBag slots = new AgentSlotBag();
        slots.setProjectBusinessType(resolveProjectBusinessType(message, context));
        slots.setQueryType(resolveQueryType(message));
        slots.setOverdueRequested(containsAny(message, "超期", "逾期", "预定回收", "回收时间", "截止日", "截止日期", "到期"));
        slots.setTrendRequested(containsAny(message, "趋势", "下降", "六个月", "半年"));

        if (context != null && context.get("projectId") != null) {
            String contextProjectId = String.valueOf(context.get("projectId")).trim();
            if (StringUtils.hasText(contextProjectId)) {
                slots.setProjectId(contextProjectId);
            }
        }

        if (StringUtils.hasText(message)) {
            Matcher idMatcher = PROJECT_ID_PATTERN.matcher(message);
            if (idMatcher.find()) {
                slots.setProjectId(idMatcher.group());
            }

            Matcher noMatcher = PROJECT_NO_PATTERN.matcher(message);
            if (noMatcher.find()) {
                slots.setProjectKeyword(noMatcher.group());
            } else {
                Matcher phraseMatcher = PROJECT_NAME_PHRASE_PATTERN.matcher(message);
                if (phraseMatcher.find()) {
                    slots.setProjectKeyword(phraseMatcher.group(1));
                }
            }

            slots.setProjectOrdinal(resolveOrdinal(message));
        }

        if ((intentType == AgentIntentType.DOCUMENT_SEARCH || intentType == AgentIntentType.PROJECT_RENT_MATERIALS)
                && !StringUtils.hasText(slots.getProjectId())
                && !StringUtils.hasText(slots.getProjectKeyword())) {
            slots.setProjectKeyword(extractLooseProjectKeyword(message));
        }

        if (intentType == AgentIntentType.MATERIAL_ESTIMATE) {
            fillEstimateSlots(slots, message);
        }

        return slots;
    }

    private void fillEstimateSlots(AgentSlotBag slots, String message) {
        slots.setEstimateProjectName(extractEstimateProjectName(message));
        slots.setBuildingArea(extractBuildingArea(message));
        slots.setFloorBelow(extractInteger(FLOOR_BELOW_PATTERN, message));
        slots.setFloorAbove(extractInteger(FLOOR_ABOVE_PATTERN, message));
        slots.setWallPerimeter(extractDouble(WALL_PERIMETER_PATTERN, message));
        slots.setBuildingHeight(extractDouble(BUILDING_HEIGHT_PATTERN, message));
        slots.setRentalDays(extractRentalDays(message));
        slots.setBuildingType(resolveBuildingType(message));
        slots.setStructureType(resolveStructureType(message));
        slots.setRegion(extractRegion(message));
        slots.setSpecialRequirement(extractSpecialRequirement(message));
    }

    private Integer resolveOrdinal(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        if (containsAny(message, "第一个", "第一")) {
            return 1;
        }
        if (containsAny(message, "第二个", "第二")) {
            return 2;
        }
        if (containsAny(message, "第三个", "第三")) {
            return 3;
        }
        if (containsAny(message, "第四个", "第四")) {
            return 4;
        }
        if (containsAny(message, "第五个", "第五")) {
            return 5;
        }
        return null;
    }

    private String extractLooseProjectKeyword(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher noMatcher = PROJECT_NO_PATTERN.matcher(message);
        if (noMatcher.find()) {
            return noMatcher.group();
        }
        Matcher phraseMatcher = PROJECT_NAME_PHRASE_PATTERN.matcher(message);
        if (phraseMatcher.find()) {
            return phraseMatcher.group(1);
        }
        return null;
    }

    private String resolveProjectBusinessType(String message, Map<String, Object> context) {
        Object contextBusinessType = context == null ? null : context.get("projectBusinessType");
        if (contextBusinessType != null && "rent_in".equalsIgnoreCase(String.valueOf(contextBusinessType))) {
            return "rent_in";
        }
        if (containsAny(message, "租入", "退租", "rent in", "rent_in")) {
            return "rent_in";
        }
        return "rent_out";
    }

    private String resolveQueryType(String message) {
        if (containsAny(message, "归还")) {
            return "1";
        }
        if (containsAny(message, "赔偿")) {
            return "2";
        }
        if (containsAny(message, "采购")) {
            return "5";
        }
        if (containsAny(message, "其他入库", "入库")) {
            return "6";
        }
        if (containsAny(message, "出库")) {
            return "7";
        }
        if (containsAny(message, "物料对账")) {
            return "8";
        }
        if (containsAny(message, "财务对账", "结算")) {
            return "3";
        }
        if (containsAny(message, "租入")) {
            return "9";
        }
        if (containsAny(message, "退租")) {
            return "10";
        }
        return "0";
    }

    private String extractEstimateProjectName(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = ESTIMATE_PROJECT_NAME_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String cleaned = message.replace("帮我估算一下", "")
                .replace("帮我预估一下", "")
                .replace("做个材料预估", "")
                .replace("材料预估", "")
                .replace("估算一下", "")
                .replace("估一下", "")
                .trim();
        String[] segments = cleaned.split("[,，。；;\\n]");
        for (String segment : segments) {
            String current = segment == null ? "" : segment.trim();
            if (current.length() < 2 || current.length() > 32) {
                continue;
            }
            if (containsAny(current, "层", "方", "米", "天", "月", "住宅", "商业", "工业", "框架", "框剪", "筒体", "库存", "接单")) {
                continue;
            }
            return current;
        }
        return null;
    }

    private Double extractBuildingArea(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = BUILDING_AREA_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        double value = parseDouble(matcher.group(1));
        if (value <= 0) {
            return null;
        }
        if (StringUtils.hasText(matcher.group(2))) {
            value = value * 10000d;
        }
        return value;
    }

    private Integer extractInteger(Pattern pattern, String message) {
        if (pattern == null || !StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return (int) Math.round(parseDouble(matcher.group(1)));
    }

    private Double extractDouble(Pattern pattern, String message) {
        if (pattern == null || !StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        double value = parseDouble(matcher.group(1));
        return value > 0 ? value : null;
    }

    private Integer extractRentalDays(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher monthMatcher = RENTAL_MONTH_PATTERN.matcher(message);
        if (monthMatcher.find()) {
            double months = parseDouble(monthMatcher.group(1));
            if (months > 0) {
                return (int) Math.round(months * 30d);
            }
        }
        Matcher dayMatcher = RENTAL_DAY_PATTERN.matcher(message);
        if (dayMatcher.find()) {
            double days = parseDouble(dayMatcher.group(1));
            if (days > 0) {
                return (int) Math.round(days);
            }
        }
        return null;
    }

    private String resolveBuildingType(String message) {
        if (containsAny(message, "住宅")) {
            return "residential";
        }
        if (containsAny(message, "商业", "商住")) {
            return "commercial";
        }
        if (containsAny(message, "工业", "厂房")) {
            return "industrial";
        }
        return null;
    }

    private String resolveStructureType(String message) {
        if (containsAny(message, "框剪", "框架剪力墙")) {
            return "frame_shear";
        }
        if (containsAny(message, "筒体", "核心筒")) {
            return "tube";
        }
        if (containsAny(message, "框架")) {
            return "frame";
        }
        return null;
    }

    private String extractRegion(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String[] cities = new String[]{"南京", "苏州", "上海", "杭州", "宁波", "合肥", "无锡", "常州", "南通"};
        for (String city : cities) {
            if (message.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private String extractSpecialRequirement(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String[] keywords = new String[]{"悬挑", "异形", "高支模", "爬架", "转换层", "裙楼", "地下室", "超高层", "平台"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return message.trim();
            }
        }
        return null;
    }

    private double parseDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return 0d;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignore) {
            return 0d;
        }
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
}
