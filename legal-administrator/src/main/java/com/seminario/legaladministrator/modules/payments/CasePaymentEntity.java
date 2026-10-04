package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Anticipo o abono de un expediente. Los montos son {@link BigDecimal} exactos:
 * el saldo pendiente se deriva de la suma de esta tabla, así que cualquier
 * operación con punto flotante introduciría diferencias de centavos.
 */
@Entity
@Table(name = "case_payment")
@Getter @Setter @NoArgsConstructor
public class CasePaymentEntity {
    /** Cota máxima: 12 dígitos enteros y 2 decimales, igual que numeric(14,2). */
    public static final int AMOUNT_SCALE = 2;
    public static final int MAX_AMOUNT_INTEGER_DIGITS = 12;

    @Id private UUID id;
    // Un abono no se traslada entre expedientes: si se registró en el trámite
    // equivocado se anula y se vuelve a registrar, para no reescribir el historial.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false, updatable = false)
    private LegalProcessEntity legalProcess;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 20)
    private PaymentType paymentType;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;
    @Column(nullable = false, length = 120)
    private String concept;
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;
    @Column(length = 60)
    private String reference;
    @Column(nullable = false)
    private boolean active = true;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registered_by", nullable = false, updatable = false)
    private UserSystemEntity registeredBy;
    @Column(name = "request_id", updatable = false)
    private UUID requestId;
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void beforeInsert() {
        // numeric(14,2) redondea en silencio: aquí se rechaza el monto inválido
        // en lugar de guardar un importe distinto al que registró la abogada.
        requireExactAmount();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        requireExactAmount();
        updatedAt = Instant.now();
    }

    private void requireExactAmount() {
        if (amount == null || amount.signum() <= 0) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "El monto del abono debe ser mayor que cero.");
        }
        if (amount.scale() > AMOUNT_SCALE) {
            throw new OperationException(HttpStatus.BAD_REQUEST,
                    "El monto admite un máximo de 2 decimales.");
        }
        // numeric(14,2) no admite más de 12 dígitos enteros; se rechaza aquí
        // para no dejar que PostgreSQL responda con un error de rango.
        if (amount.precision() - amount.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            throw new OperationException(HttpStatus.BAD_REQUEST,
                    "El monto supera el máximo admitido.");
        }
    }
}