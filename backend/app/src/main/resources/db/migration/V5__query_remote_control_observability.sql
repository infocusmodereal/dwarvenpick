ALTER TABLE query_runtime_executions
  ADD COLUMN control_action VARCHAR(16);

ALTER TABLE query_runtime_executions
  ADD COLUMN control_requested_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE query_runtime_executions
  ADD COLUMN control_observed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE query_runtime_executions
  ADD CONSTRAINT query_runtime_control_action_check
  CHECK (control_action IS NULL OR control_action IN ('CANCEL', 'KILL'));

CREATE INDEX IF NOT EXISTS query_runtime_executions_owner_control_idx
  ON query_runtime_executions (owner_instance_id, status, control_observed_at);
