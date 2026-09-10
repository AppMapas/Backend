package com.seminario.legaladministrator.modules.calculations.repository;

import com.seminario.legaladministrator.modules.calculations.SubPolygonsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubPolygonsRepository extends JpaRepository<SubPolygonsEntity, Long> {
    List<SubPolygonsEntity> findByAreaCalculationId(Long areaCalculationId);
}
