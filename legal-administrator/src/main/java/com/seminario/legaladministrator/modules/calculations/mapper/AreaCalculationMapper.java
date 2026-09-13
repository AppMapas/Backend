package com.seminario.legaladministrator.modules.calculations.mapper;

import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationResponseDto;
import org.springframework.stereotype.Component;

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

        return response;
    }
}
