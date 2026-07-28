-- refresh_tokens.user_id carries a foreign key but had no index. Postgres does not create one for
-- a referencing column, so every delete of a users row (UnverifiedAccountPurger) and every
-- deleteByUserId sweep scanned the whole table — the one that grows fastest, at roughly 96 rows a
-- day per active session.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
