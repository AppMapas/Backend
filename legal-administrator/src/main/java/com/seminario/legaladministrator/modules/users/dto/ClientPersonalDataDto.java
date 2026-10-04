package com.seminario.legaladministrator.modules.users.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class ClientPersonalDataDto {
    @NotBlank @Size(max = 100)
    private String firstName;
    @NotBlank @Size(max = 100)
    private String lastName;
    @NotBlank @Email @Size(max = 100)
    private String email;
    @NotBlank @Pattern(regexp = "\\+?[0-9]{8,12}", message = "El teléfono debe tener de 8 a 12 dígitos, con + opcional.")
    private String phone;
    @Past
    private LocalDate birthDate;
    @NotNull @Positive
    private Long maritalStatusId;
    @NotNull @Positive
    private Long nationalityId;
    @Size(max = 150)
    private String occupation;
    @NotBlank @Size(max = 255)
    private String exactAddress;
    @Positive
    private Long municipalityId;
}
