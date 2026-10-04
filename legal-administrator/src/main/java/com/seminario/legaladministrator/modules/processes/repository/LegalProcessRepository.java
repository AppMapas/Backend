package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LegalProcessRepository extends JpaRepository<LegalProcessEntity, Long>,
        JpaSpecificationExecutor<LegalProcessEntity> {
    @EntityGraph(attributePaths = {"client", "assignedUser", "processType"})
    Optional<LegalProcessEntity> findByRequestId(UUID requestId);
    @EntityGraph(attributePaths = {"client", "assignedUser", "processType"})
    Optional<LegalProcessEntity> findById(Long id);
    @Override
    @EntityGraph(attributePaths = {"client", "assignedUser", "processType"})
    Page<LegalProcessEntity> findAll(org.springframework.data.jpa.domain.Specification<LegalProcessEntity> criteria,
                                   Pageable pageable);
    @Query(value = "select 'EXP-' || nextval('legal_process_case_code_seq')", nativeQuery = true)
    String nextCaseCode();
}
