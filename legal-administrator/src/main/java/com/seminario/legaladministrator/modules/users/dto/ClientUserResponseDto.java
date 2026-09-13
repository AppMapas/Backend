package com.seminario.legaladministrator.modules.users.dto;

import lombok.*;

import java.time.LocalDate;

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
}