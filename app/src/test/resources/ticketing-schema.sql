-- Test fixture: the part of the ticketing_fulfillment schema that the contact projection uses.
-- Mirrors csp-ticketing-db V001 (schema), V002 (user_contact) and V003 (inbox_event) so the
-- integration tests run against the same table shape; the API itself never runs migrations.
CREATE SCHEMA ticketing_fulfillment;

CREATE TABLE ticketing_fulfillment.user_contact (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    email           TEXT        NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_contact PRIMARY KEY (id),
    CONSTRAINT uk_user_contact_user_id UNIQUE (user_id),
    CONSTRAINT chk_user_contact_email_length CHECK (char_length(email) BETWEEN 3 AND 255),
    CONSTRAINT chk_user_contact_email_format CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$')
);

CREATE TABLE ticketing_fulfillment.inbox_event (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_id     UUID        NOT NULL,
    event_type   TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    error        TEXT,
    CONSTRAINT pk_inbox_event PRIMARY KEY (id),
    CONSTRAINT uk_inbox_event_event_id UNIQUE (event_id),
    CONSTRAINT chk_inbox_event_event_type_length CHECK (char_length(event_type) BETWEEN 1 AND 100)
);
