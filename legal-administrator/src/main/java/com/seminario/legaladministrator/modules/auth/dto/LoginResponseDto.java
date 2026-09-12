package com.seminario.legaladministrator.modules.auth.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseDto {
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private String accessToken;
    private String refreshToken;
    private boolean twoFactorRequired;
    private String message;
}