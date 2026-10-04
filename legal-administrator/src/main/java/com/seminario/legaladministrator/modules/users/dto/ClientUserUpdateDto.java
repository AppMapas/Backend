package com.seminario.legaladministrator.modules.users.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClientUserUpdateDto extends ClientPersonalDataDto {
    @NotNull @PositiveOrZero
    private Long version;
}
