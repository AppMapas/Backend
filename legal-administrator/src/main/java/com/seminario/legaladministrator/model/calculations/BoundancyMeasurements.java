package com.seminario.legaladministrator.model.calculations;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "boundancy_measurements")
public class BoundancyMeasurements {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_boundaries", nullable = false)
    private Boundaries boundaries;

    @Column(name = "unit_type", nullable = false, length = 20)
    private String unitType;

    @Column(name = "original_value", nullable = false)
    private Double originalValue;

    @Column(name = "conversion_factor", nullable = false)
    private Double conversionFactor;

    @Column(name = "value_converted_meters", nullable = false)
    private Double valueConvertedMeters;
}