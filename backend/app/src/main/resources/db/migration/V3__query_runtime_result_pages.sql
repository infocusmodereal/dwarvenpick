CREATE TABLE IF NOT EXISTS query_runtime_result_pages (
  execution_id VARCHAR(36) NOT NULL,
  page_index INT NOT NULL,
  start_row INT NOT NULL,
  row_count INT NOT NULL,
  rows_json TEXT NOT NULL,
  byte_count BIGINT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT query_runtime_result_pages_pk PRIMARY KEY (execution_id, page_index),
  CONSTRAINT query_runtime_result_pages_execution_fk FOREIGN KEY (execution_id)
    REFERENCES query_runtime_executions(execution_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS query_runtime_result_pages_execution_start_idx
  ON query_runtime_result_pages (execution_id, start_row);

INSERT INTO query_runtime_result_pages (
  execution_id,
  page_index,
  start_row,
  row_count,
  rows_json,
  byte_count,
  created_at
)
SELECT
  execution_id,
  0,
  0,
  row_count,
  rows_json,
  OCTET_LENGTH(rows_json),
  COALESCE(completed_at, started_at, submitted_at)
FROM query_runtime_executions
WHERE row_count > 0
  AND rows_json IS NOT NULL
  AND rows_json <> '[]'
  AND NOT EXISTS (
    SELECT 1
    FROM query_runtime_result_pages p
    WHERE p.execution_id = query_runtime_executions.execution_id
  );
