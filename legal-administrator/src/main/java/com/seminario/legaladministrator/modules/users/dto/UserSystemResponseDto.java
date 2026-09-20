package com.seminario.legaladministrator.modules.users.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSystemResponseDto {
    private String dpi;
    private String firstName;
    private String lastName;
    private Integer age;
    private String email;
    private String maritalStatusName;
    private String nationalityName;
    private String roleName;
    private LocalDate createdAt;
    private boolean twoFactorEnabled;
}
