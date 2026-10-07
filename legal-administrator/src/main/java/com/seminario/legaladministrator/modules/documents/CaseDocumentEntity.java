package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "case_document")
@Getter @Setter
public class CaseDocumentEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false)
    private LegalProcessEntity legalProcess;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legal_process_requirement_id")
    private LegalProcessRequirementEntity legalProcessRequirement;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_payment_id")
    private com.seminario.legaladministrator.modules.payments.CasePaymentEntity payment;
    @Column(name = "original_name", nullable = false, length = 180)
    private String originalName;
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "storage_provider", nullable = false, length = 10)
    private String storageProvider;
    @Column(name = "object_key", nullable = false, unique = true, length = 200)
    private String objectKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private UserSystemEntity uploadedBy;
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
