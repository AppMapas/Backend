package com.seminario.legaladministrator.modules.calculations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitConversionTest {

    @Test
    void getFactor_returnsKnownFactorForExactUnitName() {
        assertEquals(0.836, UnitConversion.getFactor("varas"), 0.0001);
        assertEquals(0.0254, UnitConversion.getFactor("pulgadas"), 0.0001);
        assertEquals(1.0, UnitConversion.getFactor("metros"), 0.0001);
    }

    @Test
    void getFactor_isCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(0.9144, UnitConversion.getFactor("  YARDAS  "), 0.0001);
        assertEquals(0.01, UnitConversion.getFactor("  CENTÍMETROS  "), 0.0001);
    }

    @Test
    void getFactor_returnsDefaultForNullOrUnknownValues() {
        assertEquals(1.0, UnitConversion.getFactor(null), 0.0001);
        assertEquals(1.0, UnitConversion.getFactor("unidades-desconocidas"), 0.0001);
    }
}
