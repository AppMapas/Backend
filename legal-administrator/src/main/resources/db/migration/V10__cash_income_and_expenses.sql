DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM expense_record) OR EXISTS (SELECT 1 FROM income_record) THEN
        RAISE EXCEPTION 'H09: concilia expense_record e income_record heredados antes de habilitar Caja. No se convertirán ni duplicarán importes automáticamente.';
    END IF;
END $$;

ALTER TABLE payment_category ADD COLUMN code varchar(50);
UPDATE payment_category SET code = 'LEGACY_' || id;
ALTER TABLE payment_category ALTER COLUMN code SET NOT NULL;
ALTER TABLE payment_category ADD CONSTRAINT uk_payment_category_code UNIQUE (code);
ALTER TABLE payment_category ADD COLUMN active boolean NOT NULL DEFAULT false;
-- Importaciones antiguas pudieron asignar ids sin avanzar la secuencia.
SELECT setval(pg_get_serial_sequence('payment_category', 'id'),
    greatest(coalesce((SELECT max(id) FROM payment_category), 1),
        (SELECT last_value FROM payment_category_id_seq)), true);
INSERT INTO payment_category (code, name, description, active) VALUES
 ('TRAMITES', 'Trámites', 'Cobros recibidos por expedientes', true),
 ('UTILES_OFICINA', 'Útiles de oficina', 'Compras de materiales de oficina', true),
 ('GASTOS_PERSONALES', 'Gastos personales', 'Egresos privados de su titular', true);

ALTER TABLE expense_record ALTER COLUMN amount TYPE numeric(14,2) USING amount::numeric(14,2);
ALTER TABLE expense_record ALTER COLUMN amount SET NOT NULL;
ALTER TABLE expense_record ALTER COLUMN description SET NOT NULL;
ALTER TABLE expense_record ALTER COLUMN expense_date SET NOT NULL;
ALTER TABLE expense_record ADD COLUMN payment_method varchar(20) NOT NULL;
ALTER TABLE expense_record ADD COLUMN reference varchar(60);
ALTER TABLE expense_record ADD COLUMN registered_by varchar(15) NOT NULL REFERENCES user_system(dpi);
ALTER TABLE expense_record ADD COLUMN owner_dpi varchar(15) REFERENCES user_system(dpi);
ALTER TABLE expense_record ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE expense_record ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE expense_record ADD COLUMN request_id uuid NOT NULL UNIQUE;
ALTER TABLE expense_record ADD COLUMN request_hash varchar(64) NOT NULL;
ALTER TABLE expense_record ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE expense_record ADD COLUMN annulled_at timestamptz;
ALTER TABLE expense_record ADD COLUMN annulled_by varchar(15) REFERENCES user_system(dpi);
ALTER TABLE expense_record ADD COLUMN annul_reason varchar(500);
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_amount CHECK (amount > 0);
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_description CHECK (length(btrim(description)) BETWEEN 1 AND 500);
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_scope CHECK (
 id_legal_process IS NULL AND ((scope = 'OFICINA' AND owner_dpi IS NULL)
 OR (scope = 'PERSONAL' AND owner_dpi IS NOT NULL AND owner_dpi = registered_by)));
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_method CHECK (payment_method IN ('EFECTIVO','TRANSFERENCIA','TARJETA','CHEQUE','OTRO'));
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_date CHECK (expense_date <= (created_at AT TIME ZONE 'America/Guatemala')::date);
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_reference CHECK (reference IS NULL OR length(btrim(reference)) BETWEEN 1 AND 60);
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_annul CHECK (
 (active AND annulled_at IS NULL AND annulled_by IS NULL AND annul_reason IS NULL)
 OR (NOT active AND annulled_at IS NOT NULL AND annulled_by IS NOT NULL AND annul_reason IS NOT NULL AND length(btrim(annul_reason)) BETWEEN 1 AND 500));
ALTER TABLE expense_record ADD CONSTRAINT ck_expense_hash CHECK (request_hash ~ '^[a-f0-9]{64}$');
CREATE INDEX ix_expense_date ON expense_record(expense_date DESC, id DESC);
CREATE INDEX ix_expense_owner_date ON expense_record(owner_dpi, expense_date DESC);

ALTER TABLE case_payment ADD COLUMN annulled_at timestamptz;
ALTER TABLE case_payment ADD COLUMN annulled_by varchar(15) REFERENCES user_system(dpi);
ALTER TABLE case_payment ADD COLUMN annul_reason varchar(500);
CREATE INDEX ix_payment_cash_date ON case_payment(payment_date DESC, created_at DESC, id);

