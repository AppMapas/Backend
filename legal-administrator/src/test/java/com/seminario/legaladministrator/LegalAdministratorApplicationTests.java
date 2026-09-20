package com.seminario.legaladministrator;

import com.seminario.legaladministrator.modules.calculations.UnitConversion;
import com.seminario.legaladministrator.modules.calculations.dto.MeasurementRequestDto;
import com.seminario.legaladministrator.modules.calculations.service.ConversionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegalAdministratorApplicationTests {

    @Test
    void unitConversion_shouldUseExpectedFactors() {
        assertEquals(0.836, UnitConversion.getFactor("varas"), 0.0001);
        assertEquals(0.0254, UnitConversion.getFactor("pulgadas"), 0.0001);
        assertEquals(1.0, UnitConversion.getFactor("metros"), 0.0001);
        assertEquals(1.0, UnitConversion.getFactor("unidad-desconocida"), 0.0001);
    }

    @Test
    void conversionService_shouldConvertMeasurementsToMeters() {
        ConversionService conversionService = new ConversionService();

        List<MeasurementRequestDto> measurements = List.of(
                new MeasurementRequestDto(10.0, "varas"),
                new MeasurementRequestDto(200.0, "centímetros")
        );

        var result = conversionService.convertMeasurements(measurements);

        assertEquals(2, result.size());
        assertEquals(10.0, result.get(0).getOriginalValue());
        assertEquals("varas", result.get(0).getUnit());
        assertEquals(8.36, result.get(0).getConvertedValueMeters(), 0.0001);

        assertEquals(200.0, result.get(1).getOriginalValue());
        assertEquals("centímetros", result.get(1).getUnit());
        assertEquals(2.0, result.get(1).getConvertedValueMeters(), 0.0001);
    }
}
