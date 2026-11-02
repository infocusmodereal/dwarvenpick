-- Preserve existing usernames and history while introducing immutable account IDs.
ALTER TABLE app_users ADD COLUMN user_id VARCHAR(300);
UPDATE app_users SET user_id = 'legacy:' || username;
ALTER TABLE app_users ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE app_users ADD CONSTRAINT app_users_user_id_unique UNIQUE (user_id);

CREATE TABLE app_external_identities (
  provider VARCHAR(32) NOT NULL,
  issuer VARCHAR(1024) NOT NULL,
  subject VARCHAR(1024) NOT NULL,
  user_id VARCHAR(300) NOT NULL,
  PRIMARY KEY (provider, issuer, subject),
  FOREIGN KEY (user_id) REFERENCES app_users(user_id) ON DELETE CASCADE,
  CHECK (provider IN ('LDAP', 'OIDC'))
);

-- app_user_groups already drives authorization. Do not union stale administrative
-- memberships into it, as that could restore previously revoked access.
DROP TABLE rbac_group_members;
ALTER TABLE app_user_groups ADD CONSTRAINT app_user_groups_user_fk
  FOREIGN KEY (username) REFERENCES app_users(username) ON DELETE CASCADE;
ALTER TABLE app_user_roles ADD CONSTRAINT app_user_roles_user_fk
  FOREIGN KEY (username) REFERENCES app_users(username) ON DELETE CASCADE;
ALTER TABLE rbac_datasource_access ADD CONSTRAINT rbac_access_group_fk
  FOREIGN KEY (group_id) REFERENCES rbac_groups(group_id) ON DELETE CASCADE;
ALTER TABLE managed_credential_profiles ADD CONSTRAINT credential_profiles_connection_fk
  FOREIGN KEY (datasource_id) REFERENCES managed_datasources(datasource_id) ON DELETE CASCADE;
