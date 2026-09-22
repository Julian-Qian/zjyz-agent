use zjyz;

INSERT INTO membership_plan_entitlement (plan_id, entitlement_key, value_type, value_text)
SELECT id, 'AI_TENANT_MONTHLY_TASK_LIMIT', 'INTEGER', '300'
FROM membership_plan p
WHERE p.plan_code = 'STANDARD'
  AND NOT EXISTS (
    SELECT 1
    FROM membership_plan_entitlement x
    WHERE x.plan_id = p.id
      AND x.entitlement_key = 'AI_TENANT_MONTHLY_TASK_LIMIT'
  );

INSERT INTO membership_plan_entitlement (plan_id, entitlement_key, value_type, value_text)
SELECT id, 'AI_TENANT_MONTHLY_TASK_LIMIT', 'INTEGER', '1000'
FROM membership_plan p
WHERE p.plan_code = 'PROFESSIONAL'
  AND NOT EXISTS (
    SELECT 1
    FROM membership_plan_entitlement x
    WHERE x.plan_id = p.id
      AND x.entitlement_key = 'AI_TENANT_MONTHLY_TASK_LIMIT'
  );
