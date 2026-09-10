package com.seminario.legaladministrator.modules.calculations;

import lombok.Getter;

@Getter
public enum UnitConversion {
    VARAS("varas", 0.836),
    PULGADAS("pulgadas", 0.0254),
    METROS("metros", 1.0),
    CENTIMETROS("centímetros", 0.01),
    YARDAS("yardas", 0.9144);

    private final String unitName;
    private final double factorToMeters;

    UnitConversion(String unitName, double factorToMeters) {
        this.unitName = unitName;
        this.factorToMeters = factorToMeters;
    }

    public static double getFactor(String unit) {
        if (unit == null) return 1.0;
        for (UnitConversion uc : values()) {
            if (uc.unitName.equalsIgnoreCase(unit.trim())) {
                return uc.factorToMeters;
            }
        }
        return 1.0;
    }
}