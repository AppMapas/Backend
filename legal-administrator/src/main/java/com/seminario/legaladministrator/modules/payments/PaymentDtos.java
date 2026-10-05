package com.seminario.legaladministrator.modules.payments;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato HTTP de los abonos. Los montos viajan como decimales en JSON
 * ({@code 1500.50}) y se devuelven siempre con escala 2 para que el resumen
 * financiero sea estable frente a comparaciones en el frontend.
 */
public final class PaymentDtos {
    private PaymentDtos() { }

    /**
     * @param paymentDate no puede ser futura: solo se registra lo ya recibido.
     * @param reference opcional; si viene, no puede quedar en blanco.
     */
    public record CreatePaymentRequest(
            @NotNull UUID requestId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull PaymentType paymentType,
            @NotNull PaymentMethod paymentMethod,
            @NotBlank @Size(max = 120) String concept,
            @NotNull @PastOrPresent LocalDate paymentDate,
            @Size(max = 60) String reference) { }

    /**
     * @param totalAmount admite {@code null} para volver al estado "no pactado".
     * @param version bloqueo optimista del expediente; cambia con cada edición.
     */
    public record TotalAmountRequest(
            @NotNull @PositiveOrZero Long version,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal totalAmount) { }

    public record PaymentResponse(UUID id, BigDecimal amount, PaymentType paymentType,
                                  PaymentMethod paymentMethod, String concept,
                                  LocalDate paymentDate, String reference, boolean active,
                                  String registeredBy, Instant registeredAt) {
        static PaymentResponse of(CasePaymentEntity entity) {
            return new PaymentResponse(entity.getId(), entity.getAmount(), entity.getPaymentType(),
                    entity.getPaymentMethod(), entity.getConcept(), entity.getPaymentDate(),
                    entity.getReference(), entity.isActive(),
                    entity.getRegisteredBy().getDpi(), entity.getCreatedAt());
        }
    }

    /**
     * Resumen del expediente y sus abonos en una sola respuesta, para que el
     * frontend no tenga que pedir dos recursos y arriesgar cifras que no cuadran.
     *
     * @param totalAmount {@code null} mientras el costo no esté pactado; se
     *                    distingue de cero porque un cero inventaría un saldo.
     * @param advancesAmount solo abonos de tipo ANTICIPO (por ejemplo el 50% inicial).
     * @param paidAmount suma de los abonos vigentes, incluidos los anticipos.
     * @param pendingAmount {@code totalAmount - paidAmount}; negativo significa
     *                      saldo a favor del cliente, que es un estado real.
     * @param overpaid {@code true} cuando lo abonado supera el costo pactado.
     */
    public record Ledger(BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal advancesAmount,
                         BigDecimal pendingAmount, boolean totalAgreed, boolean settled,
                         boolean overpaid, Long caseVersion, boolean caseActive,
                         List<PaymentResponse> payments) { }

    /** @param replayed indica que la clave de idempotencia ya tenía ese registro. */
    public record Creation(Ledger ledger, boolean replayed) { }
}