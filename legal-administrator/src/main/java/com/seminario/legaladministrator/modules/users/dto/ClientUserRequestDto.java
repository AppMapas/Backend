package com.seminario.legaladministrator.modules.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClientUserRequestDto extends ClientPersonalDataDto {
    @NotBlank(message = "El DPI es obligatorio")
    @Pattern(regexp = "[0-9]{13}", message = "El DPI debe contener exactamente 13 dígitos.")
    private String dpi;
}
