package com.zjyz.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjyz.agent.model.AgentUsageRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentUsageRecordMapper extends BaseMapper<AgentUsageRecordEntity> {

    /**
     * 查询指定用户某月的使用记录，不存在返回 null
     */
    @Select("SELECT * FROM agent_usage_record WHERE user_key = #{userKey} AND cid = #{cid} AND month_key = #{monthKey} LIMIT 1")
    AgentUsageRecordEntity selectByUserMonth(@Param("userKey") String userKey,
                                             @Param("cid") String cid,
                                             @Param("monthKey") String monthKey);

    /**
     * 查询单用户每日调用上限覆盖值；未配置时返回 null。
     */
    @Select("SELECT daily_call_limit FROM agent_user_quota_override " +
            "WHERE user_key = #{userKey} AND cid = #{cid} LIMIT 1")
    Integer selectDailyCallLimitOverride(@Param("userKey") String userKey,
                                         @Param("cid") String cid);

    /**
     * 查询单用户月调用次数是否不限量；未配置时返回 null。
     */
    @Select("SELECT unlimited_monthly_calls_flag FROM agent_user_quota_override " +
            "WHERE user_key = #{userKey} AND cid = #{cid} LIMIT 1")
    Integer selectMonthlyCallUnlimitedOverride(@Param("userKey") String userKey,
                                                @Param("cid") String cid);

    /**
     * 查询单用户月输入 Token 是否不限量；未配置时返回 null。
     */
    @Select("SELECT unlimited_prompt_tokens_flag FROM agent_user_quota_override " +
            "WHERE user_key = #{userKey} AND cid = #{cid} LIMIT 1")
    Integer selectMonthlyPromptTokenUnlimitedOverride(@Param("userKey") String userKey,
                                                       @Param("cid") String cid);

    /**
     * 原子递增当日调用次数（跨日自动重置为1）
     */
    @Update("UPDATE agent_usage_record " +
            "SET daily_calls = IF(day_key = #{dayKey}, daily_calls + 1, 1), " +
            "    day_key = #{dayKey}, " +
            "    monthly_calls = monthly_calls + 1, " +
            "    monthly_prompt_tokens = monthly_prompt_tokens + #{promptTokens}, " +
            "    monthly_completion_tokens = monthly_completion_tokens + #{completionTokens}, " +
            "    monthly_premium_calls = monthly_premium_calls + #{premiumCalls}, " +
            "    monthly_cost_cny = monthly_cost_cny + #{costCny}, " +
            "    updated_at = NOW() " +
            "WHERE user_key = #{userKey} AND cid = #{cid} AND month_key = #{monthKey}")
    int incrementUsage(@Param("userKey") String userKey,
                       @Param("cid") String cid,
                       @Param("monthKey") String monthKey,
                       @Param("dayKey") String dayKey,
                       @Param("promptTokens") int promptTokens,
                       @Param("completionTokens") int completionTokens,
                       @Param("premiumCalls") int premiumCalls,
                       @Param("costCny") double costCny);

    /**
     * 仅累计模型 Token 与成本，不把模型内部迭代计为新的业务任务。
     */
    @Update("UPDATE agent_usage_record " +
            "SET monthly_prompt_tokens = monthly_prompt_tokens + #{promptTokens}, " +
            "    monthly_completion_tokens = monthly_completion_tokens + #{completionTokens}, " +
            "    monthly_cost_cny = monthly_cost_cny + #{costCny}, " +
            "    updated_at = NOW() " +
            "WHERE user_key = #{userKey} AND cid = #{cid} AND month_key = #{monthKey}")
    int incrementModelUsage(@Param("userKey") String userKey,
                            @Param("cid") String cid,
                            @Param("monthKey") String monthKey,
                            @Param("promptTokens") int promptTokens,
                            @Param("completionTokens") int completionTokens,
                            @Param("costCny") double costCny);

    /**
     * 查询租户当月总费用（汇总所有用户）
     */
    @Select("SELECT COALESCE(SUM(monthly_cost_cny), 0) FROM agent_usage_record WHERE cid = #{cid} AND month_key = #{monthKey}")
    double sumTenantMonthlyCost(@Param("cid") String cid, @Param("monthKey") String monthKey);
}
