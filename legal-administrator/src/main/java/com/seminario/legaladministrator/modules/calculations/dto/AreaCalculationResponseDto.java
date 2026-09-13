package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaCalculationResponseDto {
    private Long id;
    private ClientRefDto clientUser;
    private UserSystemRefDto userSystem;
    private String terrainName;
    private String generalDescription;
    private Double totalAreaSquareMeters;
    private String legalNotice;
    private LocalDate createdAt;
    private LocalDate updatedAt;
    private String propertyType;

    @Data
    public static class ClientRefDto {
        private String dpi;
    }

    @Data
    public static class UserSystemRefDto {
        private String dpi;
    }
}