package com.seminario.legaladministrator.modules.processes;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "legal_process_stage_event")
@Getter @Setter @NoArgsConstructor
public class LegalProcessStageEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false, updatable = false)
    private LegalProcessEntity legalProcess;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_stage_id", updatable = false)
    private LegalProcessStageEntity fromStage;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_stage_id", nullable = false, updatable = false)
    private LegalProcessStageEntity toStage;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_dpi", nullable = false, updatable = false)
    private UserSystemEntity actor;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(length = 1000, updatable = false)
    private String comment;
    @Column(name = "request_id", updatable = false)
    private UUID requestId;
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;
}
