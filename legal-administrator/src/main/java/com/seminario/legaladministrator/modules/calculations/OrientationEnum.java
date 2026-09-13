package com.seminario.legaladministrator.modules.calculations;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OrientationEnum {
    N("N", "Norte"),
    S("S", "Sur"),
    E("E", "Este"),
    O("O", "Oeste"),
    W("W", "Oeste");

    private final String code;
    private final String description;

    OrientationEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    @JsonCreator
    public static OrientationEnum fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (OrientationEnum orientation : OrientationEnum.values()) {
            if (orientation.code.equalsIgnoreCase(value) || orientation.description.equalsIgnoreCase(value)) {
                return orientation;
            }
        }
        throw new IllegalArgumentException("Orientación no válida: " + value);
    }
}