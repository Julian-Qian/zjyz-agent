use zjyz;

-- 单用户 AI 配额覆盖；未配置的用户继续使用系统默认值。
CREATE TABLE IF NOT EXISTS agent_user_quota_override (
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_key            VARCHAR(64) NOT NULL COMMENT '用户标识（uid）',
    cid                 VARCHAR(64) NOT NULL COMMENT '租户标识',
    daily_call_limit    INT         NOT NULL COMMENT '每日 AI 调用次数上限',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_user_quota (user_key, cid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单用户 AI 配额覆盖';

-- 云云云：每日 AI 调用上限 1000 次。用 uid/cid/用户名三重约束避免误配。
INSERT INTO agent_user_quota_override (user_key, cid, daily_call_limit)
SELECT uid, cid, 1000
FROM `user`
WHERE uid = 'f8d432290f1c432a9eb71b02d8932174'
  AND cid = 'a1a0bb1d4a494d7b9000d39fd48f39bf'
  AND user_name = '云云云'
ON DUPLICATE KEY UPDATE
    daily_call_limit = VALUES(daily_call_limit),
    updated_at = NOW();
