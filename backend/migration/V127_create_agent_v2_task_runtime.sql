use zjyz;

CREATE TABLE IF NOT EXISTS agent_task (
  task_id VARCHAR(64) NOT NULL COMMENT '稳定业务任务ID，与短生命周期Run分离',
  turn_id VARCHAR(64) NOT NULL COMMENT '创建本任务的对话Turn ID',
  parent_task_id VARCHAR(64) DEFAULT NULL COMMENT '继续、纠正或澄清所关联的上一任务',
  thread_id VARCHAR(64) NOT NULL COMMENT '会话线程ID',
  workspace_id VARCHAR(64) NOT NULL COMMENT '工作空间ID',
  latest_run_id VARCHAR(64) NOT NULL COMMENT '最近一次短执行Run ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  owner_uid VARCHAR(100) NOT NULL COMMENT '任务所有者',
  client_request_id VARCHAR(100) NOT NULL COMMENT '客户端幂等ID',
  request_fingerprint CHAR(64) NOT NULL COMMENT '消息与冻结范围指纹',
  status VARCHAR(32) NOT NULL COMMENT 'OPEN/READY/WAITING_USER/COMPLETED/BLOCKED/CANCELLED',
  dialogue_act VARCHAR(40) NOT NULL DEFAULT 'PENDING' COMMENT '模型结构化解释后的对话行为',
  relation_type VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'NEW/CONTINUE/CORRECT/CLARIFICATION_RESPONSE',
  goal VARCHAR(1000) DEFAULT NULL COMMENT '模型解析的业务目标摘要',
  interpretation_json LONGTEXT DEFAULT NULL COMMENT '结构化ConversationInterpretation',
  task_spec_json LONGTEXT DEFAULT NULL COMMENT '结构化TaskSpec',
  scope_json LONGTEXT NOT NULL COMMENT '提交时解析并冻结的授权项目范围',
  context_json LONGTEXT NOT NULL COMMENT '受预算且提交时冻结的会话上下文',
  capability_snapshot_json LONGTEXT NOT NULL COMMENT '本次任务可见的只读能力快照',
  version INT NOT NULL DEFAULT 1 COMMENT '任务结构版本',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  completed_at DATETIME DEFAULT NULL COMMENT '任务结束时间，等待输入时为空',
  PRIMARY KEY (task_id),
  UNIQUE KEY uk_agent_task_client (cid,owner_uid,client_request_id),
  KEY idx_agent_task_thread (cid,owner_uid,thread_id,created_at),
  KEY idx_agent_task_parent (parent_task_id,created_at),
  KEY idx_agent_task_run (latest_run_id),
  KEY idx_agent_task_status (cid,owner_uid,status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent V2持久业务任务';

CREATE TABLE IF NOT EXISTS agent_interaction (
  interaction_id VARCHAR(64) NOT NULL COMMENT '澄清或审批交互ID',
  task_id VARCHAR(64) NOT NULL COMMENT '关联业务任务ID',
  thread_id VARCHAR(64) NOT NULL COMMENT '关联会话ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  owner_uid VARCHAR(100) NOT NULL COMMENT '交互所有者',
  interaction_type VARCHAR(32) NOT NULL COMMENT 'CLARIFICATION；V2只读阶段不创建审批',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING/PROCESSING/ANSWERED/CANCELLED/EXPIRED',
  prompt_text VARCHAR(2000) NOT NULL COMMENT '用户可见问题',
  missing_fields_json LONGTEXT DEFAULT NULL COMMENT '缺失字段列表',
  options_json LONGTEXT DEFAULT NULL COMMENT '可选答案',
  answer_json LONGTEXT DEFAULT NULL COMMENT '用户回答快照',
  answer_client_request_id VARCHAR(100) DEFAULT NULL COMMENT '回答请求幂等ID',
  answered_task_id VARCHAR(64) DEFAULT NULL COMMENT '回答后创建的子Task ID',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  answered_at DATETIME DEFAULT NULL COMMENT '回答时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (interaction_id),
  KEY idx_agent_interaction_task (task_id,status,created_at),
  KEY idx_agent_interaction_owner (cid,owner_uid,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent V2人机澄清交互';

SET @agt127_db=DATABASE();

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_run' AND COLUMN_NAME='task_id')=0,
  'ALTER TABLE agent_run ADD COLUMN task_id VARCHAR(64) NULL COMMENT ''V2 Task ID'' AFTER run_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_interaction' AND COLUMN_NAME='answer_client_request_id')=0,
  'ALTER TABLE agent_interaction ADD COLUMN answer_client_request_id VARCHAR(100) NULL COMMENT ''回答请求幂等ID'' AFTER answer_json',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_interaction' AND COLUMN_NAME='answered_task_id')=0,
  'ALTER TABLE agent_interaction ADD COLUMN answered_task_id VARCHAR(64) NULL COMMENT ''回答后创建的子Task ID'' AFTER answer_client_request_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_run' AND COLUMN_NAME='turn_id')=0,
  'ALTER TABLE agent_run ADD COLUMN turn_id VARCHAR(64) NULL COMMENT ''V2 Turn ID'' AFTER task_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_run' AND COLUMN_NAME='runtime_version')=0,
  'ALTER TABLE agent_run ADD COLUMN runtime_version VARCHAR(16) NOT NULL DEFAULT ''V1'' COMMENT ''V1/V2'' AFTER turn_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_run' AND INDEX_NAME='idx_agent_run_v2_task')=0,
  'ALTER TABLE agent_run ADD KEY idx_agent_run_v2_task (task_id,runtime_version,status)',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_run' AND COLUMN_NAME='outcome_json')=0,
  'ALTER TABLE agent_run ADD COLUMN outcome_json LONGTEXT NULL COMMENT ''Run结构化终态结果'' AFTER error_message',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_message' AND COLUMN_NAME='task_id')=0,
  'ALTER TABLE agent_message ADD COLUMN task_id VARCHAR(64) NULL COMMENT ''V2 Task ID'' AFTER run_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;

SET @agt127_sql=IF((SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt127_db AND TABLE_NAME='agent_message' AND COLUMN_NAME='turn_id')=0,
  'ALTER TABLE agent_message ADD COLUMN turn_id VARCHAR(64) NULL COMMENT ''V2 Turn ID'' AFTER task_id',
  'SELECT 1');
PREPARE agt127_stmt FROM @agt127_sql;
EXECUTE agt127_stmt;
DEALLOCATE PREPARE agt127_stmt;
