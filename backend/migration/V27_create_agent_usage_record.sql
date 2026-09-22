use zjyz;

-- V27: 创建 Agent 使用量统计表（持久化配额计数，替换原先的内存 ConcurrentHashMap）
-- 每个用户每月一行，通过唯一索引 (user_key, cid, month_key) 保证幂等性

CREATE TABLE IF NOT EXISTS agent_usage_record (
    id                       BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_key                 VARCHAR(64)     NOT NULL COMMENT '用户标识（uid）',
    cid                      VARCHAR(64)     NOT NULL COMMENT '租户标识',
    day_key                  VARCHAR(10)     NOT NULL COMMENT '最后记录日期 yyyy-MM-dd（用于每日次数重置）',
    month_key                VARCHAR(7)      NOT NULL COMMENT '统计月份 yyyy-MM',
    daily_calls              INT             NOT NULL DEFAULT 0 COMMENT '当日调用次数（跨日自动重置）',
    monthly_calls            INT             NOT NULL DEFAULT 0 COMMENT '当月总调用次数',
    monthly_prompt_tokens    INT             NOT NULL DEFAULT 0 COMMENT '当月输入 token 总量',
    monthly_completion_tokens INT            NOT NULL DEFAULT 0 COMMENT '当月输出 token 总量',
    monthly_premium_calls    INT             NOT NULL DEFAULT 0 COMMENT '当月高级模型调用次数',
    monthly_cost_cny         DECIMAL(12, 4)  NOT NULL DEFAULT 0.0000 COMMENT '当月累计费用（元）',
    created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_month (user_key, cid, month_key),
    INDEX idx_cid_month (cid, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 使用量统计表（每用户每月一行）';
