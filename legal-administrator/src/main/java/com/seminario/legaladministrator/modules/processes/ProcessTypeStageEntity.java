package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "process_type_stage")
@Getter @Setter @NoArgsConstructor
public class ProcessTypeStageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "process_type_id", nullable = false)
    private ProcessTypeEntity processType;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "is_initial", nullable = false)
    private boolean initial;
    @Column(name = "is_terminal", nullable = false)
    private boolean terminal;
}
