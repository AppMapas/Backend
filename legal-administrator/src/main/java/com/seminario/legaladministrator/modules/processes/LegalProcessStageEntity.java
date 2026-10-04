package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "legal_process_stage")
@Getter @Setter @NoArgsConstructor
public class LegalProcessStageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_process_id", nullable = false, updatable = false)
    private LegalProcessEntity legalProcess;
    @Column(nullable = false, length = 40, updatable = false)
    private String code;
    @Column(name = "name_snapshot", nullable = false, length = 150, updatable = false)
    private String nameSnapshot;
    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;
    @Column(name = "is_initial", nullable = false, updatable = false)
    private boolean initial;
    @Column(name = "is_terminal", nullable = false, updatable = false)
    private boolean terminal;
}
