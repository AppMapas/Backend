package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LegalProcessRequirementRepository extends JpaRepository<LegalProcessRequirementEntity, Long> {
    List<LegalProcessRequirementEntity> findByLegalProcessIdOrderByDisplayOrderAsc(Long legalProcessId);
}
