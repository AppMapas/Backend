package com.seminario.legaladministrator.modules.users.repository;

import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaritalStatusRepository extends JpaRepository<MaritalStatusEntity, Long> {
}
