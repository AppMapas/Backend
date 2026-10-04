package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.ProcessTypeStageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProcessTypeStageRepository extends JpaRepository<ProcessTypeStageEntity, Long> {
    List<ProcessTypeStageEntity> findByProcessTypeIdOrderByDisplayOrderAsc(Long processTypeId);
    void deleteByProcessTypeId(Long processTypeId);
}
