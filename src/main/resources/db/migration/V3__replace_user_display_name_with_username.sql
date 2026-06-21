-- Phase 3: the user's public name becomes a unique handle (username), replacing display_name.
-- A personal organization is created under this handle, so a globally-unique username makes the
-- personal-org name globally unique too (see uq_organizations_name in V2), which DB-enforces the
-- one-personal-organization-per-user rule. The users table is empty at every fresh migration
-- (in-memory H2 now; an empty table at first Postgres baseline later), so the NOT NULL column needs
-- no backfill default.
ALTER TABLE users DROP COLUMN display_name;
ALTER TABLE users ADD COLUMN username VARCHAR(100) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_username UNIQUE (username);
