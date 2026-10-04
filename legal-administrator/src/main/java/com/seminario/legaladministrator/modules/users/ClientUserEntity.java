package com.seminario.legaladministrator.modules.users;

import jakarta.persistence.*;
import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.locations.MunicipalityEntity;
import lombok.*;

import java.time.LocalDate;
import java.time.Instant;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "client_user")
public class ClientUserEntity {
    @Id
    @Column(length = 15, nullable = false)
    private String dpi;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 100, name = "email")
    private String email;

    @Column(nullable = false, length = 13, name = "phone")
    private String phone;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;

    private LocalDate birthDate;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_marital_status")
    private MaritalStatusEntity maritalStatus;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_nationality")
    private CountryEntity nationality;
    @Column(length = 150)
    private String occupation;
    @Column(name = "exact_address", length = 255)
    private String exactAddress;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_municipality")
    private MunicipalityEntity municipality;
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
    @Version
    private Long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @PrePersist
    void beforeInsert() {
        if (createdAt == null) createdAt = LocalDate.now();
        updatedAt = Instant.now();
    }
    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }
}
