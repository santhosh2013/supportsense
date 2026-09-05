-- V2: seed data only — no DDL. Idempotent by design: safe to re-run without duplicating rows.

INSERT INTO teams (name, slug, description) VALUES
    ('Billing Ops', 'billing-ops', 'Invoicing, refunds and subscription billing'),
    ('Platform Support', 'platform-support', 'General platform issues and bug reports'),
    ('Identity & Access', 'identity-access', 'Login, provisioning and account security'),
    ('Data & Integrations', 'data-integrations', 'API integrations and data import/export'),
    ('Customer Success', 'customer-success', 'Escalations, legal/compliance and fallback triage')
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

INSERT INTO categories (name, slug, team_id, auto_answer_blocked, default_priority) VALUES
    ('Billing — Invoice', 'billing-invoice',
        (SELECT id FROM teams WHERE slug = 'billing-ops'), true, 'P2'),
    ('Billing — Refund', 'billing-refund',
        (SELECT id FROM teams WHERE slug = 'billing-ops'), true, 'P2'),
    ('Legal / Compliance', 'legal-compliance',
        (SELECT id FROM teams WHERE slug = 'customer-success'), true, 'P1'),
    ('Security / Privacy', 'security-privacy',
        (SELECT id FROM teams WHERE slug = 'identity-access'), true, 'P1'),
    ('Account Login / Access', 'account-login-access',
        (SELECT id FROM teams WHERE slug = 'identity-access'), false, 'P2'),
    ('Account Provisioning', 'account-provisioning',
        (SELECT id FROM teams WHERE slug = 'identity-access'), false, 'P3'),
    ('API Integration Error', 'api-integration-error',
        (SELECT id FROM teams WHERE slug = 'data-integrations'), false, 'P2'),
    ('Data Import / Export', 'data-import-export',
        (SELECT id FROM teams WHERE slug = 'data-integrations'), false, 'P3'),
    ('Performance / Latency', 'performance-latency',
        (SELECT id FROM teams WHERE slug = 'platform-support'), false, 'P2'),
    ('Bug Report', 'bug-report',
        (SELECT id FROM teams WHERE slug = 'platform-support'), false, 'P3')
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    team_id = EXCLUDED.team_id,
    auto_answer_blocked = EXCLUDED.auto_answer_blocked,
    default_priority = EXCLUDED.default_priority;

-- Bootstrap ADMIN. Password hash is a placeholder recognisable as such; the real hash is
-- set by the application on first startup from SUPPORTSENSE_SECURITY_ADMIN_PASSWORD, which
-- fails fast if absent outside the 'local' profile (see RequiredSecretsValidator).
INSERT INTO users (email, password_hash, full_name, role, active) VALUES
    ('admin@supportsense.local', '$PENDING$', 'SupportSense Admin', 'ADMIN', true)
ON CONFLICT (lower(email)) DO NOTHING;
