ALTER TABLE case_document ADD COLUMN case_payment_id UUID REFERENCES case_payment(id);
CREATE INDEX idx_case_document_payment ON case_document(case_payment_id);
