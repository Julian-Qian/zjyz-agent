package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import java.util.*;
import java.util.stream.Collectors;

/** Per-run discovery over server-authorized definitions. Discovery never grants access or executes a business tool. */
final class AgentV2ToolDiscovery {
    static final String SEARCH = "agent_search_tools";
    static final String DESCRIBE = "agent_describe_tools";
    static final String BIND = "agent_bind_requirements";
    private final Map<String, Map<String,Object>> definitions = new LinkedHashMap<>();
    private final Map<String,String> codes = new LinkedHashMap<>();
    private final LinkedHashSet<String> loaded = new LinkedHashSet<>();
    private final Set<String> discovered = new LinkedHashSet<>();
    private final AgentV2Models.TaskSpec spec;
    private final ObjectMapper mapper;
    private final List<Map<String,Object>> originalCriteria = new ArrayList<>();
    private int calls;
    private boolean requirementsBound;

    AgentV2ToolDiscovery(List<Map<String,Object>> tools, java.util.function.Function<String,String> canonical,
                         AgentV2Models.TaskSpec spec, ObjectMapper mapper) {
        this.spec = spec;
        this.requirementsBound = spec.getRequirements() == null || spec.getRequirements().isEmpty();
        this.mapper = mapper;
        if (spec.getRequirements() != null) for (AgentV2Models.TaskRequirement requirement : spec.getRequirements()) {
            originalCriteria.add(requirement.getCriteria() == null ? Collections.emptyMap()
                    : mapper.convertValue(requirement.getCriteria(), Map.class));
        }
        for (Map<String,Object> tool : tools) {
            Map<String,Object> f = function(tool);
            String name = String.valueOf(f.get("name"));
            definitions.put(name, tool);
            codes.put(name, canonical.apply(name));
        }
    }

    static boolean handles(String name) { return SEARCH.equals(name) || DESCRIBE.equals(name) || BIND.equals(name); }
    boolean isBound() { return requirementsBound; }
    boolean isLoaded(String name) { return loaded.contains(name); }

    List<Map<String,Object>> tools() {
        List<Map<String,Object>> result = new ArrayList<>();
        result.add(tool(SEARCH, "发现当前授权业务能力。query填目标或业务词，空字符串列出能力；offset用于扩大搜索。无匹配不代表系统不支持，可换词或查询空字符串。",
                Map.of("query", Map.of("type","string"), "offset",Map.of("type","integer","minimum",0)), List.of("query")));
        result.add(tool(DESCRIBE, "按搜索返回的name或规范code加载完整工具定义，下轮可直接调用。一次最多8项。只能加载已授权工具。",
                Map.of("names",Map.of("type","array","items",Map.of("type","string"),"maxItems",8)), List.of("names")));
        result.add(tool(BIND, "为原始requirements逐项绑定已发现能力及已知查询条件，不能删除或改写用户要求。提交完整数组，每项index从0开始、capabilityCodes优先填规范能力码（也接受已加载工具名，由服务端规范化）、criteria为对象。不支持的要求使用空能力数组。",
                Map.of("requirements",Map.of("type","array","items",Map.of("type","object","properties",Map.of(
                        "index",Map.of("type","integer"),"capabilityCodes",Map.of("type","array","items",Map.of("type","string")),
                        "criteria",Map.of("type","object")),"required",List.of("index","capabilityCodes","criteria")))), List.of("requirements")));
        for(String name:loaded) result.add(definitions.get(name));
        return result;
    }

    Object handle(String name, String arguments) {
        if (++calls > 24) return Map.of("error","DISCOVERY_LIMIT", "message","工具发现已达本轮上限");
        try {
            Map<?,?> args = mapper.readValue(arguments, Map.class);
            if (SEARCH.equals(name)) return search(args);
            if (DESCRIBE.equals(name)) return describe(args);
            if (BIND.equals(name)) return bind(args);
            return Map.of("error","UNKNOWN_DISCOVERY_TOOL");
        } catch (Exception error) {
            return Map.of("error","INVALID_ARGUMENTS", "message","参数结构错误，请按工具定义修正后重试");
        }
    }

