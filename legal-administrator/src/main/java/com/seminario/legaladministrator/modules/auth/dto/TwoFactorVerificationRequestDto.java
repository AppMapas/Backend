package com.seminario.legaladministrator.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TwoFactorVerificationRequestDto {
    private String email;

    @NotBlank(message = "El código 2FA es obligatorio")
    private String code;
}
