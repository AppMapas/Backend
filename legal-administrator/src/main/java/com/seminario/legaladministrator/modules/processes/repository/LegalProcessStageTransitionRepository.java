package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.LegalProcessStageTransitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LegalProcessStageTransitionRepository extends JpaRepository<LegalProcessStageTransitionEntity, Long> {
    List<LegalProcessStageTransitionEntity> findByLegalProcessIdOrderByIdAsc(Long legalProcessId);
    boolean existsByLegalProcessIdAndFromCodeAndToCode(Long legalProcessId, String fromCode, String toCode);
}
