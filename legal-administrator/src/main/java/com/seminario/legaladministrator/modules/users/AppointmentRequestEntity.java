package com.seminario.legaladministrator.modules.users;

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
@Table(name = "appointment_request")
public class AppointmentRequestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dpi_client", nullable = false)
    private ClientUserEntity clientUser;

    @Column(name = "id_user_system_assigned", length = 15)
    private String idUserSystemAssigned;

    @Column(name = "id_legal_process")
    private Long idLegalProcess;

    @Column(name = "location_place", nullable = false)
    private String locationPlace;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "appointment_date", nullable = false)
    private LocalDateTime appointmentDate;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "google_event_id", unique = true)
    private String googleEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt;
}