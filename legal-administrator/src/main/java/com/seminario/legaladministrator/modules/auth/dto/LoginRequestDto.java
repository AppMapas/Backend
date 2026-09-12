package com.seminario.legaladministrator.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequestDto {
    @NotBlank(message = "El correo electrónico es obligatorio")
    private String email;
    @NotBlank(message = "La contraseña es obligatoria")
    private String password;
}