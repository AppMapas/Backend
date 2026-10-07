ALTER TABLE case_document
  ADD COLUMN legal_process_requirement_id BIGINT REFERENCES legal_process_requirement(id);

CREATE INDEX idx_case_document_requirement
  ON case_document(legal_process_requirement_id);
