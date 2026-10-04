CREATE TABLE case_document (
    id UUID PRIMARY KEY,
    legal_process_id BIGINT NOT NULL REFERENCES legal_process(id),
    original_name VARCHAR(180) NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    storage_provider VARCHAR(10) NOT NULL CHECK (storage_provider IN ('local', 'gcs')),
    object_key VARCHAR(200) NOT NULL UNIQUE,
    uploaded_by VARCHAR(15) NOT NULL REFERENCES user_system(dpi),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_case_document_process_date ON case_document(legal_process_id, uploaded_at DESC);