    private Object search(Map<?,?> args) {
        Object queryValue = args.get("query");
        if (!(queryValue instanceof String) || ((String)queryValue).length()>300) return Map.of("error","INVALID_QUERY");
        String query = ((String)queryValue).toLowerCase(Locale.ROOT).trim();
        int offset = args.get("offset") instanceof Number ? ((Number)args.get("offset")).intValue() : 0;
        if(offset<0) return Map.of("error","INVALID_OFFSET");
        Map<String,Integer> scores = new LinkedHashMap<>();
        for(String name:definitions.keySet()) {
            String text = (name+" "+codes.get(name)+" "+function(definitions.get(name)).get("description")).toLowerCase(Locale.ROOT);
            int score = query.isEmpty()?1:score(query,text);
            if(score>0) scores.put(name,score);
        }
        List<String> ordered = scores.keySet().stream().sorted(Comparator.comparingInt((String n)->scores.get(n)).reversed()).collect(Collectors.toList());
        List<Object> matches = ordered.stream().skip(offset).limit(8).map(n -> (Object)Map.of("name",n,"code",codes.get(n),
                "description",shorten(String.valueOf(function(definitions.get(n)).get("description")),180))).collect(Collectors.toList());
        return Map.of("matches",matches,"total",ordered.size(),"nextOffset",offset+matches.size()<ordered.size()?offset+matches.size():-1,
                "domains",domains(codes.values()),"hint","未命中时换用业务对象、领域词，或query为空分页浏览；再调用agent_describe_tools加载定义。");
    }

    private Object describe(Map<?,?> args) {
        if(!(args.get("names") instanceof List)) return Map.of("error","INVALID_NAMES");
        List<?> names = (List<?>)args.get("names");
        if(names.isEmpty() || names.size()>8) return Map.of("error","DESCRIBE_LIMIT");
        List<String> missing = new ArrayList<>();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for(Object value:names) {
            String resolved = value instanceof String ? resolveName((String)value) : null;
            if (resolved == null) missing.add(String.valueOf(value));
            else selected.add(resolved);
        }
        loaded.removeAll(selected);
        loaded.addAll(selected);
        discovered.addAll(selected);
        while(loaded.size()>16) loaded.remove(loaded.iterator().next());
        return Map.of("loaded",selected,"bindings",selected.stream().map(n -> Map.of("name",n,"code",codes.get(n))).collect(Collectors.toList()),"notFound",missing,"message","已加载工具定义。下一步必须先用agent_bind_requirements绑定全部原始要求，绑定前业务查询不会执行。");
    }

    private String resolveName(String value) {
        if (definitions.containsKey(value)) return value;
        for (Map.Entry<String,String> entry : codes.entrySet()) if (Objects.equals(entry.getValue(),value)) return entry.getKey();
        return null;
    }

