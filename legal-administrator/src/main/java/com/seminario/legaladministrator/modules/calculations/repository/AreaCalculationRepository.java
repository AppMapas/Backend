package com.seminario.legaladministrator.modules.calculations.repository;

import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AreaCalculationRepository extends JpaRepository<AreaCalculationEntity, Long> {
}
