package com.seminario.legaladministrator.modules.calculations.mapper;

import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationResponseDto;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class AreaCalculationMapper {
    public AreaCalculationResponseDto toResponseDto(AreaCalculationEntity entity) {
        if (entity == null) {
            return null;
        }

        AreaCalculationResponseDto.ClientRefDto clientRef = new AreaCalculationResponseDto.ClientRefDto();
        if (entity.getClientUser() != null) {
            clientRef.setDpi(entity.getClientUser().getDpi());
        }

        AreaCalculationResponseDto.UserSystemRefDto userRef = new AreaCalculationResponseDto.UserSystemRefDto();
        if (entity.getUserSystem() != null) {
            userRef.setDpi(entity.getUserSystem().getDpi());
        }

        AreaCalculationResponseDto response = new AreaCalculationResponseDto();
        response.setId(entity.getId());
        response.setClientUser(clientRef);
        response.setUserSystem(userRef);
        response.setTerrainName(entity.getTerrainName());
        response.setGeneralDescription(entity.getGeneralDescription());
        response.setTotalAreaSquareMeters(entity.getTotalAreaSquareMeters());
        response.setLegalNotice(entity.getLegalNotice());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setPropertyType(entity.getPropertyType());
        response.setLocation(entity.getLocation());

        if (entity.getBoundaries() != null) {
            response.setBoundaries(entity.getBoundaries().stream().map(boundary -> {
                AreaCalculationResponseDto.BoundaryDto bDto = new AreaCalculationResponseDto.BoundaryDto();
                bDto.setSideNumber(boundary.getSideNumber());
                bDto.setOrientation(boundary.getOrientation());
                bDto.setReferencePoint(boundary.getReferencePoint());

                if (boundary.getMeasurements() != null) {
                    bDto.setMeasurements(boundary.getMeasurements().stream().map(m -> {
                        AreaCalculationResponseDto.MeasurementDto mDto = new AreaCalculationResponseDto.MeasurementDto();
                        mDto.setId(m.getId());
                        mDto.setValueConvertedMeters(m.getValueConvertedMeters());
                        return mDto;
                    }).collect(Collectors.toList()));
                }

                return bDto;
            }).collect(Collectors.toList()));
        }

        return response;
    }
}
