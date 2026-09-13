package com.seminario.legaladministrator.modules.users.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToggleTwoFactorRequestDto {
    @NotNull(message = "El estado de 2FA es obligatorio")
    private Boolean enabled;
}