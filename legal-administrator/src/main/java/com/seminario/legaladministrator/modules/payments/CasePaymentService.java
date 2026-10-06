package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Anticipos y abonos del expediente. El saldo pendiente nunca se guarda: se
 * deriva aquí de los abonos vigentes para que no pueda quedar desactualizado.
 * <p>
 * Todas las cifras se aritmetizan con {@link BigDecimal} a escala 2. Con
 * {@code double}, un saldo de 0.10 + 0.20 + 1000.00 no cuadraría con la lista
 * de abonos que se muestra debajo, y la discordancia haría desconfiar del resumen.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class CasePaymentService {
    /** Escala única de los montos, igual que la columna numeric(14,2). */
    private static final int SCALE = CasePaymentEntity.AMOUNT_SCALE;

    private final CasePaymentRepository payments;
    private final LegalProcessRepository cases;
    private final OfficeAccess officeAccess;
    private final CaseRequestGuard requestGuard;
    private final Validator validator;

    @Transactional(readOnly = true)
    public Ledger ledger(Long caseId) {
        return ledger(requireCase(caseId));
    }

    @Transactional
    public Creation register(Long caseId, CreatePaymentRequest request) {
        validate(request);
        var operator = officeAccess.current();
        String fingerprint = fingerprint(caseId, request);
        // Bloquea solo esta clave: dos toques simultáneos no crean dos abonos.
        requestGuard.lock(request.requestId());
        var previous = payments.findByRequestId(request.requestId());
        if (previous.isPresent()) {
            var existing = previous.get();
            if (!operator.getDpi().equals(existing.getRegisteredBy().getDpi())
                    || !fingerprint.equals(existing.getRequestHash())) {
                throw new OperationException(HttpStatus.CONFLICT,
                        "La clave de solicitud ya se utilizó. Reutilízala únicamente para reintentar el mismo abono.");
            }
            return new Creation(ledger(requireCase(caseId)), true);
        }

        if (requestGuard.identifiesExpense(request.requestId())) {
            throw new OperationException(HttpStatus.CONFLICT, "La solicitud ya identifica un gasto.");
        }
        var legalCase = requireActiveCase(caseId);

        var entity = new CasePaymentEntity();
        entity.setId(UUID.randomUUID());
        entity.setLegalProcess(legalCase);
        entity.setAmount(money(request.amount()));
        entity.setPaymentType(request.paymentType());
        entity.setPaymentMethod(request.paymentMethod());
        entity.setConcept(InputRules.required(request.concept(), 120, "El concepto"));
        entity.setPaymentDate(request.paymentDate());
        entity.setReference(InputRules.text(request.reference()));
        entity.setRegisteredBy(operator);
        entity.setRequestId(request.requestId());
        entity.setRequestHash(fingerprint);
        payments.saveAndFlush(entity);
        return new Creation(ledger(legalCase), false);
    }

    @Transactional
    public Ledger setTotalAmount(Long caseId, TotalAmountRequest request) {
        validate(request);
        var legalCase = requireActiveCase(caseId);
        if (!Objects.equals(legalCase.getVersion(), request.version())) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente cambió. Recarga su información.");
        }
        // null devuelve el expediente al estado "costo no pactado".
        legalCase.setTotalAmount(request.totalAmount() == null ? null : money(request.totalAmount()));
        cases.saveAndFlush(legalCase);
        return ledger(legalCase);
    }

    /**
     * Anula un abono: la fila se conserva con {@code active = false}. Un registro
     * financiero se corrige, no se borra, para que el historial siga auditable.
     */
    @Transactional
    public Ledger annul(Long caseId, UUID paymentId) {
        return annulWithReason(caseId, paymentId, null, "Anulación registrada desde el expediente.", false);
    }

    @Transactional
    public Ledger annulFromCash(Long caseId, UUID paymentId, Long version, String reason) {
        return annulWithReason(caseId, paymentId, version, InputRules.required(reason, 500, "El motivo"), true);
    }

    private Ledger annulWithReason(Long caseId, UUID paymentId, Long version, String reason, boolean replayable) {
        var legalCase = lockCase(caseId);
        var operator = officeAccess.current();
        var entity = payments.findByIdAndLegalProcessId(paymentId, caseId)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND,
                        "El abono no existe en este expediente."));
        if (!entity.isActive()) {
            if (replayable && Objects.equals(reason, entity.getAnnulReason())
                    && Objects.equals(operator.getDpi(), entity.getAnnulledBy())) return ledger(legalCase);
            throw new OperationException(HttpStatus.CONFLICT, "El abono ya está anulado.");
        }
        if (!legalCase.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        }
        if (version != null && !Objects.equals(version, legalCase.getVersion())) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente cambió. Recarga su información.");
        }
        entity.setActive(false);
        entity.setAnnulledAt(java.time.Instant.now());
        entity.setAnnulledBy(operator.getDpi());
        entity.setAnnulReason(reason);
        payments.saveAndFlush(entity);
        return ledger(legalCase);
    }

    /**
     * Resumen y abonos del expediente desde una sola consulta: así las cifras y
     * las filas que las originan siempre coinciden, sin que dos consultas
     * separadas puedan mostrar importes que no concuerdan.
     */
    private Ledger ledger(LegalProcessEntity legalCase) {
        var items = new ArrayList<PaymentResponse>();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal advances = BigDecimal.ZERO;
        for (var entity : payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(legalCase.getId())) {
            if (entity.isActive()) {
                paid = paid.add(entity.getAmount());
                if (entity.getPaymentType() == PaymentType.ANTICIPO) {
                    advances = advances.add(entity.getAmount());
                }
            }
            items.add(PaymentResponse.of(entity));
        }
        paid = money(paid);
        advances = money(advances);
        var total = legalCase.getTotalAmount() == null ? null : money(legalCase.getTotalAmount());
        var pending = total == null ? null : money(total.subtract(paid));
        return new Ledger(total, paid, advances, pending, total != null,
                pending != null && pending.signum() == 0,
                pending != null && pending.signum() < 0,
                legalCase.getVersion(), legalCase.isActive(), List.copyOf(items));
    }

    /**
     * Huella de la solicitud para la idempotencia. Los montos se normalizan a dos
     * decimales: sin eso, un reintento con {@code 10.0} en lugar de {@code 10.00}
     * llegaría como un abono distinto y el cliente perdería su registro.
     */
    private String fingerprint(Long caseId, CreatePaymentRequest request) {
        var values = new ArrayList<Object>();
        values.add(caseId);
        values.add(money(request.amount()).toPlainString());
        values.add(request.paymentType().name());
        values.add(request.paymentMethod().name());
        values.add(InputRules.text(request.concept()));
        values.add(request.paymentDate().toString());
        values.add(InputRules.text(request.reference()));
        return requestGuard.fingerprint(values);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new OperationException(HttpStatus.BAD_REQUEST,
                    "La solicitud está incompleta o contiene datos no válidos.");
        }
    }

    private LegalProcessEntity requireCase(Long id) {
        if (id == null || id < 1) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Identificador no válido.");
        }
        return cases.findById(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND,
                "Expediente no encontrado."));
    }

    private LegalProcessEntity requireActiveCase(Long id) {
        var legalCase = lockCase(id);
        if (!legalCase.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        }
        return legalCase;
    }

    private LegalProcessEntity lockCase(Long id) {
        if (id == null || id < 1) throw new OperationException(HttpStatus.BAD_REQUEST, "Identificador no válido.");
        var legalCase = cases.findForUpdate(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND,
                "Expediente no encontrado."));
        return legalCase;
    }
}
