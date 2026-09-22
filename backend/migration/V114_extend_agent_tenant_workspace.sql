use zjyz;

-- Agent 企业级工作空间：兼容已有项目级数据，支持跨项目只读财务 Skill。
-- 使用 INFORMATION_SCHEMA + 动态 SQL，兼容 MySQL 5.7 且可重复执行。
SET @agt114_db := DATABASE();

SET @agt114_exists := (
  SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND COLUMN_NAME='scope_type'
);
SET @agt114_sql := IF(@agt114_exists=0,
  'ALTER TABLE agent_workspace ADD COLUMN scope_type VARCHAR(16) NOT NULL DEFAULT ''PROJECT'' COMMENT ''PROJECT/TENANT'' AFTER cid',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_exists := (
  SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND COLUMN_NAME='scope_key'
);
SET @agt114_sql := IF(@agt114_exists=0,
  'ALTER TABLE agent_workspace ADD COLUMN scope_key VARCHAR(100) NULL COMMENT ''范围唯一键：项目ID或TENANT'' AFTER scope_type',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

UPDATE agent_workspace
SET scope_type='PROJECT', scope_key=project_id
WHERE scope_key IS NULL OR scope_key='';

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND COLUMN_NAME='scope_key' AND IS_NULLABLE='YES')=1,
  'ALTER TABLE agent_workspace MODIFY COLUMN scope_key VARCHAR(100) NOT NULL COMMENT ''范围唯一键：项目ID或TENANT''',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND COLUMN_NAME='project_id' AND IS_NULLABLE='NO')=1,
  'ALTER TABLE agent_workspace MODIFY COLUMN project_id VARCHAR(64) NULL COMMENT ''项目级范围的项目ID，企业级为空''',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND INDEX_NAME='uk_agent_workspace_project')>0,
  'ALTER TABLE agent_workspace DROP INDEX uk_agent_workspace_project',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_workspace' AND INDEX_NAME='uk_agent_workspace_scope')=0,
  'ALTER TABLE agent_workspace ADD UNIQUE KEY uk_agent_workspace_scope (cid,scope_type,scope_key)',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_exists := (
  SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_artifact' AND COLUMN_NAME='workspace_id'
);
SET @agt114_sql := IF(@agt114_exists=0,
  'ALTER TABLE agent_artifact ADD COLUMN workspace_id VARCHAR(64) NULL COMMENT ''工作空间ID'' AFTER run_id',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

UPDATE agent_artifact artifact
JOIN agent_run run_record ON run_record.run_id=artifact.run_id
SET artifact.workspace_id=run_record.workspace_id
WHERE artifact.workspace_id IS NULL OR artifact.workspace_id='';

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_artifact' AND COLUMN_NAME='project_id' AND IS_NULLABLE='NO')=1,
  'ALTER TABLE agent_artifact MODIFY COLUMN project_id VARCHAR(64) NULL COMMENT ''项目级产物的项目ID，企业级为空''',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;

SET @agt114_sql := IF(
  (SELECT COUNT(1) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=@agt114_db AND TABLE_NAME='agent_artifact' AND INDEX_NAME='idx_agent_artifact_workspace')=0,
  'ALTER TABLE agent_artifact ADD KEY idx_agent_artifact_workspace (cid,workspace_id,created_at)',
  'SELECT 1');
PREPARE agt114_stmt FROM @agt114_sql; EXECUTE agt114_stmt; DEALLOCATE PREPARE agt114_stmt;
