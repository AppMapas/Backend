package com.seminario.legaladministrator.modules.documents;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseDocumentRepository extends JpaRepository<CaseDocumentEntity, UUID> {
    List<CaseDocumentEntity> findByLegalProcessIdOrderByUploadedAtDesc(Long caseId);
    List<CaseDocumentEntity> findByLegalProcessIdAndLegalProcessRequirementIdOrderByUploadedAtDesc(Long caseId, Long requirementId);
    Optional<CaseDocumentEntity> findByIdAndLegalProcessId(UUID id, Long caseId);
    Optional<CaseDocumentEntity> findByIdAndLegalProcessIdAndLegalProcessRequirementId(UUID id, Long caseId, Long requirementId);
    long countByLegalProcessIdAndLegalProcessRequirementId(Long caseId, Long requirementId);
    void deleteByLegalProcessIdAndLegalProcessRequirementId(Long caseId, Long requirementId);
}
