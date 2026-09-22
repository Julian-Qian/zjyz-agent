package com.zjyz.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 使用量统计记录（每用户每月一行）
 */
@Data
@TableName("agent_usage_record")
public class AgentUsageRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户标识（uid） */
    private String userKey;

    /** 租户标识（cid） */
    private String cid;

    /** 统计日期 yyyy-MM-dd（用于每日调用次数重置） */
    private String dayKey;

    /** 统计月份 yyyy-MM */
    private String monthKey;

    /** 当日调用次数 */
    private Integer dailyCalls;

    /** 当月总调用次数 */
    private Integer monthlyCalls;

    /** 当月输入 token 总量 */
    private Integer monthlyPromptTokens;

    /** 当月输出 token 总量 */
    private Integer monthlyCompletionTokens;

    /** 当月高级模型调用次数 */
    private Integer monthlyPremiumCalls;

    /** 当月累计费用（元） */
    private BigDecimal monthlyCostCny;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
