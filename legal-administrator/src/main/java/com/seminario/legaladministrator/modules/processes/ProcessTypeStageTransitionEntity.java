package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "process_type_stage_transition")
@Getter @Setter @NoArgsConstructor
public class ProcessTypeStageTransitionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "process_type_id", nullable = false)
    private ProcessTypeEntity processType;
    @Column(name = "from_code", nullable = false, length = 40)
    private String fromCode;
    @Column(name = "to_code", nullable = false, length = 40)
    private String toCode;
}
