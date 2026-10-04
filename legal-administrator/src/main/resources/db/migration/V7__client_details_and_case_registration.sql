-- Datos históricos incompletos se completan por edición, sin inventar valores.
ALTER TABLE client_user
    ADD COLUMN birth_date date,
    ADD COLUMN id_marital_status bigint REFERENCES marital_status(id),
    ADD COLUMN id_nationality bigint REFERENCES country(id),
    ADD COLUMN occupation varchar(150),
    ADD COLUMN exact_address varchar(255),
    ADD COLUMN id_municipality bigint REFERENCES municipality(id),
    ADD COLUMN active boolean NOT NULL DEFAULT true,
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- Ajustar los registros antiguos antes de aplicar restricciones a nuevas escrituras.
UPDATE client_user
SET updated_at = created_at::timestamp AT TIME ZONE 'America/Guatemala';

-- NOT VALID conserva datos heredados; se aplica a nuevas escrituras.
ALTER TABLE client_user ADD CONSTRAINT ck_client_dpi_format
    CHECK (dpi ~ '^[0-9]{13}$') NOT VALID;
ALTER TABLE client_user ADD CONSTRAINT ck_client_personal_names
    CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0) NOT VALID;
CREATE INDEX ix_client_name_order ON client_user(last_name, first_name, dpi);
CREATE INDEX ix_client_active_name ON client_user(active, last_name, first_name, dpi);

CREATE SEQUENCE legal_process_case_code_seq;
SELECT setval('legal_process_case_code_seq',
    COALESCE((SELECT max(substring(case_code FROM 5)::bigint)
        FROM legal_process WHERE case_code ~ '^EXP-[0-9]+$'), 0) + 1, false);
ALTER TABLE legal_process
    ALTER COLUMN case_code SET DEFAULT ('EXP-' || nextval('legal_process_case_code_seq')),
    ADD COLUMN active boolean NOT NULL DEFAULT true,
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN opened_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN modified_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by varchar(15) REFERENCES user_system(dpi),
    ADD COLUMN request_id uuid,
    ADD COLUMN request_hash varchar(64),
    ADD CONSTRAINT uk_legal_process_request UNIQUE(request_id),
    ADD CONSTRAINT ck_legal_process_request CHECK (
        (request_id IS NULL AND request_hash IS NULL) OR
        (request_id IS NOT NULL AND request_hash IS NOT NULL AND created_by IS NOT NULL));
CREATE INDEX ix_legal_process_active_opened ON legal_process(active, opened_at DESC, id DESC);
CREATE INDEX ix_legal_process_type ON legal_process(id_process_type);

-- Las fechas antiguas no tienen hora: se conserva el día como medianoche local.
UPDATE legal_process
SET opened_at = created_at::timestamp AT TIME ZONE 'America/Guatemala',
    modified_at = COALESCE(updated_at, created_at)::timestamp AT TIME ZONE 'America/Guatemala';
