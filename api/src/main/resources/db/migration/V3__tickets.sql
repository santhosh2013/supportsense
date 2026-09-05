-- V3: tickets and their audit trail.
--
-- Two state machines live on this table, deliberately kept separate (ADR-0012):
--   status           — the BR-A09 business lifecycle
--   ingestion_state   — the async pipeline (ADR-0011): PENDING/PROCESSING/DONE/FAILED
--
-- customer_tier is a point-in-time snapshot, not an FK (ADR-0001). auto_answered,
-- auto_answered_at and first_resolved_at exist purely so false-deflection is computable
-- at all — see docs/metrics.md.

CREATE TABLE tickets (
    id                  bigserial PRIMARY KEY,
    external_ref        varchar(255) NOT NULL,
    subject             varchar(500) NOT NULL,
    body                text         NOT NULL,
    channel             varchar(16)  NOT NULL,
    customer_email      varchar(255) NOT NULL,
    customer_tier       varchar(16)  NOT NULL DEFAULT 'FREE',
    status              varchar(24)  NOT NULL DEFAULT 'NEW',
    priority             varchar(8),
    category_id         bigint REFERENCES categories (id),
    team_id             bigint REFERENCES teams (id),
    assignee_id         bigint REFERENCES users (id),

    -- Async ingestion pipeline state (BR-A01 / ADR-0011). Orthogonal to `status`.
    ingestion_state     varchar(16)  NOT NULL DEFAULT 'PENDING',
    claimed_at          timestamptz,
    attempt_count       integer      NOT NULL DEFAULT 0,
    ingestion_error     text,

    -- Deflection measurement (docs/metrics.md).
    auto_answered       boolean      NOT NULL DEFAULT false,
    auto_answered_at    timestamptz,
    first_resolved_at   timestamptz,

    resolution_text     text,
    resolved_by         varchar(16),
    resolved_at         timestamptz,
    sla_due_at          timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    first_response_at   timestamptz,
    version             integer      NOT NULL DEFAULT 0,

    CONSTRAINT ck_ticket_channel CHECK (channel IN ('EMAIL', 'WEB', 'CHAT', 'API')),
    CONSTRAINT ck_ticket_customer_tier CHECK (customer_tier IN ('FREE', 'PRO', 'ENTERPRISE')),
    CONSTRAINT ck_ticket_status CHECK (status IN
        ('NEW', 'TRIAGED', 'IN_PROGRESS', 'PENDING_CUSTOMER', 'RESOLVED', 'CLOSED', 'DUPLICATE')),
    CONSTRAINT ck_ticket_priority CHECK (priority IS NULL OR priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_ticket_ingestion_state CHECK (
        ingestion_state IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT ck_ticket_resolved_by CHECK (
        resolved_by IS NULL OR resolved_by IN ('HUMAN', 'AI_ACCEPTED', 'AI_AUTO')),
    CONSTRAINT ck_ticket_resolved_after_created CHECK (resolved_at IS NULL OR resolved_at >= created_at)
);

CREATE UNIQUE INDEX ux_ticket_external_ref ON tickets (external_ref);
CREATE INDEX ix_ticket_status_sla ON tickets (status, sla_due_at) WHERE resolved_at IS NULL;
CREATE INDEX ix_ticket_category_created ON tickets (category_id, created_at);

-- Keeps the sweep/reaper scan cheap — see ADR-0011.
CREATE INDEX ix_ticket_ingestion_pending
    ON tickets (ingestion_state, claimed_at)
    WHERE ingestion_state IN ('PENDING', 'PROCESSING');

CREATE TABLE ticket_events (
    id           bigserial PRIMARY KEY,
    ticket_id    bigint       NOT NULL REFERENCES tickets (id),
    event_type   varchar(32)  NOT NULL,
    from_status  varchar(24),
    to_status    varchar(24),
    actor_user_id bigint REFERENCES users (id),
    payload_json jsonb,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_event_type CHECK (event_type IN (
        'CREATED', 'TRIAGED', 'ASSIGNED', 'STATUS_CHANGED',
        'SUGGESTION_GENERATED', 'SUGGESTION_ACCEPTED', 'RESOLVED', 'MARKED_DUPLICATE'))
);

CREATE INDEX ix_event_ticket_occurred ON ticket_events (ticket_id, occurred_at DESC);

-- RESOLVED -> CLOSED is a normal forward closure, NOT a reopen. Excluding it here is what
-- keeps false-deflection rate honest — see docs/metrics.md and ADR discussion in requirements.
CREATE INDEX ix_event_reopen
    ON ticket_events (ticket_id, occurred_at)
    WHERE from_status IN ('RESOLVED', 'CLOSED') AND to_status NOT IN ('RESOLVED', 'CLOSED');
