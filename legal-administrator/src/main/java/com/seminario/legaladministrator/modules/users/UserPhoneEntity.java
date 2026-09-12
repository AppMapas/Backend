package com.seminario.legaladministrator.modules.users;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "user_phone")
public class UserPhoneEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_user", nullable = false)
    private UserSystemEntity userSystem;

    @Column(nullable = false, length = 12)
    private String phone;
}