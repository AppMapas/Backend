package com.seminario.legaladministrator.modules.users.repository;

import com.seminario.legaladministrator.modules.users.UserPhoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserPhoneRepository extends JpaRepository<UserPhoneEntity,Long> {
    List<UserPhoneEntity> findByUserSystemDpi(String dpi);
}
