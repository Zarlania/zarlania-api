-- Neither of refresh_tokens' foreign keys had an index. Postgres indexes the referenced key, never
-- the referencing column, so deleting a parent row has to scan the whole child table to prove
-- nothing still points at it — and refresh_tokens is the fastest-growing table here, at roughly 96
-- rows a day per active session.
--
-- UnverifiedAccountPurger deletes both parents for one account inside a single transaction: the
-- users row, and the personal organization it owns. Indexing one column and not the other would
-- have left half of every purge scanning, so the two belong in the same migration.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_organization ON refresh_tokens (organization_id);
