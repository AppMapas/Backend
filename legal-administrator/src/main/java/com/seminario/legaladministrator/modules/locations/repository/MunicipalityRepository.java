package com.seminario.legaladministrator.modules.locations.repository;
import com.seminario.legaladministrator.modules.locations.MunicipalityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Sort;
import java.util.List;

public interface MunicipalityRepository extends JpaRepository<MunicipalityEntity, Long> {
    @Override
    @EntityGraph(attributePaths = "department")
    List<MunicipalityEntity> findAll(Sort sort);
}
