package com.seminario.legaladministrator.modules.calculations.service;

import com.seminario.legaladministrator.modules.calculations.*;
import com.seminario.legaladministrator.modules.calculations.dto.*;
import com.seminario.legaladministrator.modules.calculations.mapper.AreaCalculationMapper;
import com.seminario.legaladministrator.modules.calculations.repository.AreaCalculationRepository;
import com.seminario.legaladministrator.modules.calculations.repository.BoundancyMeasurementsRepository;
import com.seminario.legaladministrator.modules.calculations.repository.BoundariesRepository;
import com.seminario.legaladministrator.modules.calculations.repository.SubPolygonsRepository;
import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
@Service
public class CalculationService {
    private static final String DEFAULT_LEGAL_NOTICE =
            "ESTE DOCUMENTO ES UN CÁLCULO PRELIMINAR DE REFERENCIA TÉCNICA. " +
                    "No constituye un documento legal válido para trámites de titulación o inscripción ante el Registro General de la Propiedad. " +
                    "Para validaciones, escrituras y trámites legales oficiales, debe verificar y consultar estrictamente con el abogado que lleva el proceso.";

    private final AreaCalculationRepository areaCalculationRepository;
    private final BoundariesRepository boundariesRepository;
    private final BoundancyMeasurementsRepository measurementsRepository;
    private final ClientUserRepository clientUserRepository;
    private final UserSystemRepository userSystemRepository;
    private final AreaCalculationMapper areaCalculationMapper;
    private final SubPolygonsRepository subPolygonsRepository;
    private final PolygonAreaCalculator areaCalculator;

    @Transactional
    public AreaCalculationResponseDto saveCalculation(AreaCalculationRequestDto request) {
        validatePolygonBoundaries(request.getBoundaries());

        double totalArea = resolvePolygonArea(request);
        String legalNoticeText = resolveLegalNotice(DEFAULT_LEGAL_NOTICE);

        AreaCalculationEntity savedAreaCalculation = createAndSaveBaseCalculation(request, totalArea, legalNoticeText);
        processBoundaries(savedAreaCalculation, request.getBoundaries());

        return areaCalculationMapper.toResponseDto(savedAreaCalculation);
    }

    @Transactional
    public AreaCalculationResponseDto calculateAndSavePolygon(AreaCalculationRequestDto request) {
        validatePolygonBoundaries(request.getBoundaries());

        double totalArea = resolvePolygonArea(request);
        String legalNoticeText = resolveLegalNotice(DEFAULT_LEGAL_NOTICE);

        AreaCalculationEntity savedAreaCalculation = createAndSaveBaseCalculation(request, totalArea, legalNoticeText);
        processBoundaries(savedAreaCalculation, request.getBoundaries());

        return areaCalculationMapper.toResponseDto(savedAreaCalculation);
    }

    /**
     * Método auxiliar unificado para determinar el área priorizando los vértices del canvas,
     * y respaldándose en las colindancias si fuera necesario.
     *
     * Cuando se calcula desde colindancias, usa meta.closed para decidir si se exige que
     * la poligonal cierre exactamente (terreno regular) o no (p. ej. un lindero irregular
     * tipo río/quebrada, donde no aplica un cierre matemático exacto). Si no viene meta,
     * se exige cierre por defecto (comportamiento anterior, más estricto).
     */
    private double resolvePolygonArea(AreaCalculationRequestDto request) {
        if (request.getVertices() != null && !request.getVertices().isEmpty()) {
            return areaCalculator.calculateGeometricCutArea(request.getVertices());
        }
        boolean enforceClosure = request.getMeta() == null || request.getMeta().isClosed();
        return areaCalculator.calculatePolygonAreaFromBoundaries(request.getBoundaries(), enforceClosure);
    }

    private AreaCalculationEntity createAndSaveBaseCalculation(AreaCalculationRequestDto request, double totalArea, String legalNotice) {
        ClientUserEntity client = clientUserRepository.findById(request.getClientDpi())
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado con DPI: " + request.getClientDpi()));

        UserSystemEntity userSystem = userSystemRepository.findById(request.getUserSystemId())
                .orElseThrow(() -> new RuntimeException("Usuario del sistema no encontrado con DPI: " + request.getUserSystemId()));

        byte[] decodedPlanImage = null;
        if (request.getPlanImageBase64() != null && !request.getPlanImageBase64().isBlank()) {
            try {
                String base64Image = request.getPlanImageBase64();
                if (base64Image.contains(",")) {
                    base64Image = base64Image.split(",")[1];
                }
                decodedPlanImage = java.util.Base64.getDecoder().decode(base64Image);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("El formato de la imagen del plano en Base64 es inválido.");
            }
        }

        AreaCalculationEntity entity = AreaCalculationEntity.builder()
                .clientUser(client)
                .userSystem(userSystem)
                .terrainName(request.getTerrainName())
                .generalDescription(request.getGeneralDescription())
                .propertyType(request.getPropertyType())
                .totalAreaSquareMeters(totalArea)
                .legalNotice(legalNotice)
                .location(request.getLocation())
                .planImage(decodedPlanImage)
                .createdAt(LocalDate.now())
                .build();

        return areaCalculationRepository.save(entity);
    }

