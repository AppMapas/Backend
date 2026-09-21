package com.seminario.legaladministrator.modules.calculations.dto;

import java.util.List;

public record PolygonAreaResult(
        double areaSquareMeters,
        double areaSquareVaras,
        double areaCuerdas,
        double perimeterMeters,
        double closureErrorMeters,
        List<CoordinateDto> vertices
) {
}
