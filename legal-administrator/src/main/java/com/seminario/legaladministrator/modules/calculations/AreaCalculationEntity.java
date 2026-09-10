package com.seminario.legaladministrator.modules.calculations;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.UserSystem;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "area_calculation")
public class AreaCalculationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_client", nullable = false)
    private ClientUserEntity clientUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_user_system", nullable = false)
    private UserSystem userSystem;

    @Column(name = "terrain_name", nullable = false, length = 255)
    private String terrainName;

    @Column(name = "general_description", columnDefinition = "TEXT")
    private String generalDescription;

    @Column(name = "total_area_square_meters", nullable = false)
    private Double totalAreaSquareMeters;

    @Column(name = "legal_notice", nullable = false, columnDefinition = "TEXT")
    private String legalNotice;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;

    @Column(name = "updated_at")
    private LocalDate updatedAt;

    @Column(name = "property_type", nullable = false, length = 20)
    private String propertyType;
}