package com.seminario.legaladministrator.model.users;

import com.seminario.legaladministrator.model.locations.Departament;
import com.seminario.legaladministrator.model.locations.Municipality;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "location")
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_user", nullable = false)
    private UserSystem userSystem;

    @Column(name = "exact_address", nullable = false, length = 255)
    private String exactAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_municipality", nullable = false)
    private Municipality municipality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "numerical_code_departament", nullable = false)
    private Departament departament;
}