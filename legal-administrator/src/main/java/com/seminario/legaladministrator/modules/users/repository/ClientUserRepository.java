package com.seminario.legaladministrator.modules.users.repository;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientUserRepository extends JpaRepository<ClientUserEntity, String> {
}
