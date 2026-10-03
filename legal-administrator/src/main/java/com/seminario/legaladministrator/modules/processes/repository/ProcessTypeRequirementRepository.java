package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.ProcessTypeRequirementEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProcessTypeRequirementRepository extends JpaRepository<ProcessTypeRequirementEntity, Long> {
    @EntityGraph(attributePaths = "requirement")
    List<ProcessTypeRequirementEntity> findByProcessTypeIdOrderByDisplayOrderAsc(Long processTypeId);

    boolean existsByRequirementIdAndProcessTypeStatus(Long requirementId, ProcessTypeStatus status);

    @Modifying
    @Query("delete from ProcessTypeRequirementEntity link where link.processType.id = :processTypeId")
    void deleteAllByProcessTypeId(@Param("processTypeId") Long processTypeId);
}
