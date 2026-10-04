package com.seminario.legaladministrator.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CasePaymentRepository extends JpaRepository<CasePaymentEntity, UUID> {

    /** Todos los abonos del expediente, incluidos los anulados, del más reciente al más antiguo. */
    List<CasePaymentEntity> findByLegalProcessIdOrderByPaymentDateDescIdDesc(Long caseId);

    /** Solo abonos vigentes: es la lista que usa el resumen financiero. */
    List<CasePaymentEntity> findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(Long caseId);

    Optional<CasePaymentEntity> findByIdAndLegalProcessId(UUID id, Long caseId);

    Optional<CasePaymentEntity> findByRequestId(UUID requestId);

    /**
     * Suma exacta de los abonos vigentes. El saldo pendiente se deriva restando
     * este valor al costo total del expediente; no se guarda para que no pueda
     * desincronizarse de los abonos. Devuelve 0 cuando no hay ninguno.
     */
    @Query("""
            select cast(coalesce(sum(p.amount), 0) as java.math.BigDecimal)
            from CasePaymentEntity p
            where p.legalProcess.id = :caseId and p.active = true
            """)
    BigDecimal sumActiveAmount(@Param("caseId") Long caseId);
}