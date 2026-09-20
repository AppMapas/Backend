package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

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
    private String location;
    private List<BoundaryDto> boundaries;

    @Data
    public static class ClientRefDto {
        private String dpi;
    }

    @Data
    public static class UserSystemRefDto {
        private String dpi;
    }

    @Data
    public static class BoundaryDto {
        private Long sideNumber;
        private String orientation;
        private String referencePoint;
        private List<MeasurementDto> measurements;
    }

    @Data
    public static class MeasurementDto {
        private Long id;
        private Double valueConvertedMeters;
    }
}