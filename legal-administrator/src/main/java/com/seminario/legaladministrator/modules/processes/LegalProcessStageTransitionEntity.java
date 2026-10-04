package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "legal_process_stage_transition")
@Getter @Setter @NoArgsConstructor
public class LegalProcessStageTransitionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false, updatable = false)
    private LegalProcessEntity legalProcess;
    @Column(name = "from_code", nullable = false, length = 40, updatable = false)
    private String fromCode;
    @Column(name = "to_code", nullable = false, length = 40, updatable = false)
    private String toCode;
}
