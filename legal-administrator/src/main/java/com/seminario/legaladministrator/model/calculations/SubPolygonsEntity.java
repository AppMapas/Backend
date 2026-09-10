package com.seminario.legaladministrator.model.calculations;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "sub_polygons")
public class SubPolygonsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_area_calculated", nullable = false)
    private AreaCalculationEntity areaCalculation;

    @Column(name = "sub_lot_name", nullable = false, length = 100)
    private String subLotName;

    @Column(name = "division_type", nullable = false, length = 50)
    private String divisionType;

    @Column(name = "calcualted_area_meters", nullable = false)
    private Double calcualtedAreaMeters;

    @Column(name = "area_calculated_varas")
    private Double areaCalculatedVaras;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;
}