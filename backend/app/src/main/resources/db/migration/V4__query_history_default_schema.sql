ALTER TABLE query_history
  ADD COLUMN IF NOT EXISTS default_schema VARCHAR(256);

ALTER TABLE query_runtime_executions
  ADD COLUMN IF NOT EXISTS default_schema VARCHAR(256);
