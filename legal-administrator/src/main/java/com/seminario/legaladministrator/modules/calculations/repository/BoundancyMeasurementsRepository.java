package com.seminario.legaladministrator.modules.calculations.repository;

import com.seminario.legaladministrator.modules.calculations.BoundancyMeasurementsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoundancyMeasurementsRepository extends JpaRepository<BoundancyMeasurementsEntity, Long> {
    List<BoundancyMeasurementsEntity> findByBoundariesId(Long boundaryId);
}
