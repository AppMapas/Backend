-- Control de anticipos y abonos por expediente (HU-08).
--
-- Decisiones:
--  * Los montos son NUMERIC(14,2), nunca double precision. Un saldo financiero
--    no admite errores de coma flotante: 0.10 + 0.20 debe ser 0.30 exacto.
--  * Los montos están en quetzales (GTQ). No hay columna de moneda porque la
--    despacho solo opera en quetzales; añadirla exigiría cambiar rates y UI.
--  * El costo total pactado vive en el expediente, no en la plantilla: es una
--    negociación por cliente y no debe cambiar si editan el catálogo.
--  * El saldo pendiente NO se guarda. Se deriva como
--    total_amount - SUM(amount) sobre abonos vigentes; almacenarlo se desincroniza.
--  * No se duplica el cliente: se hereda del expediente, evitando pagos
--    asociados a un cliente distinto del trámite.

-- NULL significa "el costo total todavía no está pactado", que es distinto de
-- un total de cero. El resumen financiero debe diferenciar ambos casos.
ALTER TABLE legal_process
    ADD COLUMN total_amount numeric(14,2),
    ADD CONSTRAINT ck_legal_process_total_amount CHECK (total_amount >= 0);

COMMENT ON COLUMN legal_process.total_amount IS
    'Costo total pactado en quetzales para este expediente; NULL si aún no se acuerda.';

CREATE TABLE case_payment
(
    id               uuid        PRIMARY KEY,
    legal_process_id bigint      NOT NULL REFERENCES legal_process (id) ON DELETE RESTRICT,
    amount           numeric(14,2) NOT NULL,
    payment_type     varchar(20)  NOT NULL,
    payment_method   varchar(20)  NOT NULL,
    concept          varchar(120) NOT NULL,
    payment_date     date         NOT NULL,
    reference        varchar(60),
    active           boolean      NOT NULL DEFAULT true,
    registered_by    varchar(15)  NOT NULL REFERENCES user_system (dpi),
    request_id       uuid,
    request_hash     varchar(64),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_case_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_case_payment_type
        CHECK (payment_type IN ('ANTICIPO', 'ABONO', 'PAGO_FINAL')),
    CONSTRAINT ck_case_payment_method
        CHECK (payment_method IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA', 'CHEQUE', 'OTRO')),
    CONSTRAINT ck_case_payment_concept CHECK (length(btrim(concept)) > 0),
    CONSTRAINT ck_case_payment_reference
        CHECK (reference IS NULL OR length(btrim(reference)) > 0),
    -- Solo se registra lo ya recibido: un abono no puede estar fechado en el futuro.
    CONSTRAINT ck_case_payment_date CHECK (
        payment_date <= (created_at AT TIME ZONE 'America/Guatemala')::date),
    -- Clave de idempotencia: un doble toque en el teléfono no duplica el abono.
    CONSTRAINT uk_case_payment_request UNIQUE (request_id),
    CONSTRAINT ck_case_payment_request CHECK (
        (request_id IS NULL AND request_hash IS NULL) OR
        (request_id IS NOT NULL AND request_hash IS NOT NULL))
);

COMMENT ON TABLE case_payment IS
    'Anticipos y abonos por expediente. Un abono anulado conserva la fila con active = false.';
COMMENT ON COLUMN case_payment.active IS
    'false cuando el abono se anula; nunca se borra la fila para conservar el rastro.';

-- Índice del listado del expediente, del abono más reciente al más antiguo.
CREATE INDEX ix_case_payment_process_date
    ON case_payment (legal_process_id, payment_date DESC, id DESC);

-- Suma de abonos vigentes por expediente: base del saldo pendiente.
CREATE INDEX ix_case_payment_process_active
    ON case_payment (legal_process_id) WHERE active;

-- Nota: NUMERIC(14,2) redondea en silencio un valor con más decimales.
-- Por eso el testigo de precisión vive en CasePaymentEntity (una entidad no
-- puede escribir 10.005 y esperar que la base lo rechace).