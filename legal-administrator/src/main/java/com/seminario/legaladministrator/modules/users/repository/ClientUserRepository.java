package com.seminario.legaladministrator.modules.users.repository;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface ClientUserRepository extends JpaRepository<ClientUserEntity, String>, JpaSpecificationExecutor<ClientUserEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClientUserEntity c where c.dpi = :dpi")
    Optional<ClientUserEntity> findForUpdate(@Param("dpi") String dpi);
}
