CREATE TABLE IF NOT EXISTS query_result_storage_lock (
  lock_id SMALLINT NOT NULL,
  lock_name VARCHAR(64) NOT NULL,
  CONSTRAINT query_result_storage_lock_pk PRIMARY KEY (lock_id)
);

INSERT INTO query_result_storage_lock (lock_id, lock_name)
SELECT 1, 'persisted-result-bytes'
WHERE NOT EXISTS (
  SELECT 1
  FROM query_result_storage_lock
  WHERE lock_id = 1
);
