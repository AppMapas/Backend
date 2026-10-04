package com.seminario.legaladministrator.modules.processes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "process_type_requirement")
@Getter
@Setter
@NoArgsConstructor
public class ProcessTypeRequirementEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "process_type_id", nullable = false)
    private ProcessTypeEntity processType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private RequirementEntity requirement;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(columnDefinition = "TEXT")
    private String instructions;
}
