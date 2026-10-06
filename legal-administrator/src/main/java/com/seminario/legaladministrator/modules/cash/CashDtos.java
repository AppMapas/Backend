package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.modules.payments.PaymentMethod;
import com.seminario.legaladministrator.modules.payments.PaymentType;
import com.seminario.legaladministrator.shared.PageResponse;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CashDtos {
    private CashDtos() { }
    public record ExpenseRequest(@NotNull UUID requestId, @NotNull ExpenseCategory category,
            @NotNull @DecimalMin("0.01") @Digits(integer=12, fraction=2) BigDecimal amount,
            @NotBlank @Size(max=500) String description, @NotNull LocalDate date,
            @NotNull PaymentMethod paymentMethod, @Size(max=60) String reference) { }
    public record IncomeRequest(@NotNull UUID requestId, @NotNull @Positive Long caseId,
            @NotNull @DecimalMin("0.01") @Digits(integer=12, fraction=2) BigDecimal amount,
            @NotBlank @Size(max=120) String description, @NotNull LocalDate date,
            @NotNull PaymentType paymentType, @NotNull PaymentMethod paymentMethod,
            @Size(max=60) String reference) { }
    public record AnnulRequest(@NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max=500) String reason) { }
    public record Category(String code, String name, String direction) { }
    public record Movement(String source, String sourceId, String direction, String category,
            String amount, String description, LocalDate date, PaymentMethod paymentMethod,
            String reference, boolean active, String registeredBy, Instant registeredAt,
            Long caseId, String caseCode, Long version, String annulReason, Instant annulledAt,
            String annulledBy) { }
    public record Summary(String income, String officeExpenses, String personalExpenses,
            String officeBalance, String generalBalance, String currency) { }
    public record Overview(LocalDate from, LocalDate to, Summary summary, PageResponse<Movement> movements) { }
    public record Mutation(String source, String sourceId, boolean replayed, Long caseVersion) { }
    public record IncomeSummary(Long caseId, String totalAmount, String paidAmount, String pendingAmount,
            boolean totalAgreed, boolean settled, boolean overpaid, boolean caseActive, Long caseVersion,
            String finalPaymentAmount, int finalPaymentCount, LocalDate lastFinalPaymentDate) { }
    public static List<Category> categories() {
        return List.of(new Category("TRAMITES", "Trámites", "INGRESO"),
                new Category("UTILES_OFICINA", "Útiles de oficina", "EGRESO"),
                new Category("GASTOS_PERSONALES", "Gastos personales", "EGRESO"));
    }
}
