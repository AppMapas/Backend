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
                    "No constituye un documento legal válido para trámites de titulación o inscripción ante el Registro General de la Propiedad[cite: 1]. " +
                    "Para validaciones, escrituras y trámites legales oficiales, debe verificar y consultar estrictamente con el abogado que lleva el proceso.";

    private final AreaCalculationRepository areaCalculationRepository;
    private final BoundariesRepository boundariesRepository;
    private final BoundancyMeasurementsRepository measurementsRepository;
    private final ClientUserRepository clientUserRepository;
    private final UserSystemRepository userSystemRepository;
    private final AreaCalculationMapper areaCalculationMapper;
    private final SubPolygonsRepository subPolygonsRepository;

    @Transactional
    public AreaCalculationResponseDto saveCalculation(AreaCalculationRequestDto request) {
        AreaCalculationEntity savedAreaCalculation = createAndSaveBaseCalculation(request, 0.0, "Cálculo generado bajo normativa legal de agrimensura");
        processBoundaries(savedAreaCalculation, request.getBoundaries());
        return areaCalculationMapper.toResponseDto(savedAreaCalculation);
    }

    @Transactional
    public AreaCalculationResponseDto calculateAndSavePolygon(AreaCalculationRequestDto request) {
        validatePolygonBoundaries(request.getBoundaries());

        double[] sideLengthsInMeters = calculateSideLengths(request.getBoundaries());
        double totalArea = calculatePolygonArea(sideLengthsInMeters);

        String legalNoticeText = resolveLegalNotice(DEFAULT_LEGAL_NOTICE);

        AreaCalculationEntity savedAreaCalculation = createAndSaveBaseCalculation(request, totalArea, legalNoticeText);
        processBoundaries(savedAreaCalculation, request.getBoundaries());

        return areaCalculationMapper.toResponseDto(savedAreaCalculation);
    }

    private AreaCalculationEntity createAndSaveBaseCalculation(AreaCalculationRequestDto request, double totalArea, String legalNotice) {
        ClientUserEntity client = clientUserRepository.findById(request.getClientDpi())
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado con DPI: " + request.getClientDpi()));

        UserSystemEntity userSystem = userSystemRepository.findById(request.getUserSystemId())
                .orElseThrow(() -> new RuntimeException("Usuario del sistema no encontrado con DPI: " + request.getUserSystemId()));

        // Convertir Base64 a byte[] si viene informado en el request
        byte[] decodedPlanImage = null;
        if (request.getPlanImageBase64() != null && !request.getPlanImageBase64().isBlank()) {
            try {
                // Limpiar cabecera Data URI si el frontend la envía (ej: "data:image/png;base64,iVBORw0KGgo...")
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

    private double[] calculateSideLengths(List<BoundaryRequestDto> boundaries) {
        double[] sideLengths = new double[boundaries.size()];
        for (int i = 0; i < boundaries.size(); i++) {
            BoundaryRequestDto boundaryDto = boundaries.get(i);
            double totalSideMeters = 0.0;
            if (boundaryDto.getMeasurements() != null) {
                for (MeasurementRequestDto mDto : boundaryDto.getMeasurements()) {
                    totalSideMeters += mDto.getValue() * UnitConversion.getFactor(mDto.getUnit());
                }
            }
            sideLengths[i] = totalSideMeters;
        }
        return sideLengths;
    }

    private void processBoundaries(AreaCalculationEntity savedAreaCalculation, List<BoundaryRequestDto> boundaries) {
        if (boundaries == null) return;

        for (BoundaryRequestDto boundaryDto : boundaries) {
            BoundariesEntity boundary = BoundariesEntity.builder()
                    .areaCalculation(savedAreaCalculation)
                    .sideNumber(boundaryDto.getSideNumber())
                    .referencePoint(boundaryDto.getReferencePoint())
                    .orientation(boundaryDto.getOrientation())
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

    private double calculatePolygonArea(double[] sides) {
        int n = sides.length;
        double perimeter = 0;
        for (double side : sides) {
            perimeter += side;
        }
        double semiPerimeter = perimeter / 2.0;

        if (n == 3) {
            double a = sides[0], b = sides[1], c = sides[2];
            if (a + b <= c || a + c <= b || b + c <= a) {
                throw new IllegalArgumentException("Las longitudes de los lados no forman un triángulo válido.");
            }
            double val = semiPerimeter * (semiPerimeter - a) * (semiPerimeter - b) * (semiPerimeter - c);
            return Math.round(Math.sqrt(val) * 100.0) / 100.0;
        }

        double maxSide = 0;
        for (double side : sides) {
            if (side > maxSide) maxSide = side;
        }

        double estimatedArea = (semiPerimeter - maxSide) * (semiPerimeter);
        return Math.round(Math.max(estimatedArea, 0.0) * 100.0) / 100.0;
    }

    @Transactional
    public List<AreaCalculationResponseDto> splitPolygon(PolygonSplitRequestDto request) {
        // 1. Recuperar el polígono principal
        AreaCalculationEntity parentPolygon = areaCalculationRepository.findById(request.getParentCalculationId())
                .orElseThrow(() -> new RuntimeException("Polígono principal no encontrado con ID: " + request.getParentCalculationId()));

        List<AreaCalculationResponseDto> subPolygonsResult = new java.util.ArrayList<>();

        // Validar si existen líneas de corte (permite lotes sin partición o con múltiples cortes / esquinas)
        if (request.getSplitLines() == null || request.getSplitLines().isEmpty()) {
            throw new IllegalArgumentException("Debe especificar al menos una línea de división o sub-lote.");
        }

        // 2. Procesar cada corte (Soporta 1, 2 o más particiones para esquinas y servidumbres)
        for (SplitLineDto cut : request.getSplitLines()) {

            // Si el corte trae colindancias explícitas, calculamos el área o usamos el área geométrica
            double cutArea = (cut.getBoundaries() != null && !cut.getBoundaries().isEmpty())
                    ? calculateSideAreaFromBoundaries(cut.getBoundaries())
                    : calculateGeometricCutArea(cut.getPoints());

            // Crear la entidad de AreaCalculation para el sub-lote (permite generar su PDF individual)
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

            // 3. Guardar las colindancias y medidas del sub-lote (para que la tabla del PDF NO salga vacía)
            if (cut.getBoundaries() != null && !cut.getBoundaries().isEmpty()) {
                processBoundaries(savedSubLot, cut.getBoundaries());
            }

            // 4. Registrar en la tabla histórica de sub-polígonos (sub_polygons)
            SubPolygonsEntity subPolygonRecord = SubPolygonsEntity.builder()
                    .areaCalculation(savedSubLot)
                    .subLotName(cut.getCutName())
                    .divisionType(cut.getDivisionType() != null ? cut.getDivisionType() : "DIVISION_ESTANDAR")
                    .calcualtedAreaMeters(cutArea)
                    .areaCalculatedVaras(cutArea * 1.4311) // Factor opcional de conversión a varas cuadradas si aplica en tu región
                    .createdAt(LocalDate.now())
                    .build();

            subPolygonsRepository.save(subPolygonRecord);

            subPolygonsResult.add(areaCalculationMapper.toResponseDto(savedSubLot));
        }

        return subPolygonsResult;
    }

    // Método auxiliar opcional para calcular el área si se proveen colindancias directas en el corte
    private double calculateSideAreaFromBoundaries(List<BoundaryRequestDto> boundaries) {
        double[] sideLengths = calculateSideLengths(boundaries);
        return calculatePolygonArea(sideLengths);
    }

    private double calculateGeometricCutArea(List<CoordinateDto> points) {
        if (points == null || points.size() < 3) return 0.0;

        // Algoritmo de Gauss / Shoelace para áreas poligonales cerradas a partir de coordenadas (X, Y)
        double area = 0.0;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            CoordinateDto p1 = points.get(i);
            CoordinateDto p2 = points.get((i + 1) % n);
            area += (p1.getX() * p2.getY()) - (p2.getX() * p1.getY());
        }
        return Math.round(Math.abs(area / 2.0) * 100.0) / 100.0;
    }
}