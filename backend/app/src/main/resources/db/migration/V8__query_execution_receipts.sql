ALTER TABLE query_runtime_executions ADD COLUMN write_started BOOLEAN NOT NULL DEFAULT FALSE;

-- Keep these small receipts after runtime data and result pages are pruned.
-- There is intentionally no foreign key to the runtime or history table.
CREATE TABLE query_execution_receipts (
    execution_id VARCHAR(36) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL
);
