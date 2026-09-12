package com.seminario.legaladministrator.modules.users;

import com.seminario.legaladministrator.modules.locations.CountryEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "user_system")
public class UserSystemEntity {
    @Id
    @Column(length = 15, nullable = false)
    private String dpi;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, name = "age")
    private Integer age;

    @Column(nullable = false, length = 100, name = "email")
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_marital_status", nullable = false)
    private MaritalStatusEntity maritalStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_nationality", nullable = false)
    private CountryEntity nationality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_role", nullable = false)
    private RoleEntity role;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;

    @Builder.Default
    @Column(name = "two_factor_enabled", nullable = false)
    private boolean twoFactorEnabled = false;

    @Column(name = "two_factor_code", length = 255)
    private String twoFactorCode;

    @Column(name = "two_factor_expiry")
    private LocalDateTime twoFactorExpiry;
}