package com.seminario.legaladministrator.modules.calculations.mapper;

import com.seminario.legaladministrator.modules.calculations.BoundariesEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationResponseDto;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class BoundaryMapper {
    public AreaCalculationResponseDto.BoundaryDto toDto(BoundariesEntity entity) {
        if (entity == null) {
            return null;
        }

        AreaCalculationResponseDto.BoundaryDto dto = new AreaCalculationResponseDto.BoundaryDto();
        dto.setSideNumber(entity.getSideNumber());
        dto.setOrientation(entity.getOrientation());
        dto.setReferencePoint(entity.getReferencePoint());

        if (entity.getMeasurements() != null) {
            dto.setMeasurements(entity.getMeasurements().stream().map(m -> {
                AreaCalculationResponseDto.MeasurementDto mDto = new AreaCalculationResponseDto.MeasurementDto();
                mDto.setId(m.getId());
                mDto.setValueConvertedMeters(m.getValueConvertedMeters());
                return mDto;
            }).collect(Collectors.toList()));
        }

        return dto;
    }
}