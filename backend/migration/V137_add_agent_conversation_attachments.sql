use zjyz;

CREATE TABLE IF NOT EXISTS agent_attachment (
  attachment_id varchar(64) NOT NULL PRIMARY KEY,
  cid varchar(64) NOT NULL, owner_uid varchar(64) NOT NULL, thread_id varchar(64) NOT NULL,
  client_request_id varchar(100) NOT NULL, checksum varchar(64) NOT NULL,
  filename varchar(255) NOT NULL, mime_type varchar(150) NOT NULL, object_key varchar(500) NOT NULL,
  byte_size bigint NOT NULL, version int NOT NULL DEFAULT 1, parse_revision int NOT NULL DEFAULT 1,
  row_version int NOT NULL DEFAULT 1, status varchar(24) NOT NULL,
  created_at datetime NOT NULL, expires_at datetime NOT NULL,
  UNIQUE KEY uk_attachment_request(cid,owner_uid,client_request_id),
  KEY idx_attachment_thread(cid,owner_uid,thread_id), KEY idx_attachment_expiry(status,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_attachment_parse_job (
  job_id varchar(64) NOT NULL PRIMARY KEY, attachment_id varchar(64) NOT NULL,
  revision int NOT NULL, status varchar(24) NOT NULL, lease_token varchar(64) DEFAULT NULL,
  attempts int NOT NULL DEFAULT 0, result_json longtext, error_code varchar(64) DEFAULT NULL,
  updated_at datetime NOT NULL,
  UNIQUE KEY uk_attachment_parse_revision(attachment_id,revision), KEY idx_attachment_job(status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
