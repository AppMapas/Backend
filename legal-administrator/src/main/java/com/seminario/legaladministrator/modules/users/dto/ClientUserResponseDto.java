package com.seminario.legaladministrator.modules.users.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientUserResponseDto {
    private String dpi;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private LocalDate createdAt;
    private LocalDate birthDate;
    private Long maritalStatusId;
    private Long nationalityId;
    private String occupation;
    private String exactAddress;
    private Long municipalityId;
    private boolean active;
    private Long version;
    private Instant updatedAt;
}
