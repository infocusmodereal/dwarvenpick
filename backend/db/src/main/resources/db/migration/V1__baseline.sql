-- Baseline schema migration.
-- Evolve the schema via subsequent numbered Flyway migrations.

create table if not exists schema_version_notes (
    id bigserial primary key,
    note text not null,
    created_at timestamptz not null default now()
);

insert into schema_version_notes (note)
values ('dwarvenpick schema baseline initialized')
on conflict do nothing;
