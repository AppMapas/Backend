package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.ProcessTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ProcessTypeRepository extends JpaRepository<ProcessTypeEntity, Long> {
    java.util.List<ProcessTypeEntity> findByStatusOrderByNameAsc(
            com.seminario.legaladministrator.modules.processes.ProcessTypeStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProcessTypeEntity p where p.id = :id")
    Optional<ProcessTypeEntity> findForUpdate(@Param("id") Long id);
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
