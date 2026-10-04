package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.LegalProcessStageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LegalProcessStageRepository extends JpaRepository<LegalProcessStageEntity, Long> {
    List<LegalProcessStageEntity> findByLegalProcessIdOrderByDisplayOrderAsc(Long legalProcessId);
    Optional<LegalProcessStageEntity> findByIdAndLegalProcessId(Long id, Long legalProcessId);
    Optional<LegalProcessStageEntity> findByLegalProcessIdAndCode(Long legalProcessId, String code);
    boolean existsByLegalProcessId(Long legalProcessId);
}
