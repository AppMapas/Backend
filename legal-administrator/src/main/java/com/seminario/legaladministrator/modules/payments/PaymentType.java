package com.seminario.legaladministrator.modules.payments;

/**
 * Naturaleza del abono dentro del esquema de pagos pactado.
 * Los valores deben coincidir con la restricción {@code ck_case_payment_type}.
 */
public enum PaymentType {
    /** Anticipo al abrir el expediente, por ejemplo el 50% inicial. */
    ANTICIPO,
    /** Abono parcial posterior, sin cerrar la cuenta. */
    ABONO,
    /** Abono que liquida el saldo completo. */
    PAGO_FINAL
}