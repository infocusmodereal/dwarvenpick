ALTER TABLE query_history
  ADD COLUMN IF NOT EXISTS justification TEXT;

ALTER TABLE query_runtime_executions
  ADD COLUMN IF NOT EXISTS justification TEXT;
