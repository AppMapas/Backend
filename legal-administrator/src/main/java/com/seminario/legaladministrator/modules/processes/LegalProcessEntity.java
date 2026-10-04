package com.seminario.legaladministrator.modules.processes;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "legal_process")
@Getter @Setter @NoArgsConstructor
public class LegalProcessEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dpi_client", nullable = false, updatable = false)
    private ClientUserEntity client;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_user_system_assigned", nullable = false)
    private UserSystemEntity assignedUser;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_process_type", nullable = false, updatable = false)
    private ProcessTypeEntity processType;
    @Column(name = "current_status", nullable = false, length = 50)
    private String currentStatus = "OPEN";
    @Column(name = "general_details", columnDefinition = "text")
    private String generalDetails;
    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;
    @Column(name = "updated_at")
    private LocalDate updatedAt;
    @Column(name = "case_code", nullable = false, length = 40, updatable = false)
    private String caseCode;
    @Column(name = "process_type_name_snapshot", nullable = false, length = 100, updatable = false)
    private String processTypeNameSnapshot;
    @Column(name = "process_type_version_snapshot", nullable = false, updatable = false)
    private Long processTypeVersionSnapshot;
    @Column(nullable = false)
    private boolean active = true;
    @Version
    private Long version;
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;
    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private UserSystemEntity createdBy;
    @Column(name = "request_id", updatable = false)
    private UUID requestId;
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;
    /**
     * Costo total pactado en quetzales para este expediente (HU-08).
     * {@code null} significa que todavía no se acuerda, no que sea cero: el
     * resumen financiero debe distinguir ambos casos.
     */
    @Column(name = "total_amount", precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @PrePersist
    void beforeInsert() {
        createdAt = LocalDate.now();
        openedAt = Instant.now();
        modifiedAt = openedAt;
    }
    @PreUpdate
    void beforeUpdate() {
        updatedAt = LocalDate.now();
        modifiedAt = Instant.now();
    }
}
