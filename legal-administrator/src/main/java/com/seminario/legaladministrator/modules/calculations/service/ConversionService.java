package com.seminario.legaladministrator.modules.calculations.service;

import com.seminario.legaladministrator.modules.calculations.UnitConversion;
import com.seminario.legaladministrator.modules.calculations.dto.ConversionResponseDto;
import com.seminario.legaladministrator.modules.calculations.dto.MeasurementRequestDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversionService {
    public List<ConversionResponseDto> convertMeasurements(List<MeasurementRequestDto> requests) {
        return requests.stream().map(req -> {
            double factor = UnitConversion.getFactor(req.getUnit());
            double convertedValue = req.getValue() * factor;

            return new ConversionResponseDto(
                    req.getValue(),
                    req.getUnit(),
                    convertedValue
            );
        }).toList();
    }
}