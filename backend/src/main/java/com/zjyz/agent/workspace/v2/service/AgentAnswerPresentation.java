package com.zjyz.agent.workspace.v2.service;

/** Known internal diagnostics only; business data gaps and original evidence are preserved. */
final class AgentAnswerPresentation {
    private AgentAnswerPresentation() {}
    static String present(String text) {
        if (text == null) return null;
        text = text.replace("要求核验未通过，已保留查询结果。", "");
        text = text.replace("要求核验依据超出预算，已保留查询结果，尚未确认全部要求完成。", "");
        text = text.replace("要求核验额度不足，已保留查询结果，尚未确认全部要求完成。", "");
        text = text.replace("部分要求尚未通过独立证据核验，已有查询结果予以保留。", "");
        text = text.replace("以下是已查到的信息，暂时还不能完整回答你的问题。", "");
        text = text.replace("部分依据超出分析预算，已保留原始查询结果；综合分析未完成。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("暂时无法整理分析依据，已保留查询结果。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("综合分析服务暂不可用，以下保留已核验的查询结果。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("综合结论未通过依据校验，以下保留已核验的查询结果。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("分析审查尚未通过，以下仅展示已有查询事实。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("分析调用额度不足，以下保留已完成的查询结果。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("综合分析未完成，已保留可核验数据。", "暂时无法给出综合分析，以下为已查到的数据。");
        text = text.replace("没有可核验依据，尚未生成经营分析。", "目前缺少分析所需的数据，暂时无法给出经营结论。");
        text = text.replace("帮助目录超过当前单次检索预算，尚未完成指导；需要分批检索目录后才能继续。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("相关文档超过本次核验预算，尚未完成操作指导。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("使用指导检索未通过校验。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("帮助来源校验失败。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("操作说明原文引用未通过校验。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("使用指导核验未完成，请稍后重试。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("使用指导达到本次调用额度，当前要求尚未完成，已完成的其他结果仍保留。", "暂时无法提供可靠的操作步骤，请稍后重试。");
        text = text.replace("本次审阅额度不足，已保留此前完成的条款检查。", "部分合同内容尚未审阅，以下仅包含已完成的条款检查。");
        text = text.replace("部分合同审阅未完成：模型服务或输出预算限制。", "部分合同内容尚未审阅，以下仅包含已完成的条款检查。");
        text = text.replace("一批审阅结果未通过结构校验，未展示未经验证的结论。", "部分合同内容尚未审阅，以下仅包含已完成的条款检查。");
        text = text.replace("跨批综合核验额度不足，已保留此前完成的条款检查。", "尚未完成不同段落之间的条款对照。");
        text = text.replace("跨批条款综合核验未完成。", "尚未完成不同段落之间的条款对照。");
        text = text.replace("文件超过本次12批审阅预算，后续正文尚未审阅。", "文件较长，后续正文尚未审阅。");
        text = text.replace("本次结果为部分内容或受限审阅，不能作为整份合同已完整核验的结论。", "本次仅审阅了部分内容，不能据此判断整份合同的风险。");
        text = text.replace("任务要求尚未完整提取，无法确认完成。", "暂时无法完整回答，请补充你希望查询的对象和范围。");
        text = text.replace("这次查询尚未完成，暂时没有可核验的结果。请重试，或补充具体查询对象。", "暂时没有查到可用结果，请稍后重试，或补充具体查询对象。");
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }
}
