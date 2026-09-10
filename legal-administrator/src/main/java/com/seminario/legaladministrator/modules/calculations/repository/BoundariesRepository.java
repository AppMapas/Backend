package com.seminario.legaladministrator.modules.calculations.repository;

import com.seminario.legaladministrator.modules.calculations.BoundariesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BoundariesRepository extends JpaRepository<BoundariesEntity, Long> {

}