    private Object bind(Map<?,?> args) {
        List<AgentV2Models.TaskRequirement> original = spec.getRequirements();
        if(!(args.get("requirements") instanceof List)) return Map.of("error","INVALID_REQUIREMENTS");
        List<?> bindings = (List<?>)args.get("requirements");
        if(original==null || bindings.size()!=original.size()) return Map.of("error","REQUIREMENT_COUNT_MISMATCH");
        Map<Integer,AgentV2Models.TaskRequirement> replacements = new LinkedHashMap<>();
        Set<String> discoveredCodes = discovered.stream().map(codes::get).collect(Collectors.toSet());
        for(Object item:bindings) {
            if(!(item instanceof Map)) return Map.of("error","INVALID_BINDING");
            Map<?,?> binding = (Map<?,?>)item;
            if(!(binding.get("index") instanceof Integer)) return Map.of("error","INVALID_INDEX");
            int index=(Integer)binding.get("index");
            if(index<0 || index>=original.size() || replacements.containsKey(index)) return Map.of("error","INVALID_INDEX");
            if(!(binding.get("capabilityCodes") instanceof List) || !(binding.get("criteria") instanceof Map)) return Map.of("error","INVALID_BINDING");
            List<String> required=new ArrayList<>();
            for(Object code:(List<?>)binding.get("capabilityCodes")) {
                if (!(code instanceof String)) return Map.of("error","UNDISCOVERED_CAPABILITY");
                String normalized = codes.getOrDefault((String)code,(String)code);
                if (!discoveredCodes.contains(normalized)) return Map.of("error","UNDISCOVERED_CAPABILITY");
                if(!required.contains(normalized)) required.add(normalized);
            }
            Map<String,Object> criteria=new LinkedHashMap<>();
            for(Map.Entry<?,?> e:((Map<?,?>)binding.get("criteria")).entrySet()) {
                if(!(e.getKey() instanceof String)) return Map.of("error","INVALID_CRITERIA");
                criteria.put((String)e.getKey(),e.getValue());
            }
            // Interpreter-known filters cannot be discarded by the planner.
            for(Map.Entry<String,Object> e:originalCriteria.get(index).entrySet()) {
                if(!Objects.equals(e.getValue(),criteria.get(e.getKey()))) return Map.of("error","ORIGINAL_CRITERIA_CHANGED");
            }
            // Bindings can add tool-specific criteria, never rewrite the frozen semantic contract.
            AgentV2Models.TaskRequirement next=mapper.convertValue(original.get(index),AgentV2Models.TaskRequirement.class);
            next.setCapabilityCodes(required);next.setCriteria(criteria);
            replacements.put(index,next);
        }
        List<AgentV2Models.TaskRequirement> updated=new ArrayList<>();
        for(int i=0;i<original.size();i++) updated.add(replacements.get(i));
        spec.setRequirements(updated);
        requirementsBound = true;
        return Map.of("requirements",updated,"message","要求已绑定；结束前将依据工具事实核验，空能力要求仍属于未完成");
    }

    static Map<String,Object> summary(Map<String,Object> manifest, ObjectMapper mapper, boolean includeNames) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("manifestVersion",manifest==null?"":manifest.getOrDefault("manifestVersion",""));
        result.put("discoveryMode","ON_DEMAND");
        List<Map<String,Object>> items=new ArrayList<>();List<String> allCodes=new ArrayList<>();
        if(manifest!=null && manifest.get("capabilities") instanceof List) for(Object item:(List<?>)manifest.get("capabilities")) {
            Map<?,?> cap=mapper.convertValue(item,Map.class);
            if(!Boolean.TRUE.equals(cap.get("available"))) continue;
            String code=String.valueOf(cap.get("code"));allCodes.add(code);
            if(includeNames && items.size()<100) items.add(Map.of("code",code,"name",shorten(String.valueOf(cap.get("name")),40)));
        }
        result.put("domains",domains(allCodes));
        result.put("capabilityCount",allCodes.size());
        if(includeNames) result.put("capabilities",items);
        result.put("hint","能力详情由后续工具发现提供。检索未命中不代表能力不存在，不向用户询问系统支持情况。");
        return result;
    }
    private static Map<String,Long> domains(Collection<String> codes) {
        return codes.stream().collect(Collectors.groupingBy(c->c.split("\\.")[0],LinkedHashMap::new,Collectors.counting()));
    }
    private static int score(String query,String text) {
        int score=text.contains(query)?20:0;
        for(String word:query.split("[^\\p{L}\\p{N}_]+")) {
            if(word.length()>1 && text.contains(word)) score+=5;
            if(word.matches(".*[\\p{IsHan}].*")) for(int i=0;i<word.length()-1;i++) if(text.contains(word.substring(i,i+2))) score++;
        }
        return score;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> function(Map<String,Object> d) { return (Map<String,Object>)d.get("function"); }
    private static String shorten(String v,int max) { return v.length()>max?v.substring(0,max):v; }
    private static Map<String,Object> tool(String name,String description,Map<String,Object> properties,List<String> required) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",
                Map.of("type","object","properties",properties,"required",required,"additionalProperties",false)));
    }
}
