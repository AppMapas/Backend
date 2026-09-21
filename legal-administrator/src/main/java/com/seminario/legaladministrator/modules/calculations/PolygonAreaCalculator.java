package com.seminario.legaladministrator.modules.calculations;

import com.seminario.legaladministrator.modules.calculations.UnitConversion;
import com.seminario.legaladministrator.modules.calculations.dto.BoundaryRequestDto;
import com.seminario.legaladministrator.modules.calculations.dto.CoordinateDto;
import com.seminario.legaladministrator.modules.calculations.dto.MeasurementRequestDto;
import com.seminario.legaladministrator.modules.calculations.dto.PolygonAreaResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Cálculo geométrico de polígonos (terrenos), a partir de:
 *  a) coordenadas de vértices ya conocidas (p. ej. dibujadas en un canvas), o
 *  b) colindancias con distancia + orientación cartesiana en grados
 *     (0° = eje +X, sentido antihorario — igual que "orientaciones cartesianas").
 *
 * Ambas rutas terminan en la fórmula de Gauss/Shoelace sobre coordenadas (X, Y),
 * la única forma matemáticamente válida de obtener el área de un polígono de 3+
 * lados: las longitudes por sí solas no determinan el área (un polígono de 4+
 * lados es "flexible" — mismas longitudes pueden encerrar áreas muy distintas).
 *
 * No asume esquinas a 90°: soporta polígonos irregulares con cualquier ángulo,
 * que es lo que requiere el DTO real (orientaciones libres como 98°, 70°, 105°...).
 */
@Component
public class PolygonAreaCalculator {

    // Tolerancia de cierre de poligonal en metros, usada solo cuando se exige cierre exacto.
    private static final double CLOSURE_TOLERANCE_METERS = 0.05;

    // Constantes del sistema de medidas guatemalteco para agrimensura.
    private static final double VARA_M = 0.835905;      // 1 vara guatemalteca en metros
    private static final double CUERDA_AREA_M2 = 436.0; // 1 cuerda (área) en metros cuadrados

