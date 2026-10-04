package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.ProcessTypeStageTransitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProcessTypeStageTransitionRepository extends JpaRepository<ProcessTypeStageTransitionEntity, Long> {
    List<ProcessTypeStageTransitionEntity> findByProcessTypeIdOrderByIdAsc(Long processTypeId);
    void deleteByProcessTypeId(Long processTypeId);
}
