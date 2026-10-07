package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "legal_process_requirement")
@Getter @Setter @NoArgsConstructor
public class LegalProcessRequirementEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false)
    private LegalProcessEntity legalProcess;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_requirement_id")
    private RequirementEntity sourceRequirement;
    @Column(name = "name_snapshot", nullable = false, length = 150)
    private String nameSnapshot;
    @Column(name = "description_snapshot", columnDefinition = "text")
    private String descriptionSnapshot;
    @Column(name = "instructions_snapshot", columnDefinition = "text")
    private String instructionsSnapshot;
    @Column(name = "is_required_snapshot", nullable = false)
    private boolean requiredSnapshot;
    @Column(name = "requires_document_snapshot", nullable = false)
    private boolean requiresDocumentSnapshot;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(nullable = false, length = 20)
    private String status = "PENDING";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @PrePersist
    void beforeInsert() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }
}
