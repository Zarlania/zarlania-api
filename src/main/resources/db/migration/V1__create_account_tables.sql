CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                uuid PRIMARY KEY,
    email             citext NOT NULL UNIQUE,
    username          citext NOT NULL UNIQUE,
    email_verified_at timestamptz(6),
    created_at        timestamptz(6) NOT NULL,
    updated_at        timestamptz(6) NOT NULL
);

CREATE TABLE organizations (
    id         uuid PRIMARY KEY,
    name       citext NOT NULL UNIQUE,
    type       text NOT NULL CHECK (type IN ('PERSONAL', 'GENERAL')),
    created_at timestamptz(6) NOT NULL,
    updated_at timestamptz(6) NOT NULL
);

CREATE TABLE organization_memberships (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    user_id         uuid NOT NULL REFERENCES users (id),
    is_owner        boolean NOT NULL,
    created_at      timestamptz(6) NOT NULL,
    updated_at      timestamptz(6) NOT NULL,
    UNIQUE (organization_id, user_id)
);
CREATE INDEX idx_memberships_user ON organization_memberships (user_id);

CREATE TABLE password_credentials (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL UNIQUE REFERENCES users (id),
    password_hash text NOT NULL,
    created_at    timestamptz(6) NOT NULL,
    updated_at    timestamptz(6) NOT NULL
);

CREATE TABLE email_verification_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id),
    token_hash  text NOT NULL UNIQUE,
    expires_at  timestamptz(6) NOT NULL,
    consumed_at timestamptz(6),
    created_at  timestamptz(6) NOT NULL,
    updated_at  timestamptz(6) NOT NULL
);
CREATE INDEX idx_verification_tokens_user ON email_verification_tokens (user_id);

CREATE TABLE refresh_tokens (
    id                uuid PRIMARY KEY,
    family_id         uuid NOT NULL,
    user_id           uuid NOT NULL REFERENCES users (id),
    organization_id   uuid NOT NULL REFERENCES organizations (id),
    token_hash        text NOT NULL UNIQUE,
    family_expires_at timestamptz(6) NOT NULL,
    used_at           timestamptz(6),
    revoked_at        timestamptz(6),
    created_at        timestamptz(6) NOT NULL,
    updated_at        timestamptz(6) NOT NULL
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
