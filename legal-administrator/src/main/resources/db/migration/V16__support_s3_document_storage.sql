-- Ampliar sin modificar el checksum de V8 ni relabelar objetos históricos.
ALTER TABLE case_document DROP CONSTRAINT case_document_storage_provider_check;
ALTER TABLE case_document ADD CONSTRAINT case_document_storage_provider_check
    CHECK (storage_provider IN ('local', 's3', 'gcs'));
-- El valor histórico se conserva para permitir migrar sus objetos explícitamente.
-- El backend solo proporciona almacenamiento local y S3 a partir de esta versión.
