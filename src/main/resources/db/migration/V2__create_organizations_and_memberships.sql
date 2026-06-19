CREATE TABLE organizations (
    id         UUID                        NOT NULL,
    name       VARCHAR(200)                NOT NULL,
    type       VARCHAR(20)                 NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_organizations   PRIMARY KEY (id),
    CONSTRAINT ck_organizations_type CHECK (type IN ('PERSONAL', 'GENERAL'))
);

CREATE TABLE memberships (
    id              UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    user_id         UUID                        NOT NULL,
    role            VARCHAR(20)                 NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_memberships              PRIMARY KEY (id),
    CONSTRAINT fk_memberships_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_memberships_user         FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_memberships_org_user     UNIQUE (organization_id, user_id),
    CONSTRAINT ck_memberships_role         CHECK (role IN ('OWNER', 'MEMBER'))
);
