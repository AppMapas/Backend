DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM legal_process_calendar) THEN
        RAISE EXCEPTION 'Agenda: concilia los eventos heredados (zona, inicio, fin y responsable) antes de aplicar V11. No borres los registros para omitir este control.';
    END IF;
END $$;
ALTER TABLE legal_process_calendar ALTER COLUMN id_legal_process DROP NOT NULL;
ALTER TABLE legal_process_calendar
    ADD COLUMN client_dpi varchar(15) REFERENCES client_user(dpi),
    ADD COLUMN responsible_dpi varchar(15) NOT NULL REFERENCES user_system(dpi),
    ADD COLUMN starts_at timestamptz NOT NULL,
    ADD COLUMN ends_at timestamptz NOT NULL,
    ADD COLUMN time_zone varchar(60) NOT NULL DEFAULT 'America/Guatemala',
    ADD COLUMN all_day boolean NOT NULL DEFAULT false,
    ADD COLUMN activity_type varchar(30) NOT NULL,
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'SCHEDULED',
    ADD COLUMN location varchar(255),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN created_by varchar(15) NOT NULL REFERENCES user_system(dpi),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN request_id uuid NOT NULL UNIQUE,
    ADD COLUMN request_hash varchar(64) NOT NULL,
    ADD CONSTRAINT agenda_dates CHECK (ends_at > starts_at AND ends_at <= starts_at + interval '31 days'),
    ADD CONSTRAINT agenda_type CHECK (activity_type IN ('APPOINTMENT','HEARING','PRESENTATION','DELIVERY','FOLLOW_UP','PAYMENT_REMINDER')),
    ADD CONSTRAINT agenda_status CHECK (status IN ('SCHEDULED','COMPLETED','CANCELLED')),
    ADD CONSTRAINT agenda_completion CHECK (is_completed = (status = 'COMPLETED'));
CREATE INDEX agenda_window ON legal_process_calendar(starts_at, ends_at);
CREATE INDEX agenda_case ON legal_process_calendar(id_legal_process, starts_at);
CREATE INDEX agenda_responsible ON legal_process_calendar(responsible_dpi, starts_at) WHERE status = 'SCHEDULED';
CREATE TABLE agenda_event_history (
    id bigserial PRIMARY KEY,
    event_id bigint NOT NULL REFERENCES legal_process_calendar(id),
    action varchar(30) NOT NULL,
    operator_dpi varchar(15) NOT NULL REFERENCES user_system(dpi),
    event_version bigint NOT NULL,
    reason varchar(500),
    event_title varchar(150) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    event_status varchar(20) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(event_id, event_version)
);
CREATE TABLE google_calendar_connection (
    id smallint PRIMARY KEY CHECK(id = 1),
    owner_dpi varchar(15) REFERENCES user_system(dpi),
    google_subject varchar(255),
    google_email varchar(255),
    calendar_id varchar(255),
    encrypted_refresh_token text,
    key_version varchar(40),
    state varchar(30) NOT NULL DEFAULT 'DISCONNECTED',
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK(state IN ('DISCONNECTED','CONNECTED','REAUTH_REQUIRED','NO_PERMISSION'))
);
INSERT INTO google_calendar_connection(id) VALUES(1);
CREATE TABLE google_calendar_oauth_intent (
    state_hash varchar(64) PRIMARY KEY,
    owner_dpi varchar(15) NOT NULL REFERENCES user_system(dpi),
    expires_at timestamptz NOT NULL
);
CREATE TABLE agenda_google_event (
    event_id bigint NOT NULL REFERENCES legal_process_calendar(id),
    calendar_id varchar(255) NOT NULL,
    google_event_id varchar(100) NOT NULL,
    etag varchar(255),
    synced_version bigint NOT NULL DEFAULT -1,
    state varchar(20) NOT NULL DEFAULT 'PENDING',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(event_id, calendar_id),
    UNIQUE(calendar_id, google_event_id),
    CHECK(state IN ('PENDING','SYNCED','ERROR','CONFLICT'))
);
CREATE TABLE agenda_sync_outbox (
    id bigserial PRIMARY KEY,
    event_id bigint NOT NULL REFERENCES legal_process_calendar(id),
    event_version bigint NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    last_error varchar(40),
    UNIQUE(event_id, event_version)
);
CREATE INDEX agenda_jobs_due ON agenda_sync_outbox(next_attempt_at) WHERE completed_at IS NULL;
