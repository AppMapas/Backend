package com.seminario.legaladministrator.modules.locations.repository;

import com.seminario.legaladministrator.modules.locations.CountryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CountryRepository extends JpaRepository<CountryEntity, Long> {
    Optional<CountryEntity> findByName(String name);
    Optional<CountryEntity> findByIsoCode(String isoCode);
}