    private void validatePolygonBoundaries(List<BoundaryRequestDto> boundaries) {
        if (boundaries == null || boundaries.size() < 3) {
            throw new IllegalArgumentException("El polígono debe tener al menos 3 colindancias.");
        }
    }

    private void processBoundaries(AreaCalculationEntity savedAreaCalculation, List<BoundaryRequestDto> boundaries) {
        if (boundaries == null) return;

        for (BoundaryRequestDto boundaryDto : boundaries) {
            BoundariesEntity boundary = BoundariesEntity.builder()
                    .areaCalculation(savedAreaCalculation)
                    .sideNumber(boundaryDto.getSideNumber())
                    .referencePoint(boundaryDto.getReferencePoint())
                    .orientation(boundaryDto.getOrientation() != null ? String.valueOf(boundaryDto.getOrientation()) : null)
                    .build();

            BoundariesEntity savedBoundary = boundariesRepository.save(boundary);

            if (boundaryDto.getMeasurements() != null) {
                for (MeasurementRequestDto mDto : boundaryDto.getMeasurements()) {
                    double factor = UnitConversion.getFactor(mDto.getUnit());
                    double convertedMeters = mDto.getValue() * factor;

                    BoundancyMeasurementsEntity measurementEntity = BoundancyMeasurementsEntity.builder()
                            .boundaries(savedBoundary)
                            .unitType(mDto.getUnit())
                            .originalValue(mDto.getValue())
                            .conversionFactor(factor)
                            .valueConvertedMeters(convertedMeters)
                            .build();

                    measurementsRepository.save(measurementEntity);
                }
            }
        }
    }

    private String resolveLegalNotice(String customNotice) {
        return (customNotice != null && !customNotice.isBlank()) ? customNotice : DEFAULT_LEGAL_NOTICE;
    }

    @Transactional
    public List<AreaCalculationResponseDto> splitPolygon(PolygonSplitRequestDto request) {
        AreaCalculationEntity parentPolygon = areaCalculationRepository.findById(request.getParentCalculationId())
                .orElseThrow(() -> new RuntimeException("Polígono principal no encontrado con ID: " + request.getParentCalculationId()));

        List<AreaCalculationResponseDto> subPolygonsResult = new java.util.ArrayList<>();

        if (request.getSplitLines() == null || request.getSplitLines().isEmpty()) {
            throw new IllegalArgumentException("Debe especificar al menos una línea de división o sub-lote.");
        }

        for (SplitLineDto cut : request.getSplitLines()) {
            double cutArea;
            if (cut.getBoundaries() != null && !cut.getBoundaries().isEmpty()) {
                cutArea = areaCalculator.calculatePolygonAreaFromBoundaries(cut.getBoundaries());
            } else {
                cutArea = areaCalculator.calculateGeometricCutArea(cut.getPoints());
            }

            AreaCalculationEntity subLotCalculation = AreaCalculationEntity.builder()
                    .clientUser(parentPolygon.getClientUser())
                    .userSystem(parentPolygon.getUserSystem())
                    .terrainName(parentPolygon.getTerrainName() + " - " + cut.getCutName())
                    .generalDescription("Sub-lote resultante de división por: " + cut.getCutName())
                    .propertyType(parentPolygon.getPropertyType())
                    .totalAreaSquareMeters(cutArea)
                    .legalNotice("Sub-área fraccionada de referencia técnica. Sujeta a validación notarial.")
                    .createdAt(LocalDate.now())
                    .build();

            AreaCalculationEntity savedSubLot = areaCalculationRepository.save(subLotCalculation);

            if (cut.getBoundaries() != null && !cut.getBoundaries().isEmpty()) {
                processBoundaries(savedSubLot, cut.getBoundaries());
            }

            SubPolygonsEntity subPolygonRecord = SubPolygonsEntity.builder()
                    .areaCalculation(savedSubLot)
                    .subLotName(cut.getCutName())
                    .divisionType(cut.getDivisionType() != null ? cut.getDivisionType() : "DIVISION_ESTANDAR")
                    .calcualtedAreaMeters(cutArea)
                    .areaCalculatedVaras(cutArea * 1.4311)
                    .createdAt(LocalDate.now())
                    .build();

            subPolygonsRepository.save(subPolygonRecord);

            subPolygonsResult.add(areaCalculationMapper.toResponseDto(savedSubLot));
        }

        return subPolygonsResult;
    }
}