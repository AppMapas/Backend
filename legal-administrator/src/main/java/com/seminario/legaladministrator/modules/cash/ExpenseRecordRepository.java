package com.seminario.legaladministrator.modules.cash;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRecordRepository extends JpaRepository<ExpenseRecordEntity, Long> {
    Optional<ExpenseRecordEntity> findByRequestId(UUID requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ExpenseRecordEntity e where e.id = :id")
    Optional<ExpenseRecordEntity> findForUpdate(@Param("id") Long id);
}
