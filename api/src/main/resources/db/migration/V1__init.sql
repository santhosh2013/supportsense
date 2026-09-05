-- V1: identity and taxonomy.
-- Batch 1 establishes the walking skeleton; teams/categories/users columns land here so
-- Flyway, ddl-auto=validate and the CI drift gate all have something real to verify.

CREATE TABLE teams (
    id          bigserial PRIMARY KEY,
    name        varchar(120) NOT NULL,
    slug        varchar(120) NOT NULL,
    description text,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_team_slug ON teams (slug);

CREATE TABLE categories (
    id                  bigserial PRIMARY KEY,
    name                varchar(120) NOT NULL,
    slug                varchar(120) NOT NULL,
    parent_id           bigint REFERENCES categories (id),
    team_id             bigint REFERENCES teams (id),
    required_skill      varchar(120),
    default_priority    varchar(8),
    -- Topic-level auto-answer guard. Paired with a deterministic keyword pre-screen that
    -- runs independently of the model, because a misclassification would otherwise bypass
    -- a gate keyed solely on the predicted category.
    auto_answer_blocked boolean      NOT NULL DEFAULT false,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_category_default_priority
        CHECK (default_priority IS NULL OR default_priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_category_not_own_parent
        CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE UNIQUE INDEX ux_category_slug ON categories (slug);
CREATE INDEX ix_category_parent ON categories (parent_id);

CREATE TABLE users (
    id            bigserial PRIMARY KEY,
    email         varchar(255) NOT NULL,
    password_hash varchar(255) NOT NULL,
    full_name     varchar(180) NOT NULL,
    role          varchar(16)  NOT NULL,
    team_id       bigint REFERENCES teams (id),
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_role CHECK (role IN ('AGENT', 'LEAD', 'ADMIN', 'SERVICE'))
);

CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));
