package com.zjyz.agent.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentTenantTaskUsageMapper {

    @Insert("INSERT IGNORE INTO agent_tenant_task_usage " +
            "(cid, month_key, monthly_task_count, created_at, updated_at) " +
            "VALUES (#{cid}, #{monthKey}, 0, NOW(), NOW())")
    int ensureMonth(@Param("cid") String cid, @Param("monthKey") String monthKey);

    @Update("UPDATE agent_tenant_task_usage " +
            "SET monthly_task_count = monthly_task_count + 1, updated_at = NOW() " +
            "WHERE cid = #{cid} AND month_key = #{monthKey} " +
            "AND monthly_task_count < #{limit}")
    int incrementIfBelowLimit(@Param("cid") String cid,
                              @Param("monthKey") String monthKey,
                              @Param("limit") int limit);

    @Update("UPDATE agent_tenant_task_usage " +
            "SET monthly_task_count = GREATEST(monthly_task_count - 1, 0), updated_at = NOW() " +
            "WHERE cid = #{cid} AND month_key = #{monthKey}")
    int decrement(@Param("cid") String cid, @Param("monthKey") String monthKey);

    @Select("SELECT monthly_task_count FROM agent_tenant_task_usage " +
            "WHERE cid = #{cid} AND month_key = #{monthKey} LIMIT 1")
    Integer selectTaskCount(@Param("cid") String cid, @Param("monthKey") String monthKey);

    default int currentTaskCount(String cid, String monthKey) {
        Integer value = selectTaskCount(cid, monthKey);
        return value == null ? 0 : Math.max(0, value);
    }
}
