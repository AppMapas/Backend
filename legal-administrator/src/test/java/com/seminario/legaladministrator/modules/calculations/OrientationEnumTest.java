package com.seminario.legaladministrator.modules.calculations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientationEnumTest {

    @Test
    void fromValue_acceptsSupportedCodeAndDescriptionValues() {
        assertEquals(OrientationEnum.N, OrientationEnum.fromValue("N"));
        assertEquals(OrientationEnum.E, OrientationEnum.fromValue("Este"));
        assertEquals(OrientationEnum.W, OrientationEnum.fromValue("W"));
        assertEquals(OrientationEnum.O, OrientationEnum.fromValue("Oeste"));
    }

    @Test
    void getters_returnExpectedCodeAndDescription() {
        assertEquals("S", OrientationEnum.S.getCode());
        assertEquals("Sur", OrientationEnum.S.getDescription());
        assertEquals("O", OrientationEnum.O.getCode());
        assertEquals("Oeste", OrientationEnum.O.getDescription());
    }

    @Test
    void fromValue_returnsNullForNullInput() {
        assertNull(OrientationEnum.fromValue(null));
    }

    @Test
    void fromValue_throwsForUnsupportedValues() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OrientationEnum.fromValue("INVALID")
        );

        assertTrue(exception.getMessage().contains("Orientación no válida"));
    }
}
