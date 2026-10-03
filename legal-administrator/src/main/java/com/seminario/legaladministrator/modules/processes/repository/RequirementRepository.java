package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementRepository extends JpaRepository<RequirementEntity, Long> {
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
