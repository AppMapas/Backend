package com.seminario.legaladministrator.model.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDate;

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
}