use zjyz;

-- Feature switches default OFF. Apply before enabling learning writes/retrieval.
CREATE TABLE IF NOT EXISTS agent_learning_entry (
 id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, cid varchar(100) NOT NULL, owner_uid varchar(100) NOT NULL,
 scope_type varchar(24) NOT NULL, project_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL, required_projects text NULL,
 kind varchar(40) NOT NULL, status varchar(32) NOT NULL, source_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version int NOT NULL DEFAULT 1, origin_thread_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL, origin_message_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 supersedes_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL, reason varchar(500) NULL, evidence_chunk_id varchar(160) NULL,
 evidence_version int NULL, created_at datetime NOT NULL, updated_at datetime NOT NULL,
 PRIMARY KEY(id), KEY idx_learning_owner(cid(64),owner_uid(64),status), KEY idx_learning_scope(cid(64),scope_type,project_id),
 KEY idx_learning_source(source_id), KEY idx_learning_parent(supersedes_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_learning_event (
 id bigint NOT NULL AUTO_INCREMENT, cid varchar(100) NOT NULL, actor_uid varchar(100) NOT NULL,
 request_key varchar(170) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 entry_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, entry_version int NOT NULL, action varchar(24) NOT NULL,
 snapshot_json mediumtext NOT NULL, created_at datetime NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_learning_request(cid(64),actor_uid(64),request_key), KEY idx_learning_event(cid(64),entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_learning_usage (
 id bigint NOT NULL AUTO_INCREMENT, cid varchar(100) NOT NULL, run_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, entry_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, entry_version int NOT NULL,
 usage_type varchar(24) NOT NULL, created_at datetime NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_learning_use(run_id,message_id,entry_id,entry_version,usage_type),
 KEY idx_learning_usage(cid(64),entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
