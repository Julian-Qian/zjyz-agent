use zjyz;

CREATE TABLE IF NOT EXISTS agent_workspace (
  workspace_id VARCHAR(64) NOT NULL COMMENT 'Agent工作空间ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  project_id VARCHAR(64) NOT NULL COMMENT '项目ID',
  project_business_type VARCHAR(16) NOT NULL DEFAULT 'rent_out' COMMENT 'rent_out/rent_in',
  name VARCHAR(255) NOT NULL COMMENT '工作空间名称',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
  created_by VARCHAR(100) NOT NULL COMMENT '创建用户',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (workspace_id),
  UNIQUE KEY uk_agent_workspace_project (cid, project_id),
  KEY idx_agent_workspace_status (cid, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='项目级Agent工作空间';

CREATE TABLE IF NOT EXISTS agent_thread (
  thread_id VARCHAR(64) NOT NULL COMMENT 'Agent会话线程ID',
  workspace_id VARCHAR(64) NOT NULL COMMENT '工作空间ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  owner_uid VARCHAR(100) NOT NULL COMMENT '创建用户',
  title VARCHAR(255) NOT NULL COMMENT '线程标题',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
  summary LONGTEXT DEFAULT NULL COMMENT '线程压缩摘要',
  summary_version INT NOT NULL DEFAULT 0 COMMENT '摘要版本',
  last_message_at DATETIME DEFAULT NULL COMMENT '最后消息时间',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (thread_id),
  KEY idx_agent_thread_owner (cid, owner_uid, workspace_id, status, last_message_at),
  KEY idx_agent_thread_workspace (workspace_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent私有对话线程';

CREATE TABLE IF NOT EXISTS agent_message (
  message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
  thread_id VARCHAR(64) NOT NULL COMMENT '线程ID',
  run_id VARCHAR(64) DEFAULT NULL COMMENT '关联Run ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  role VARCHAR(16) NOT NULL COMMENT 'user/assistant',
  content_type VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT '内容类型',
  content LONGTEXT NOT NULL COMMENT '消息内容',
  metadata_json LONGTEXT DEFAULT NULL COMMENT '证据、卡片等扩展信息',
  token_count INT NOT NULL DEFAULT 0 COMMENT '估算Token数',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (message_id),
  KEY idx_agent_message_thread (thread_id, created_at),
  KEY idx_agent_message_run (run_id, created_at),
  KEY idx_agent_message_cid (cid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent消息';

CREATE TABLE IF NOT EXISTS agent_run (
  run_id VARCHAR(64) NOT NULL COMMENT '执行ID',
  thread_id VARCHAR(64) NOT NULL COMMENT '线程ID',
  workspace_id VARCHAR(64) NOT NULL COMMENT '工作空间ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  owner_uid VARCHAR(100) NOT NULL COMMENT '发起用户',
  client_request_id VARCHAR(100) NOT NULL COMMENT '客户端幂等ID',
  status VARCHAR(32) NOT NULL COMMENT '执行状态',
  model_provider VARCHAR(32) DEFAULT NULL COMMENT '模型提供商',
  model_name VARCHAR(64) DEFAULT NULL COMMENT '模型名称',
  reasoning_effort VARCHAR(16) DEFAULT NULL COMMENT '推理强度',
  iteration_count INT NOT NULL DEFAULT 0 COMMENT '模型迭代次数',
  tool_call_count INT NOT NULL DEFAULT 0 COMMENT '工具调用次数',
  prompt_tokens INT NOT NULL DEFAULT 0 COMMENT '输入Token',
  completion_tokens INT NOT NULL DEFAULT 0 COMMENT '输出Token',
  estimated_cost_cny DECIMAL(14,6) NOT NULL DEFAULT 0 COMMENT '估算费用',
  started_at DATETIME DEFAULT NULL COMMENT '开始时间',
  heartbeat_at DATETIME DEFAULT NULL COMMENT '最近心跳',
  completed_at DATETIME DEFAULT NULL COMMENT '结束时间',
  error_code VARCHAR(32) DEFAULT NULL COMMENT '错误码',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '安全错误摘要',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_agent_run_client (cid, owner_uid, client_request_id),
  KEY idx_agent_run_thread (thread_id, created_at),
  KEY idx_agent_run_owner_status (cid, owner_uid, status, created_at),
  KEY idx_agent_run_workspace (workspace_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent执行';

CREATE TABLE IF NOT EXISTS agent_step (
  step_id VARCHAR(64) NOT NULL COMMENT '步骤ID',
  run_id VARCHAR(64) NOT NULL COMMENT '执行ID',
  seq_no INT NOT NULL COMMENT '步骤序号',
  step_type VARCHAR(32) NOT NULL COMMENT 'MODEL/TOOL/FINAL',
  status VARCHAR(32) NOT NULL COMMENT '步骤状态',
  summary VARCHAR(1000) DEFAULT NULL COMMENT '用户可见摘要',
  input_summary LONGTEXT DEFAULT NULL COMMENT '脱敏输入摘要',
  output_summary LONGTEXT DEFAULT NULL COMMENT '脱敏输出摘要',
  error_code VARCHAR(32) DEFAULT NULL COMMENT '错误码',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要',
  started_at DATETIME DEFAULT NULL COMMENT '开始时间',
  completed_at DATETIME DEFAULT NULL COMMENT '结束时间',
  PRIMARY KEY (step_id),
  UNIQUE KEY uk_agent_step_seq (run_id, seq_no),
  KEY idx_agent_step_run_status (run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent执行步骤';

CREATE TABLE IF NOT EXISTS agent_tool_call (
  tool_call_id VARCHAR(64) NOT NULL COMMENT '工具调用ID',
  run_id VARCHAR(64) NOT NULL COMMENT '执行ID',
  step_id VARCHAR(64) NOT NULL COMMENT '步骤ID',
  call_ref VARCHAR(100) DEFAULT NULL COMMENT '模型工具调用引用',
  tool_code VARCHAR(100) NOT NULL COMMENT '业务Tool编码',
  tool_version VARCHAR(32) NOT NULL DEFAULT '1',
  risk_level VARCHAR(32) NOT NULL COMMENT 'READ/COMPUTE等',
  arguments_json LONGTEXT DEFAULT NULL COMMENT '模型参数快照',
  arguments_hash CHAR(64) DEFAULT NULL COMMENT '参数摘要',
  status VARCHAR(32) NOT NULL COMMENT '调用状态',
  result_json LONGTEXT DEFAULT NULL COMMENT '脱敏工具结果',
  result_summary VARCHAR(2000) DEFAULT NULL COMMENT '结果摘要',
  started_at DATETIME DEFAULT NULL COMMENT '开始时间',
  completed_at DATETIME DEFAULT NULL COMMENT '结束时间',
  error_code VARCHAR(32) DEFAULT NULL COMMENT '错误码',
  PRIMARY KEY (tool_call_id),
  KEY idx_agent_tool_call_run (run_id, started_at),
  KEY idx_agent_tool_call_tool (tool_code, status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent工具调用审计';

CREATE TABLE IF NOT EXISTS agent_artifact (
  artifact_id VARCHAR(64) NOT NULL COMMENT '产物ID',
  thread_id VARCHAR(64) NOT NULL COMMENT '线程ID',
  run_id VARCHAR(64) NOT NULL COMMENT '执行ID',
  cid VARCHAR(100) NOT NULL COMMENT '企业ID',
  project_id VARCHAR(64) NOT NULL COMMENT '项目ID',
  artifact_type VARCHAR(32) NOT NULL COMMENT '产物类型',
  title VARCHAR(255) NOT NULL COMMENT '产物标题',
  mime_type VARCHAR(100) NOT NULL DEFAULT 'application/json',
  storage_type VARCHAR(16) NOT NULL DEFAULT 'DATABASE',
  object_key VARCHAR(500) DEFAULT NULL COMMENT '对象存储键',
  content_json LONGTEXT DEFAULT NULL COMMENT '结构化内容',
  checksum CHAR(64) DEFAULT NULL COMMENT '内容摘要',
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  created_by VARCHAR(100) NOT NULL COMMENT '创建用户',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (artifact_id),
  KEY idx_agent_artifact_thread (thread_id, created_at),
  KEY idx_agent_artifact_run (run_id, created_at),
  KEY idx_agent_artifact_project (cid, project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent结构化产物';

CREATE TABLE IF NOT EXISTS agent_run_event (
  event_id VARCHAR(64) NOT NULL COMMENT '事件ID',
  run_id VARCHAR(64) NOT NULL COMMENT '执行ID',
  seq_no INT NOT NULL COMMENT '事件序号',
  event_type VARCHAR(64) NOT NULL COMMENT 'SSE事件类型',
  payload_json LONGTEXT DEFAULT NULL COMMENT '事件数据',
  visible_to_user TINYINT NOT NULL DEFAULT 1 COMMENT '是否用户可见',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_agent_run_event_seq (run_id, seq_no),
  KEY idx_agent_run_event_created (run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent可重放执行事件';
