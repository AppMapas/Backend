package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.ProcessTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessTypeRepository extends JpaRepository<ProcessTypeEntity, Long> {
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
