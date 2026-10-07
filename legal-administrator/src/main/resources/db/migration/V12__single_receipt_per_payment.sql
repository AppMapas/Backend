CREATE UNIQUE INDEX uk_case_document_payment_receipt ON case_document(case_payment_id)
    WHERE case_payment_id IS NOT NULL;
