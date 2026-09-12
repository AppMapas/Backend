package com.seminario.legaladministrator.modules.calculations.service;

import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.BoundancyMeasurementsEntity;
import com.seminario.legaladministrator.modules.calculations.BoundariesEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationRequestDto;
import com.seminario.legaladministrator.modules.calculations.dto.BoundaryRequestDto;
import com.seminario.legaladministrator.modules.calculations.dto.MeasurementRequestDto;
import com.seminario.legaladministrator.modules.calculations.repository.AreaCalculationRepository;
import com.seminario.legaladministrator.modules.calculations.repository.BoundancyMeasurementsRepository;
import com.seminario.legaladministrator.modules.calculations.repository.BoundariesRepository;
import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@RequiredArgsConstructor
@Service
public class CalculationService {
    private static final double VARA_CONVERSION_FACTOR = 0.836;

    private final AreaCalculationRepository areaCalculationRepository;
    private final BoundariesRepository boundariesRepository;
    private final BoundancyMeasurementsRepository measurementsRepository;

    @Transactional
    public AreaCalculationEntity saveCalculation(AreaCalculationRequestDto request, ClientUserEntity client, UserSystemEntity userSystem) {
        // 1. Crear y guardar la entidad principal del terreno
        AreaCalculationEntity areaCalculation = AreaCalculationEntity.builder()
                .clientUser(client)
                .userSystem(userSystem)
                .terrainName(request.getTerrainName())
                .generalDescription(request.getGeneralDescription())
                .propertyType(request.getPropertyType())
                .totalAreaSquareMeters(0.0) // Se calculará o actualizará posteriormente según la lógica de polígonos
                .legalNotice("Cálculo generado bajo normativa legal de agrimensura")
                .createdAt(LocalDate.now())
                .build();

        AreaCalculationEntity savedAreaCalculation = areaCalculationRepository.save(areaCalculation);

        // 2. Iterar y guardar las colindancias (boundaries) con sus respectivas medidas
        if (request.getBoundaries() != null) {
            for (BoundaryRequestDto boundaryDto : request.getBoundaries()) {
                BoundariesEntity boundary = BoundariesEntity.builder()
                        .areaCalculation(savedAreaCalculation)
                        .sideNumber(boundaryDto.getSideNumber())
                        .referencePoint(boundaryDto.getReferencePoint())
                        .orientation(boundaryDto.getOrientation())
                        .build();

                BoundariesEntity savedBoundary = boundariesRepository.save(boundary);

                // 3. Procesar las medidas y aplicar la conversión (1 vara = 0.836 metros)
                if (boundaryDto.getMeasurements() != null) {
                    for (MeasurementRequestDto measurementDto : boundaryDto.getMeasurements()) {
                        double factor = getConversionFactor(measurementDto.getUnit());
                        double convertedMeters = measurementDto.getValue() * factor;

                        BoundancyMeasurementsEntity measurementEntity = BoundancyMeasurementsEntity.builder()
                                .boundaries(savedBoundary)
                                .unitType(measurementDto.getUnit())
                                .originalValue(measurementDto.getValue())
                                .conversionFactor(factor)
                                .valueConvertedMeters(convertedMeters)
                                .build();

                        measurementsRepository.save(measurementEntity);
                    }
                }
            }
        }

        return savedAreaCalculation;
    }

    private double getConversionFactor(String unitType) {
        if (unitType == null) return 1.0;
        return switch (unitType.toLowerCase()) {
            case "vara", "varas" -> VARA_CONVERSION_FACTOR;
            default -> 1.0; // Metros u otras unidades base
        };
    }
}