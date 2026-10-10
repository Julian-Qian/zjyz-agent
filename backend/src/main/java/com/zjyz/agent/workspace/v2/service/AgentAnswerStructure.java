package com.zjyz.agent.workspace.v2.service;

import java.util.ArrayList;
import java.util.List;

/** Safe last-mile layout shared by guidance, data queries, analysis and document answers. */
final class AgentAnswerStructure {
    private AgentAnswerStructure() {}

    static String present(String answer) {
        if(answer==null || answer.isBlank() || answer.contains("```"))return answer;
        List<String> blocks=new ArrayList<>();
        for(String block:answer.split("\\n\\s*\\n")) {
            String trimmed=block.trim();
            // Existing steps, tables and structured analysis retain their meaning and order.
            if(trimmed.contains("\n") || trimmed.startsWith("#") || trimmed.startsWith("**")
                    || trimmed.startsWith("|") || trimmed.startsWith("- ") || trimmed.matches("^\\d+[.)、].*")) {
                blocks.add(block);continue;
            }
            String[] sentences=trimmed.split("(?<=[。！？])(?=.)");
            if(sentences.length<3 || trimmed.length()<100) {blocks.add(block);continue;}
            StringBuilder formatted=new StringBuilder(sentences[0]);
            for(int i=1;i<sentences.length;i++) {
                String sentence=sentences[i].trim();
                if(!sentence.isEmpty())formatted.append(i==1 ? "\n\n- " : "\n- ").append(sentence);
            }
            blocks.add(formatted.toString());
        }
        return String.join("\n\n",blocks);
    }
}
