package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;

/** Per-run read identity. Array order and values are preserved; only object key order is normalized. */
final class AgentQueryReuse {
    private AgentQueryReuse() {}
    static String key(String code, String arguments, ObjectMapper json) {
        try {
            JsonNode node=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(arguments == null || arguments.trim().isEmpty() ? "{}" : arguments);
            if(node == null || !node.isObject()) return null;
            return code + "\n" + canonical(node,json).toString();
        } catch(Exception invalid) { return null; }
    }
    private static JsonNode canonical(JsonNode node,ObjectMapper json) {
        if(node.isObject()) {
            ObjectNode out=json.createObjectNode();List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);for(String key:keys)out.set(key,canonical(node.get(key),json));return out;
        }
        if(node.isArray()) {ArrayNode out=json.createArrayNode();node.forEach(item->out.add(canonical(item,json)));return out;}
        return node;
    }
}