    /**
     * Área de un polígono a partir de coordenadas de vértices ya conocidas
     * (p. ej. las que llegan del canvas del frontend). Fórmula de Gauss/Shoelace.
     */
    public double calculateGeometricCutArea(List<CoordinateDto> points) {
        if (points == null || points.size() < 3) return 0.0;

        double area = 0.0;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            CoordinateDto p1 = points.get(i);
            CoordinateDto p2 = points.get((i + 1) % n);
            area += (p1.getX() * p2.getY()) - (p2.getX() * p1.getY());
        }
        return Math.round(Math.abs(area / 2.0) * 100.0) / 100.0;
    }

    /**
     * Área a partir de colindancias (distancia + orientación), exigiendo que la
     * poligonal cierre exactamente. Usa esta ruta cuando no hay vértices explícitos.
     */
    public double calculatePolygonAreaFromBoundaries(List<BoundaryRequestDto> boundaries) {
        return calculatePolygonAreaFromBoundaries(boundaries, true);
    }

    /**
     * Igual que la anterior, pero permite omitir la validación de cierre
     * (útil para linderos irregulares tipo río/quebrada, ver TerrainMetaDto.closed).
     */
    public double calculatePolygonAreaFromBoundaries(List<BoundaryRequestDto> boundaries, boolean enforceClosure) {
        double[] sideLengths = calculateSideLengths(boundaries);
        List<CoordinateDto> vertices = buildVerticesFromBoundaries(boundaries, sideLengths);
        closeOrTrim(vertices, enforceClosure);
        return calculateGeometricCutArea(vertices);
    }

    /**
     * Versión extendida a partir de colindancias: además del área en m², devuelve
     * área en varas²/cuerdas, perímetro y el error de cierre medido.
     */
    public PolygonAreaResult calculatePolygonMetricsFromBoundaries(List<BoundaryRequestDto> boundaries, boolean enforceClosure) {
        double[] sideLengths = calculateSideLengths(boundaries);
        List<CoordinateDto> vertices = buildVerticesFromBoundaries(boundaries, sideLengths);
        double closureErrorMeters = measureClosureError(vertices);
        closeOrTrim(vertices, enforceClosure);

        double perimeterMeters = 0.0;
        for (double side : sideLengths) {
            perimeterMeters += side;
        }

        return buildResult(vertices, perimeterMeters, closureErrorMeters);
    }

    /**
     * Versión extendida a partir de vértices ya conocidos (canvas). No hay error de
     * cierre que medir: un polígono definido por sus propios vértices siempre "cierra"
     * por construcción (el Shoelace conecta el último punto con el primero).
     */
    public PolygonAreaResult calculatePolygonMetricsFromVertices(List<CoordinateDto> vertices) {
        double perimeterMeters = 0.0;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            CoordinateDto p1 = vertices.get(i);
            CoordinateDto p2 = vertices.get((i + 1) % n);
            perimeterMeters += Math.hypot(p2.getX() - p1.getX(), p2.getY() - p1.getY());
        }
        return buildResult(vertices, perimeterMeters, 0.0);
    }

    private PolygonAreaResult buildResult(List<CoordinateDto> vertices, double perimeterMeters, double closureErrorMeters) {
        double areaSquareMeters = calculateGeometricCutArea(vertices);
        double areaSquareVaras = areaSquareMeters / (VARA_M * VARA_M);
        double areaCuerdas = areaSquareMeters / CUERDA_AREA_M2;
        return new PolygonAreaResult(areaSquareMeters, areaSquareVaras, areaCuerdas, perimeterMeters, closureErrorMeters, vertices);
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

    /**
     * Reconstruye las coordenadas (X, Y) de cada vértice a partir de la distancia y
     * la orientación cartesiana en grados de cada colindancia (0° = eje +X, antihorario).
     */
    private List<CoordinateDto> buildVerticesFromBoundaries(List<BoundaryRequestDto> boundaries, double[] sideLengths) {
        List<CoordinateDto> vertices = new ArrayList<>();
        double x = 0.0;
        double y = 0.0;
        vertices.add(new CoordinateDto(x, y));

        for (int i = 0; i < boundaries.size(); i++) {
            Double orientationDegrees = boundaries.get(i).getOrientation();
            if (orientationDegrees == null) {
                throw new IllegalArgumentException(
                        "Falta la orientación de la colindancia #" + (i + 1) + ". Se requiere el ángulo cartesiano de cada lado.");
            }

            double angleRad = Math.toRadians(orientationDegrees);
            double distance = sideLengths[i];

            x += distance * Math.cos(angleRad);
            y += distance * Math.sin(angleRad);
            vertices.add(new CoordinateDto(x, y));
        }

        return vertices;
    }

    private double measureClosureError(List<CoordinateDto> vertices) {
        CoordinateDto first = vertices.get(0);
        CoordinateDto last = vertices.get(vertices.size() - 1);
        double errorX = last.getX() - first.getX();
        double errorY = last.getY() - first.getY();
        return Math.sqrt(errorX * errorX + errorY * errorY);
    }

    /**
     * Si enforceClosure es true, valida la tolerancia de cierre y lanza excepción
     * si se excede. En ambos casos remueve el vértice de cierre duplicado antes de
     * aplicar Shoelace (si no, el lado de cierre se contaría dos veces).
     */
    private void closeOrTrim(List<CoordinateDto> vertices, boolean enforceClosure) {
        double closureError = measureClosureError(vertices);

        if (enforceClosure && closureError > CLOSURE_TOLERANCE_METERS) {
            throw new IllegalArgumentException(String.format(
                    "La poligonal no cierra: error de cierre de %.2f m (tolerancia: %.2f m). " +
                            "Verifique las distancias y orientaciones ingresadas para cada colindancia.",
                    closureError, CLOSURE_TOLERANCE_METERS));
        }

        vertices.remove(vertices.size() - 1);
    }
}