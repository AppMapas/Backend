package com.seminario.legaladministrator.model.users;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "appointment_request")
public class AppointmentRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_client", nullable = false)
    private ClientUser clientUser;

    @Column(name = "id_user_system_assigned", length = 15)
    private String idUserSystemAssigned;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;
}