package com.seminario.legaladministrator.modules.calculations;

/**
 * Dirección cardinal de una colindancia, para terrenos rectilíneos con esquinas
 * fijas a 90° (Norte/Sur/Este/Oeste). Reemplaza el rumbo/ángulo libre en grados:
 * en vez de capturar un ángulo arbitrario, el usuario solo elige una de estas 4
 * direcciones y el sistema asume que el giro entre lados consecutivos es de 90°.
 *
 * dx/dy representan el desplazamiento unitario en el plano cartesiano
 * (Este = +X, Norte = +Y), listo para multiplicar por la distancia del lado.
 */
public enum CardinalDirection {
    NORTE(0, 1),
    SUR(0, -1),
    ESTE(1, 0),
    OESTE(-1, 0);

    private final int dx;
    private final int dy;

    CardinalDirection(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public int getDx() {
        return dx;
    }

    public int getDy() {
        return dy;
    }
}
