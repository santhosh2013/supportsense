-- V4: triage results and duplicate links.
--
-- These tables are PERSISTENCE-ONLY in milestone A1 — schema + entity + bare JpaRepository,
-- nothing else. No service, no endpoint, no business logic touches them until A2/A5.
--
-- confidence columns are numeric(4,3), never float: thresholds drive the abstention rule
-- in A2, and floating-point comparison at a boundary is a real bug source.
--
-- Duplicate pairs are stored in CANONICAL order (ticket_a_id < ticket_b_id) so A-dup-B and
-- B-dup-A cannot both be inserted as distinct rows.

CREATE TABLE triage_results (
    id                     bigserial PRIMARY KEY,
    ticket_id              bigint       NOT NULL REFERENCES tickets (id),
    predicted_category_id  bigint REFERENCES categories (id),
    category_confidence    numeric(4,3),
    predicted_priority     varchar(8),
    priority_confidence    numeric(4,3),
    predicted_team_id      bigint REFERENCES teams (id),
    model_name             varchar(120),
    prompt_tokens          integer,
    completion_tokens      integer,
    latency_ms             integer,
    abstained              boolean      NOT NULL DEFAULT false,
    abstention_reason      varchar(24)  NOT NULL DEFAULT 'NONE',
    created_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_triage_category_confidence CHECK (
        category_confidence IS NULL OR category_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_triage_priority_confidence CHECK (
        priority_confidence IS NULL OR priority_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_triage_predicted_priority CHECK (
        predicted_priority IS NULL OR predicted_priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_triage_abstention_reason CHECK (abstention_reason IN (
        'NONE', 'LOW_CONFIDENCE', 'LOW_SIMILARITY', 'ENTERPRISE_TIER',
        'SENSITIVE_CATEGORY', 'KEYWORD_PRESCREEN', 'TOOL_FAILURE'))
);

CREATE INDEX ix_triage_ticket ON triage_results (ticket_id);
CREATE INDEX ix_triage_abstained ON triage_results (abstained, created_at);

CREATE TABLE duplicate_links (
    id                    bigserial PRIMARY KEY,
    ticket_a_id           bigint       NOT NULL REFERENCES tickets (id),
    ticket_b_id           bigint       NOT NULL REFERENCES tickets (id),
    similarity            numeric(4,3) NOT NULL,
    detected_at           timestamptz  NOT NULL DEFAULT now(),
    confirmed_by          bigint REFERENCES users (id),
    status                varchar(16)  NOT NULL DEFAULT 'SUGGESTED',
    CONSTRAINT ck_dup_canonical_order CHECK (ticket_a_id < ticket_b_id),
    CONSTRAINT ck_dup_similarity CHECK (similarity BETWEEN 0 AND 1),
    CONSTRAINT ck_dup_status CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'REJECTED'))
);

CREATE UNIQUE INDEX ux_dup_pair ON duplicate_links (ticket_a_id, ticket_b_id);
