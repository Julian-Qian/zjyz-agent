package com.zjyz.agent.orch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A narrow, complete owner-list request. Does not resolve nicknames or expand project scope. */
public final class AgentProjectOwnerQuery {
    private static final Pattern REQUEST = Pattern.compile(
            "^(?:(?:请问|请|帮我查一下|帮我查查|帮我查|查一下|目前|现在|当前)\\s*)*"
                    + "([\\p{IsHan}A-Za-z·]{1,20}?)(?:目前|现在|当前)?在?负责的?"
                    + "(?:哪些|什么|哪几个|哪几项)项目[？?。\\s]*$");

    private AgentProjectOwnerQuery() {
    }

    public static String ownerName(String message) {
        Matcher matcher = REQUEST.matcher(message == null ? "" : message.trim());
        if (!matcher.matches()) return null;
        String name = matcher.group(1);
        // A pronoun requires context or identity resolution, not a literal database name filter.
        if (name.contains("负责人") || name.startsWith("对方") || name.startsWith("我方")) return null;
        if (name.matches("我|你|他|她|我们|他们|谁|负责人|项目负责人")) return null;
        return name;
    }

    public static boolean asksAboutSystemCapability(String value) {
        if (value == null) return false;
        return value.matches("(?s).*(?:系统|工具|接口|功能|平台|小云).*(?:是否支持|能否|是否具备|是否可以|有没有能力).*?")
                || value.matches("(?s).*(?:是否支持|能否|是否具备|是否可以).*(?:筛选项目|查询项目|调用工具|调用接口).*?");
    }
}
