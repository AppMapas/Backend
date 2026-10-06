package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.modules.payments.PaymentMethod;
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

@Entity @Table(name="expense_record")
@Getter @Setter @NoArgsConstructor
public class ExpenseRecordEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, updatable=false, length=20) private String scope;
    @Column(name="id_payment_category", nullable=false, updatable=false) private Long categoryId;
    @Column(nullable=false, updatable=false, precision=14, scale=2) private BigDecimal amount;
    @Column(nullable=false, updatable=false, columnDefinition="text") private String description;
    @Column(name="expense_date", nullable=false, updatable=false) private LocalDate date;
    @Enumerated(EnumType.STRING) @Column(name="payment_method", nullable=false, updatable=false, length=20)
    private PaymentMethod paymentMethod;
    @Column(updatable=false, length=60) private String reference;
    @Column(name="registered_by", nullable=false, updatable=false, length=15) private String registeredBy;
    @Column(name="owner_dpi", updatable=false, length=15) private String ownerDpi;
    @Column(nullable=false) private boolean active = true;
    @Version private Long version;
    @Column(name="request_id", nullable=false, updatable=false) private UUID requestId;
    @Column(name="request_hash", nullable=false, updatable=false, length=64) private String requestHash;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="annulled_at") private Instant annulledAt;
    @Column(name="annulled_by", length=15) private String annulledBy;
    @Column(name="annul_reason", length=500) private String annulReason;
    @PrePersist void beforeInsert() {
        requireExactAmount();
        createdAt = Instant.now();
    }
    @PreUpdate void beforeUpdate() { requireExactAmount(); }
    private void requireExactAmount() {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.precision() - amount.scale() > 12) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "El monto debe ser positivo y exacto a dos decimales.");
        }
    }
}
