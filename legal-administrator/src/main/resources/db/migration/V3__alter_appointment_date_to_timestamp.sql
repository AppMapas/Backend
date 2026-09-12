-- ==========================================
-- Migración V3: Ajustar tipo de dato de appointment_date a timestamp
-- ==========================================
ALTER TABLE appointment_request
    ALTER COLUMN appointment_date TYPE timestamp USING appointment_date::timestamp;
