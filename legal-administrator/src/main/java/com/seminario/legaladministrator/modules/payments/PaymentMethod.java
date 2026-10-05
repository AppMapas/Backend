package com.seminario.legaladministrator.modules.payments;

/**
 * Vía por la que el cliente entregó el dinero.
 * Los valores deben coincidir con la restricción {@code ck_case_payment_method}.
 */
public enum PaymentMethod {
    EFECTIVO,
    TRANSFERENCIA,
    TARJETA,
    CHEQUE,
    OTRO
}