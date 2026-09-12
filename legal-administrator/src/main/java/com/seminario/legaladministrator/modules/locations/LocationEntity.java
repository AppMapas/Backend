package com.seminario.legaladministrator.modules.locations;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "location")
public class LocationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_user", nullable = false)
    private UserSystemEntity userSystem;

    @Column(name = "exact_address", nullable = false, length = 255)
    private String exactAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_municipality", nullable = false)
    private MunicipalityEntity municipality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "numerical_code_department", nullable = false)
    private DepartmentEntity department;
}