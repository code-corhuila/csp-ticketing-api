-- Test fixture: the part of the ticketing_fulfillment schema that the consumers use.
-- Mirrors csp-ticketing-db V001 to V009 (schema, user_contact, inbox_event, tickets, ticketing_fulfillments,
-- pending_concession_order) so the integration tests run against the same table shape; the API itself
-- never runs migrations.
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

CREATE TABLE ticketing_fulfillment.tickets (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL,
    user_contact_id      UUID,
    reservation_id       UUID         NOT NULL,
    showtime_id          UUID         NOT NULL,
    movie_title_snapshot VARCHAR(255) NOT NULL,
    room_name_snapshot   VARCHAR(160) NOT NULL,
    showtime_starts_at   TIMESTAMPTZ,
    seat_labels_snapshot JSONB        NOT NULL,
    concessions_snapshot JSONB,
    qr_payload           TEXT         NOT NULL,
    pdf_url              VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT uk_tickets_reservation_id UNIQUE (reservation_id)
);

CREATE TABLE ticketing_fulfillment.ticketing_fulfillments (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    ticket_id     UUID        NOT NULL,
    channel       VARCHAR(50) NOT NULL DEFAULT 'EMAIL',
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT,
    next_retry_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ticketing_fulfillments PRIMARY KEY (id),
    CONSTRAINT fk_ticketing_fulfillments_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticketing_fulfillment.tickets (id) ON DELETE CASCADE,
    CONSTRAINT chk_ticketing_fulfillments_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE TABLE ticketing_fulfillment.pending_concession_order (
    reservation_id UUID        NOT NULL,
    items_json     JSONB       NOT NULL,
    event_id       VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_pending_concession_order PRIMARY KEY (reservation_id)
);
