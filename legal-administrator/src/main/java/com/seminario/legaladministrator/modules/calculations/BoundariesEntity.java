package com.seminario.legaladministrator.modules.calculations;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "boundaries")
public class BoundariesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_area_calculation", nullable = false)
    private AreaCalculationEntity areaCalculation;

    @Column(name = "side_number", nullable = false)
    private Long sideNumber;

    @Column(name = "reference_point", length = 150)
    private String referencePoint;

    @Column(length = 255)
    private String orientation;

    @OneToMany(mappedBy = "boundaries", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BoundancyMeasurementsEntity> measurements;
}