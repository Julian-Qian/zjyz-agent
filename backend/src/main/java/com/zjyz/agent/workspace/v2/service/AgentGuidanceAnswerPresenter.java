package com.zjyz.agent.workspace.v2.service;

import java.util.*;

/** Organize verified passages by subject; never invent summaries or alter source evidence. */
final class AgentGuidanceAnswerPresenter {
    private AgentGuidanceAnswerPresenter() {}

    static String present(List<Map<?, ?>> sources) {
        Map<String,List<String>> sections=new LinkedHashMap<>();
        Set<String> seen=new LinkedHashSet<>(), origins=new LinkedHashSet<>();
        Map<String,Set<String>> labelFamilies=new HashMap<>();
        for(Map<?, ?> source:sources) {
            String title=heading((String)source.get("title")), family=title.split("｜",2)[0];
            labelFamilies.computeIfAbsent(title.contains("｜") ? title.split("｜",2)[1] : title,key->new HashSet<>()).add(family);
            for(String paragraph:((String)source.get("quote")).split("\\n\\s*\\n"))
                if(paragraph.trim().matches("#{1,6} [^\\n]+"))
                    labelFamilies.computeIfAbsent(heading(paragraph.trim()),key->new HashSet<>()).add(family);
        }
        for(Map<?, ?> source:sources) {
            String title=heading((String)source.get("title"));
            String topic=title.contains("｜") ? title.split("｜",2)[1] : title;
            boolean added=false;
            for(String paragraph:((String)source.get("quote")).split("\\n\\s*\\n")) {
                String text=paragraph.trim();
                if(text.isEmpty())continue;
                if(text.matches("#{1,6} [^\\n]+")) {
                    String label=heading(text);
                    if(!label.equals(title))topic=label;
                    continue;
                }
                // Generated option metadata is not a business instruction.
                if(text.equals("所属功能："+title.split("｜",2)[0]+"。"))continue;
                if(heading(text).equals(title) || !seen.add(text))continue;
                String label=labelFamilies.getOrDefault(topic,Collections.emptySet()).size()>1 ? title.split("｜",2)[0]+"："+topic : topic;
                sections.computeIfAbsent(label,key->new ArrayList<>()).add(text);added=true;
            }
            if(added)origins.add("RELEASE_BUNDLED_OVERVIEW".equals(source.get("source")) ? "产品概览" : "帮助中心");
        }
        List<String> answers=new ArrayList<>();int index=1;
        if(sections.size()>1) {
            Map.Entry<String,List<String>> first=sections.entrySet().iterator().next();
            String lead=first.getValue().get(0);
            if(first.getKey().matches(".*(?:概览|总览|概述|总述|功能含义|介绍|层次).*")
                    && !lead.contains("\n") && !lead.matches("^(?:[-*]|[0-9]+[.)、]).*") && lead.length()<=600) {
                answers.add(lead);first.getValue().remove(0);
                if(first.getValue().isEmpty())sections.remove(first.getKey());
            }
        }
        for(Map.Entry<String,List<String>> section:sections.entrySet()) {
            String body=String.join("\n\n",section.getValue());
            // A short single answer needs no ornamental hierarchy.
            if(sections.size()==1 && body.length()<120) answers.add(body);
            else answers.add("**"+(sections.size()>1 ? (index++)+". " : "")+section.getKey()+"**\n\n"+body);
        }
        return String.join("\n\n",answers)+"\n\n来源："+String.join("及",origins)+"。";
    }

    private static String heading(String text) {
        return text.replaceFirst("^#{1,6}\\s+","").trim();
    }
}
