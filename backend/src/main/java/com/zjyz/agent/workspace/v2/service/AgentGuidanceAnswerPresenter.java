package com.zjyz.agent.workspace.v2.service;

import java.util.*;

/** Merge verified passages across requirements without changing evidence or business facts. */
final class AgentGuidanceAnswerPresenter {
    private AgentGuidanceAnswerPresenter() {}

    static String present(List<Map<?, ?>> sources) {
        Map<String,List<String>> sections=new LinkedHashMap<>();
        Set<String> seen=new LinkedHashSet<>();
        Set<String> origins=new LinkedHashSet<>();
        for(Map<?, ?> source:sources) {
            String title=heading((String)source.get("title"));
            List<String> additions=new ArrayList<>();
            for(String paragraph:((String)source.get("quote")).split("\\n\\s*\\n")) {
                String text=paragraph.trim();
                if(text.isEmpty() || heading(text).equals(title) || !seen.add(text)) continue;
                additions.add(text);
            }
            if(!additions.isEmpty()) {
                sections.computeIfAbsent(title,key->new ArrayList<>()).addAll(additions);
                origins.add("RELEASE_BUNDLED_OVERVIEW".equals(source.get("source")) ? "产品概览" : "帮助中心");
            }
        }
        List<String> answers=new ArrayList<>();
        sections.forEach((title,paragraphs)->answers.add(title+"\n\n"+String.join("\n\n",paragraphs)));
        return String.join("\n\n",answers)+"\n\n来源："+String.join("及",origins)+"。";
    }

    private static String heading(String text) {
        return text.replaceFirst("^#{1,6}\\s+","").trim();
    }
}
