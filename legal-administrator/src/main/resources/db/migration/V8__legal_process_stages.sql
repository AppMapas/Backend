-- HU-07: configuración de etapas, copia por expediente e historial.
CREATE TABLE process_type_stage (
    id bigserial PRIMARY KEY,
    process_type_id bigint NOT NULL REFERENCES process_type(id),
    code varchar(40) NOT NULL,
    name varchar(150) NOT NULL,
    display_order integer NOT NULL,
    is_initial boolean NOT NULL DEFAULT false,
    is_terminal boolean NOT NULL DEFAULT false,
    CONSTRAINT uk_type_stage_code UNIQUE (process_type_id, code),
    CONSTRAINT uk_type_stage_order UNIQUE (process_type_id, display_order),
    CONSTRAINT ck_type_stage_order CHECK (display_order > 0),
    CONSTRAINT ck_type_stage_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_type_stage_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$')
);
CREATE UNIQUE INDEX uk_type_initial_stage ON process_type_stage(process_type_id) WHERE is_initial;

CREATE TABLE process_type_stage_transition (
    id bigserial PRIMARY KEY,
    process_type_id bigint NOT NULL REFERENCES process_type(id),
    from_code varchar(40) NOT NULL,
    to_code varchar(40) NOT NULL,
    CONSTRAINT uk_type_stage_transition UNIQUE (process_type_id, from_code, to_code),
    CONSTRAINT ck_type_stage_transition_direction CHECK (from_code <> to_code),
    CONSTRAINT fk_type_transition_from FOREIGN KEY (process_type_id, from_code)
        REFERENCES process_type_stage(process_type_id, code),
    CONSTRAINT fk_type_transition_to FOREIGN KEY (process_type_id, to_code)
        REFERENCES process_type_stage(process_type_id, code)
);

CREATE TABLE legal_process_stage (
    id bigserial PRIMARY KEY,
    legal_process_id bigint NOT NULL REFERENCES legal_process(id),
    code varchar(40) NOT NULL,
    name_snapshot varchar(150) NOT NULL,
    display_order integer NOT NULL,
    is_initial boolean NOT NULL,
    is_terminal boolean NOT NULL,
    CONSTRAINT uk_case_stage_code UNIQUE (legal_process_id, code),
    CONSTRAINT uk_case_stage_order UNIQUE (legal_process_id, display_order),
    CONSTRAINT uk_case_stage_id UNIQUE (legal_process_id, id),
    CONSTRAINT ck_case_stage_order CHECK (display_order > 0)
);
CREATE UNIQUE INDEX uk_case_initial_stage ON legal_process_stage(legal_process_id) WHERE is_initial;

CREATE TABLE legal_process_stage_transition (
    id bigserial PRIMARY KEY,
    legal_process_id bigint NOT NULL REFERENCES legal_process(id),
    from_code varchar(40) NOT NULL,
    to_code varchar(40) NOT NULL,
    CONSTRAINT uk_case_stage_transition UNIQUE (legal_process_id, from_code, to_code),
    CONSTRAINT ck_case_stage_transition_direction CHECK (from_code <> to_code),
    CONSTRAINT fk_case_transition_from FOREIGN KEY (legal_process_id, from_code)
        REFERENCES legal_process_stage(legal_process_id, code),
    CONSTRAINT fk_case_transition_to FOREIGN KEY (legal_process_id, to_code)
        REFERENCES legal_process_stage(legal_process_id, code)
);

ALTER TABLE legal_process ADD COLUMN current_stage_id bigint;
ALTER TABLE legal_process ADD CONSTRAINT fk_case_current_stage
    FOREIGN KEY (id, current_stage_id) REFERENCES legal_process_stage(legal_process_id, id);

CREATE TABLE legal_process_stage_event (
    id bigserial PRIMARY KEY,
    legal_process_id bigint NOT NULL REFERENCES legal_process(id),
    from_stage_id bigint,
    to_stage_id bigint NOT NULL,
    actor_dpi varchar(15) NOT NULL REFERENCES user_system(dpi),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    comment varchar(1000),
    request_id uuid UNIQUE,
    request_hash varchar(64),
    CONSTRAINT ck_stage_event_request CHECK (
        (request_id IS NULL AND request_hash IS NULL) OR
        (request_id IS NOT NULL AND request_hash IS NOT NULL)),
    CONSTRAINT fk_stage_event_from FOREIGN KEY (legal_process_id, from_stage_id)
        REFERENCES legal_process_stage(legal_process_id, id),
    CONSTRAINT fk_stage_event_to FOREIGN KEY (legal_process_id, to_stage_id)
        REFERENCES legal_process_stage(legal_process_id, id)
);
CREATE INDEX ix_stage_event_timeline ON legal_process_stage_event(legal_process_id, occurred_at, id);

-- Plantillas ya publicadas reciben una configuración inicial revisable.
INSERT INTO process_type_stage(process_type_id, code, name, display_order, is_initial, is_terminal)
SELECT p.id, seed.code, seed.name, seed.display_order, seed.is_initial, seed.is_terminal
FROM process_type p
CROSS JOIN (VALUES
    ('PRESENTADO', 'Presentado', 1, true, false),
    ('EN_REVISION', 'En revisión', 2, false, false),
    ('APROBADO', 'Aprobado', 3, false, false),
    ('ENTREGADO', 'Entregado', 4, false, true)
) AS seed(code, name, display_order, is_initial, is_terminal)
WHERE p.status = 'PUBLISHED'
   OR EXISTS (SELECT 1 FROM legal_process lp WHERE lp.id_process_type = p.id);

INSERT INTO process_type_stage_transition(process_type_id, from_code, to_code)
SELECT p.id, edge.from_code, edge.to_code
FROM process_type p
CROSS JOIN (VALUES
    ('PRESENTADO', 'EN_REVISION'),
    ('EN_REVISION', 'APROBADO'),
    ('APROBADO', 'ENTREGADO')
) AS edge(from_code, to_code)
WHERE p.status = 'PUBLISHED'
   OR EXISTS (SELECT 1 FROM legal_process lp WHERE lp.id_process_type = p.id);

-- No se asigna una etapa ficticia a expedientes anteriores.
