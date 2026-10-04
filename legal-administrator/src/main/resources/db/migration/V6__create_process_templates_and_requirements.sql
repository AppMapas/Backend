-- Las plantillas se configuran antes de abrir expedientes. Los expedientes
-- conservan una copia de sus requisitos para no cambiar al editar la plantilla.
ALTER TABLE process_type
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_process_type_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'INACTIVE')),
    ADD CONSTRAINT ck_process_type_name CHECK (length(btrim(name)) > 0);

-- Una plantilla ya utilizada debe seguir apareciendo como publicada al migrar.
UPDATE process_type
SET status = 'PUBLISHED'
WHERE id IN (SELECT DISTINCT id_process_type FROM legal_process);

CREATE UNIQUE INDEX uk_process_type_normalized_name
    ON process_type (lower(btrim(name)));

CREATE TABLE requirement
(
    id          bigserial PRIMARY KEY,
    name        varchar(150) NOT NULL,
    description text,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_requirement_name CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX uk_requirement_normalized_name
    ON requirement (lower(btrim(name)));

CREATE TABLE process_type_requirement
(
    id                bigserial PRIMARY KEY,
    process_type_id   bigint NOT NULL REFERENCES process_type (id),
    requirement_id    bigint NOT NULL REFERENCES requirement (id),
    is_required       boolean NOT NULL DEFAULT true,
    requires_document boolean NOT NULL DEFAULT false,
    display_order     integer NOT NULL,
    instructions      text,
    CONSTRAINT uk_process_type_requirement UNIQUE (process_type_id, requirement_id),
    CONSTRAINT uk_process_type_requirement_order UNIQUE (process_type_id, display_order),
    CONSTRAINT ck_process_type_requirement_order CHECK (display_order > 0)
);

CREATE INDEX ix_process_type_requirement_requirement
    ON process_type_requirement (requirement_id);

-- Campos preparados para identificar la plantilla utilizada en expedientes
-- anteriores y futuros, aun si su nombre cambia después.
ALTER TABLE legal_process
    ADD COLUMN case_code varchar(40),
    ADD COLUMN process_type_name_snapshot varchar(100),
    ADD COLUMN process_type_version_snapshot bigint;

UPDATE legal_process AS lp
SET case_code = 'EXP-' || lp.id,
    process_type_name_snapshot = pt.name,
    process_type_version_snapshot = pt.version
FROM process_type AS pt
WHERE lp.id_process_type = pt.id;

ALTER TABLE legal_process
    ALTER COLUMN case_code SET NOT NULL,
    ALTER COLUMN process_type_name_snapshot SET NOT NULL,
    ALTER COLUMN process_type_version_snapshot SET NOT NULL,
    ADD CONSTRAINT uk_legal_process_case_code UNIQUE (case_code);

CREATE INDEX ix_legal_process_client_status
    ON legal_process (dpi_client, current_status);

CREATE TABLE legal_process_requirement
(
    id                             bigserial PRIMARY KEY,
    legal_process_id               bigint NOT NULL REFERENCES legal_process (id),
    source_requirement_id          bigint REFERENCES requirement (id),
    name_snapshot                  varchar(150) NOT NULL,
    description_snapshot           text,
    instructions_snapshot          text,
    is_required_snapshot           boolean NOT NULL,
    requires_document_snapshot     boolean NOT NULL,
    display_order                  integer NOT NULL,
    status                         varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at                     timestamptz NOT NULL DEFAULT now(),
    updated_at                     timestamptz NOT NULL DEFAULT now(),
    completed_at                   timestamptz,
    reviewed_by                    varchar(15) REFERENCES user_system (dpi),
    CONSTRAINT uk_legal_process_requirement_order UNIQUE (legal_process_id, display_order),
    CONSTRAINT ck_legal_process_requirement_order CHECK (display_order > 0),
    CONSTRAINT ck_legal_process_requirement_status
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'COMPLETED', 'NOT_APPLICABLE')),
    CONSTRAINT ck_legal_process_requirement_completed
        CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX ix_legal_process_requirement_source
    ON legal_process_requirement (source_requirement_id);
