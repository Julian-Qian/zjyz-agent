use zjyz;

CREATE TABLE IF NOT EXISTS agent_tenant_task_usage (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cid                 VARCHAR(100) NOT NULL COMMENT '企业ID',
    month_key           VARCHAR(7)   NOT NULL COMMENT '统计月份 yyyy-MM',
    monthly_task_count  INT          NOT NULL DEFAULT 0 COMMENT '企业当月已创建Agent任务数',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tenant_task_month (cid, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent企业共享任务月用量';

INSERT INTO membership_plan_entitlement (plan_id, entitlement_key, value_type, value_text)
SELECT id, 'AI_TENANT_MONTHLY_TASK_LIMIT', 'INTEGER',
       CASE WHEN plan_code='PROFESSIONAL' THEN '1000' ELSE '300' END
FROM membership_plan p
WHERE p.plan_code IN ('STANDARD', 'PROFESSIONAL')
ON DUPLICATE KEY UPDATE
    value_type=VALUES(value_type),
    value_text=VALUES(value_text),
    updated_at=NOW();

UPDATE tenant_subscription
SET entitlement_snapshot = JSON_SET(
        JSON_REMOVE(entitlement_snapshot, '$.AI_TENANT_MONTHLY_TASK_LIMIT'),
        '$.AI_TENANT_MONTHLY_TASK_LIMIT',
        300
    ),
    updated_at = NOW()
WHERE plan_code_snapshot='STANDARD'
  AND JSON_VALID(entitlement_snapshot);

UPDATE tenant_subscription
SET entitlement_snapshot = JSON_SET(
        JSON_REMOVE(entitlement_snapshot, '$.AI_TENANT_MONTHLY_TASK_LIMIT'),
        '$.AI_TENANT_MONTHLY_TASK_LIMIT',
        1000
    ),
    updated_at = NOW()
WHERE plan_code_snapshot='PROFESSIONAL'
  AND JSON_VALID(entitlement_snapshot);

INSERT INTO agent_tenant_task_usage (cid, month_key, monthly_task_count, created_at, updated_at)
SELECT cid,
       DATE_FORMAT(created_at, '%Y-%m'),
       COUNT(*),
       MIN(created_at),
       NOW()
FROM agent_run
GROUP BY cid, DATE_FORMAT(created_at, '%Y-%m')
ON DUPLICATE KEY UPDATE
    monthly_task_count=GREATEST(monthly_task_count, VALUES(monthly_task_count)),
    updated_at=NOW();
