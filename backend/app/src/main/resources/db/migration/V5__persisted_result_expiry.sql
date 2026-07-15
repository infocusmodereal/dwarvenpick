CREATE INDEX IF NOT EXISTS query_runtime_executions_result_expiry_idx
  ON query_runtime_executions (results_expired, status, last_accessed_at);

CREATE TABLE IF NOT EXISTS query_runtime_result_export_leases (
  execution_id VARCHAR(36) NOT NULL,
  lease_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT query_runtime_result_export_leases_pk PRIMARY KEY (execution_id, lease_id),
  CONSTRAINT query_runtime_result_export_leases_execution_fk FOREIGN KEY (execution_id)
    REFERENCES query_runtime_executions(execution_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS query_runtime_result_export_leases_expiry_idx
  ON query_runtime_result_export_leases (expires_at);
