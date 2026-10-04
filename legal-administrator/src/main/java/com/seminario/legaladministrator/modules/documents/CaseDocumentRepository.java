package com.seminario.legaladministrator.modules.documents;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseDocumentRepository extends JpaRepository<CaseDocumentEntity, UUID> {
    List<CaseDocumentEntity> findByLegalProcessIdOrderByUploadedAtDesc(Long caseId);
    Optional<CaseDocumentEntity> findByIdAndLegalProcessId(UUID id, Long caseId);
}
