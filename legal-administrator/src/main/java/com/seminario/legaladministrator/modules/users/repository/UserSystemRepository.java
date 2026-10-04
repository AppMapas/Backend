package com.seminario.legaladministrator.modules.users.repository;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSystemRepository extends JpaRepository<UserSystemEntity, String> {
    @EntityGraph(attributePaths = "role")
    Optional<UserSystemEntity> findByEmail(String email);
}
