CREATE TABLE IF NOT EXISTS SPRING_SESSION (
  PRIMARY_ID CHAR(36) NOT NULL,
  SESSION_ID CHAR(36) NOT NULL,
  CREATION_TIME BIGINT NOT NULL,
  LAST_ACCESS_TIME BIGINT NOT NULL,
  MAX_INACTIVE_INTERVAL INT NOT NULL,
  EXPIRY_TIME BIGINT NOT NULL,
  PRINCIPAL_NAME VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES BYTEA NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
    REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS auth_audit_events (
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(255) NOT NULL,
  actor VARCHAR(255),
  outcome VARCHAR(64) NOT NULL,
  ip_address VARCHAR(255),
  details_json TEXT NOT NULL,
  event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT auth_audit_events_pk PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS auth_audit_events_timestamp_idx ON auth_audit_events (event_timestamp);
CREATE INDEX IF NOT EXISTS auth_audit_events_actor_timestamp_idx ON auth_audit_events (actor, event_timestamp);
CREATE INDEX IF NOT EXISTS auth_audit_events_type_timestamp_idx ON auth_audit_events (event_type, event_timestamp);

CREATE TABLE IF NOT EXISTS snippets (
  snippet_id VARCHAR(36) NOT NULL,
  owner VARCHAR(255) NOT NULL,
  group_id VARCHAR(255),
  title VARCHAR(255) NOT NULL,
  sql_text TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT snippets_pk PRIMARY KEY (snippet_id)
);

CREATE INDEX IF NOT EXISTS snippets_owner_updated_idx ON snippets (owner, updated_at);
CREATE INDEX IF NOT EXISTS snippets_group_updated_idx ON snippets (group_id, updated_at);

CREATE TABLE IF NOT EXISTS resource_scripts (
  resource_id VARCHAR(36) NOT NULL,
  owner VARCHAR(255) NOT NULL,
  title VARCHAR(255) NOT NULL,
  sql_text TEXT NOT NULL,
  scope VARCHAR(32) NOT NULL,
  group_id VARCHAR(255),
  folder_path VARCHAR(512) NOT NULL,
  datasource_id VARCHAR(255),
  tags_json TEXT NOT NULL,
  allow_group_edit BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  current_revision INT NOT NULL,
  CONSTRAINT resource_scripts_pk PRIMARY KEY (resource_id)
);

CREATE INDEX IF NOT EXISTS resource_scripts_owner_updated_idx ON resource_scripts (owner, updated_at);
CREATE INDEX IF NOT EXISTS resource_scripts_group_updated_idx ON resource_scripts (group_id, updated_at);

CREATE TABLE IF NOT EXISTS resource_script_versions (
  version_id VARCHAR(36) NOT NULL,
  resource_id VARCHAR(36) NOT NULL,
  revision INT NOT NULL,
  title VARCHAR(255) NOT NULL,
  sql_text TEXT NOT NULL,
  scope VARCHAR(32) NOT NULL,
  group_id VARCHAR(255),
  folder_path VARCHAR(512) NOT NULL,
  datasource_id VARCHAR(255),
  tags_json TEXT NOT NULL,
  allow_group_edit BOOLEAN NOT NULL,
  action VARCHAR(64) NOT NULL,
  saved_by VARCHAR(255) NOT NULL,
  saved_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT resource_script_versions_pk PRIMARY KEY (version_id)
);

CREATE INDEX IF NOT EXISTS resource_script_versions_resource_revision_idx ON resource_script_versions (resource_id, revision);
CREATE INDEX IF NOT EXISTS resource_script_versions_resource_saved_idx ON resource_script_versions (resource_id, saved_at);

CREATE TABLE IF NOT EXISTS app_users (
  username VARCHAR(255) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  email VARCHAR(320),
  password_hash TEXT,
  provider VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL,
  temporary_password BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_seen_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT app_users_pk PRIMARY KEY (username)
);

CREATE TABLE IF NOT EXISTS app_user_roles (
  username VARCHAR(255) NOT NULL,
  role_name VARCHAR(128) NOT NULL,
  CONSTRAINT app_user_roles_pk PRIMARY KEY (username, role_name)
);

CREATE TABLE IF NOT EXISTS app_user_groups (
  username VARCHAR(255) NOT NULL,
  group_id VARCHAR(255) NOT NULL,
  CONSTRAINT app_user_groups_pk PRIMARY KEY (username, group_id)
);

CREATE TABLE IF NOT EXISTS rbac_groups (
  group_id VARCHAR(255) NOT NULL,
  group_name VARCHAR(255) NOT NULL,
  description TEXT,
  CONSTRAINT rbac_groups_pk PRIMARY KEY (group_id)
);

CREATE TABLE IF NOT EXISTS rbac_group_members (
  group_id VARCHAR(255) NOT NULL,
  username VARCHAR(255) NOT NULL,
  CONSTRAINT rbac_group_members_pk PRIMARY KEY (group_id, username)
);

CREATE TABLE IF NOT EXISTS rbac_datasource_access (
  group_id VARCHAR(255) NOT NULL,
  datasource_id VARCHAR(255) NOT NULL,
  can_query BOOLEAN NOT NULL,
  can_export BOOLEAN NOT NULL,
  read_only BOOLEAN NOT NULL,
  max_rows_per_query INT,
  max_runtime_seconds INT,
  concurrency_limit INT,
  credential_profile VARCHAR(255) NOT NULL,
  CONSTRAINT rbac_datasource_access_pk PRIMARY KEY (group_id, datasource_id)
);

CREATE INDEX IF NOT EXISTS rbac_datasource_access_datasource_idx ON rbac_datasource_access (datasource_id);

CREATE TABLE IF NOT EXISTS managed_datasources (
  datasource_id VARCHAR(255) NOT NULL,
  datasource_name VARCHAR(255) NOT NULL,
  engine VARCHAR(64) NOT NULL,
  host VARCHAR(512) NOT NULL,
  port INT NOT NULL,
  datasource_database VARCHAR(255),
  driver_id VARCHAR(255) NOT NULL,
  driver_class VARCHAR(512) NOT NULL,
  pool_json TEXT NOT NULL,
  tls_json TEXT NOT NULL,
  options_json TEXT NOT NULL,
  source VARCHAR(32) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT managed_datasources_pk PRIMARY KEY (datasource_id)
);

CREATE TABLE IF NOT EXISTS managed_credential_profiles (
  datasource_id VARCHAR(255) NOT NULL,
  profile_id VARCHAR(255) NOT NULL,
  username VARCHAR(255) NOT NULL,
  description TEXT,
  sysadmin BOOLEAN NOT NULL,
  encryption_key_id VARCHAR(255) NOT NULL,
  encrypted_credential TEXT NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT managed_credential_profiles_pk PRIMARY KEY (datasource_id, profile_id)
);

CREATE TABLE IF NOT EXISTS datasource_pause_state (
  datasource_id VARCHAR(255) NOT NULL,
  paused_by VARCHAR(255),
  reason TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT datasource_pause_state_pk PRIMARY KEY (datasource_id)
);

CREATE TABLE IF NOT EXISTS uploaded_jdbc_drivers (
  driver_id VARCHAR(255) NOT NULL,
  engine VARCHAR(64) NOT NULL,
  driver_class VARCHAR(512) NOT NULL,
  description TEXT NOT NULL,
  jar_path TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uploaded_jdbc_drivers_pk PRIMARY KEY (driver_id)
);

CREATE TABLE IF NOT EXISTS query_history (
  execution_id VARCHAR(36) NOT NULL,
  actor VARCHAR(255) NOT NULL,
  datasource_id VARCHAR(255) NOT NULL,
  credential_profile VARCHAR(255) NOT NULL,
  query_hash VARCHAR(128) NOT NULL,
  query_text TEXT,
  query_text_redacted BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL,
  message TEXT NOT NULL,
  error_summary TEXT,
  row_count INT NOT NULL,
  column_count INT NOT NULL,
  row_limit_reached BOOLEAN NOT NULL,
  max_rows_per_query INT NOT NULL,
  max_runtime_seconds INT NOT NULL,
  submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT query_history_pk PRIMARY KEY (execution_id)
);

CREATE INDEX IF NOT EXISTS query_history_actor_submitted_idx ON query_history (actor, submitted_at);
CREATE INDEX IF NOT EXISTS query_history_datasource_submitted_idx ON query_history (datasource_id, submitted_at);
CREATE INDEX IF NOT EXISTS query_history_status_submitted_idx ON query_history (status, submitted_at);
CREATE INDEX IF NOT EXISTS query_history_completed_submitted_idx ON query_history (completed_at, submitted_at);

CREATE TABLE IF NOT EXISTS query_runtime_executions (
  execution_id VARCHAR(36) NOT NULL,
  actor VARCHAR(255) NOT NULL,
  ip_address VARCHAR(255),
  datasource_id VARCHAR(255) NOT NULL,
  credential_profile VARCHAR(255) NOT NULL,
  query_hash VARCHAR(128) NOT NULL,
  sql_text TEXT,
  sql_text_redacted BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL,
  message TEXT NOT NULL,
  error_summary TEXT,
  row_count INT NOT NULL,
  column_count INT NOT NULL,
  row_limit_reached BOOLEAN NOT NULL,
  max_rows_per_query INT NOT NULL,
  max_runtime_seconds INT NOT NULL,
  concurrency_limit INT NOT NULL,
  script_statement_count INT NOT NULL,
  script_stop_on_error BOOLEAN NOT NULL,
  script_transaction_mode VARCHAR(32) NOT NULL,
  script_statements_json TEXT NOT NULL,
  columns_json TEXT NOT NULL,
  rows_json TEXT NOT NULL,
  submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  results_expired BOOLEAN NOT NULL,
  cancel_requested BOOLEAN NOT NULL,
  owner_instance_id VARCHAR(255) NOT NULL,
  heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT query_runtime_executions_pk PRIMARY KEY (execution_id)
);

CREATE INDEX IF NOT EXISTS query_runtime_executions_active_idx ON query_runtime_executions (status, datasource_id, submitted_at);
CREATE INDEX IF NOT EXISTS query_runtime_executions_actor_active_idx ON query_runtime_executions (actor, status, submitted_at);
CREATE INDEX IF NOT EXISTS query_runtime_executions_heartbeat_idx ON query_runtime_executions (heartbeat_at);

CREATE TABLE IF NOT EXISTS datasource_pool_metrics (
  instance_id VARCHAR(255) NOT NULL,
  pool_key VARCHAR(512) NOT NULL,
  datasource_id VARCHAR(255) NOT NULL,
  credential_profile VARCHAR(255) NOT NULL,
  active_connections INT NOT NULL,
  idle_connections INT NOT NULL,
  total_connections INT NOT NULL,
  maximum_pool_size INT NOT NULL,
  threads_awaiting_connection INT NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT datasource_pool_metrics_pk PRIMARY KEY (instance_id, pool_key)
);

CREATE INDEX IF NOT EXISTS datasource_pool_metrics_datasource_idx ON datasource_pool_metrics (datasource_id, updated_at);
CREATE INDEX IF NOT EXISTS datasource_pool_metrics_updated_idx ON datasource_pool_metrics (updated_at);
