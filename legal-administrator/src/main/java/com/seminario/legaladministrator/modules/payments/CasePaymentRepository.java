package com.seminario.legaladministrator.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CasePaymentRepository extends JpaRepository<CasePaymentEntity, UUID> {

    /**
     * Todos los abonos del expediente, incluidos los anulados, del más reciente al
     * más antiguo. El resumen financiero se deriva de esta misma lista para que las
     * cifras y las filas que las originan nunca discrepen.
     */
    List<CasePaymentEntity> findByLegalProcessIdOrderByPaymentDateDescIdDesc(Long caseId);

    /** Solo abonos vigentes, para cuando el listado no necesita mostrar los anulados. */
    List<CasePaymentEntity> findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(Long caseId);

    Optional<CasePaymentEntity> findByIdAndLegalProcessId(UUID id, Long caseId);

    Optional<CasePaymentEntity> findByRequestId(UUID requestId);
}