CREATE FUNCTION protect_expense_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE category_code text;
BEGIN
 SELECT code INTO category_code FROM payment_category WHERE id = NEW.id_payment_category AND active;
 IF NOT ((category_code = 'UTILES_OFICINA' AND NEW.scope = 'OFICINA')
     OR (category_code = 'GASTOS_PERSONALES' AND NEW.scope = 'PERSONAL')) OR category_code IS NULL THEN
   RAISE EXCEPTION 'Categoría incompatible con el gasto';
 END IF;
 IF TG_OP = 'UPDATE' THEN
   IF ROW(NEW.amount, NEW.description, NEW.expense_date, NEW.id_payment_category, NEW.scope,
          NEW.registered_by, NEW.owner_dpi, NEW.request_id, NEW.request_hash, NEW.payment_method, NEW.reference, NEW.created_at)
      IS DISTINCT FROM
      ROW(OLD.amount, OLD.description, OLD.expense_date, OLD.id_payment_category, OLD.scope,
          OLD.registered_by, OLD.owner_dpi, OLD.request_id, OLD.request_hash, OLD.payment_method, OLD.reference, OLD.created_at)
      OR (NOT OLD.active AND NEW IS DISTINCT FROM OLD) THEN
     RAISE EXCEPTION 'El gasto es inmutable; corrige mediante anulación y nuevo registro';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_expense BEFORE INSERT OR UPDATE ON expense_record FOR EACH ROW EXECUTE FUNCTION protect_expense_record();

CREATE FUNCTION protect_case_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.amount, NEW.legal_process_id, NEW.payment_type, NEW.payment_method, NEW.concept,
        NEW.payment_date, NEW.reference, NEW.registered_by, NEW.request_id, NEW.request_hash, NEW.created_at)
    IS DISTINCT FROM
    ROW(OLD.amount, OLD.legal_process_id, OLD.payment_type, OLD.payment_method, OLD.concept,
        OLD.payment_date, OLD.reference, OLD.registered_by, OLD.request_id, OLD.request_hash, OLD.created_at)
    OR (NOT OLD.active AND NEW IS DISTINCT FROM OLD) THEN
   RAISE EXCEPTION 'El abono es inmutable; corrige mediante anulación y nuevo registro';
 END IF;
 IF OLD.active AND NOT NEW.active AND (NEW.annulled_at IS NULL OR NEW.annulled_by IS NULL
     OR NEW.annul_reason IS NULL OR length(btrim(NEW.annul_reason)) = 0) THEN
   RAISE EXCEPTION 'La anulación requiere auditoría';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_payment BEFORE UPDATE ON case_payment FOR EACH ROW EXECUTE FUNCTION protect_case_payment();

CREATE FUNCTION prevent_financial_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Los movimientos financieros se anulan; no se eliminan';
END $$;
CREATE TRIGGER prevent_expense_delete BEFORE DELETE ON expense_record FOR EACH ROW EXECUTE FUNCTION prevent_financial_delete();
CREATE TRIGGER prevent_payment_delete BEFORE DELETE ON case_payment FOR EACH ROW EXECUTE FUNCTION prevent_financial_delete();

CREATE VIEW cash_movement AS
 SELECT 'CASE_PAYMENT'::varchar AS source, p.id::text AS source_id,
        'INGRESO'::varchar AS direction, 'TRAMITES'::varchar AS category,
        p.amount, p.concept::text AS description, p.payment_date AS movement_date,
        p.payment_method, p.reference, p.active, p.registered_by,
        NULL::varchar AS owner_dpi, p.created_at, p.legal_process_id,
        l.case_code, l.version, p.annul_reason, p.annulled_at,
        p.annulled_by
 FROM case_payment p JOIN legal_process l ON l.id = p.legal_process_id
 UNION ALL
 SELECT 'EXPENSE'::varchar, e.id::text, 'EGRESO'::varchar, c.code,
        e.amount, e.description, e.expense_date, e.payment_method,
        e.reference, e.active, e.registered_by, e.owner_dpi, e.created_at,
        NULL::bigint, NULL::varchar, e.version, e.annul_reason,
        e.annulled_at, e.annulled_by
 FROM expense_record e JOIN payment_category c ON c.id = e.id_payment_category;
