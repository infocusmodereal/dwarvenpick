create table if not exists query_executions (
    execution_id varchar(64) primary key,
    username varchar(255) not null,
    datasource_id varchar(255) not null,
    credential_profile varchar(255) not null,
    status varchar(32) not null,
    query_text text not null,
    query_hash varchar(64) not null,
    row_count integer not null default 0,
    row_limit_reached boolean not null default false,
    error_summary text,
    submitted_at timestamptz not null,
    started_at timestamptz,
    completed_at timestamptz
);

create index if not exists idx_query_executions_username on query_executions (username);
create index if not exists idx_query_executions_status on query_executions (status);
create index if not exists idx_query_executions_submitted_at on query_executions (submitted_at desc);
