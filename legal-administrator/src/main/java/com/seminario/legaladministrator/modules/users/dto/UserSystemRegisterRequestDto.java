package com.seminario.legaladministrator.modules.users.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSystemRegisterRequestDto {
    @NotBlank(message = "El DPI es obligatorio")
    @Size(max = 15, message = "El DPI no puede exceder los 15 caracteres")
    private String dpi;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede exceder los 100 caracteres")
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 100, message = "El apellido no puede exceder los 100 caracteres")
    private String lastName;

    @NotNull(message = "La edad es obligatoria")
    private Integer age;

    @NotBlank(message = "El correo electrónico es obligatorio")
    @Email(message = "Debe proporcionar un correo electrónico válido")
    private String email;

    @NotBlank(message = "La contraseña es obligatoria")
    @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
    private String password;

    @NotNull(message = "El identificador de estado civil es obligatorio")
    private Long idMaritalStatus;

    @NotNull(message = "El identificador de nacionalidad es obligatorio")
    private Long idNationality;

    @NotNull(message = "El identificador de rol es obligatorio")
    private Long idRole;
}