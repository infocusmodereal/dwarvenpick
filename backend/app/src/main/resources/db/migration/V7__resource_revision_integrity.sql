-- Fail migration on duplicate revisions instead of silently discarding history.
ALTER TABLE resource_script_versions ADD CONSTRAINT resource_revision_unique UNIQUE (resource_id, revision);
ALTER TABLE resource_script_versions ADD CONSTRAINT resource_version_resource_fk
  FOREIGN KEY (resource_id) REFERENCES resource_scripts(resource_id) ON DELETE CASCADE;
