package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.cash.CashDtos.*;
import com.seminario.legaladministrator.modules.payments.*;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.shared.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Caja deriva los cobros del libro del expediente y conserva egresos auditables. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class CashService {
    private final ExpenseRecordRepository expenses;
    private final CasePaymentService payments;
    private final CasePaymentRepository paymentRepository;
    private final OfficeAccess access;
    private final CaseRequestGuard requests;
    private final NamedParameterJdbcTemplate jdbc;
    private final Validator validator;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=15)
    public Overview overview(LocalDate from, LocalDate to, String category, PaymentMethod method,
                             String status, String q, Long caseId, int page, int size) {
        var actor = access.current();
        LocalDate today = LocalDate.now(ZoneId.of("America/Guatemala"));
        if (from == null) from = today.withDayOfMonth(1);
        if (to == null) to = today;
        if (from.isAfter(to)) bad("La fecha inicial debe ser anterior o igual a la final.");
        InputRules.page(page, size, org.springframework.data.domain.Sort.unsorted());
        var params = new MapSqlParameterSource().addValue("actor", actor.getDpi())
                .addValue("from", from).addValue("to", to);
        StringBuilder where = new StringBuilder(" WHERE movement_date BETWEEN :from AND :to AND (owner_dpi IS NULL OR owner_dpi = :actor)");
        if (category != null && !category.isBlank()) {
            if (CashDtos.categories().stream().noneMatch(item -> item.code().equals(category))) bad("La categoría no es válida.");
            where.append(" AND category = :category");
            params.addValue("category", category);
        }
        if (method != null) { where.append(" AND payment_method = :method"); params.addValue("method", method.name()); }
        if (caseId != null) {
            if (caseId <= 0) bad("El expediente no es válido.");
            where.append(" AND legal_process_id = :caseId"); params.addValue("caseId", caseId);
        }
        if (status == null || status.equals("ACTIVE")) where.append(" AND active");
        else if (status.equals("ANNULLED")) where.append(" AND NOT active");
        else if (!status.equals("ALL")) bad("El estado no es válido.");
        String term = InputRules.search(q);
        if (!term.isBlank()) {
            where.append(" AND (description ILIKE :term ESCAPE '!' OR coalesce(case_code,'') ILIKE :term ESCAPE '!' OR coalesce(reference,'') ILIKE :term ESCAPE '!')");
            params.addValue("term", "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        String filter = where.toString();
        var totals = jdbc.queryForMap("SELECT count(*) AS count, "
                + "coalesce(sum(amount) FILTER (WHERE active AND direction='INGRESO'),0) AS income, "
                + "coalesce(sum(amount) FILTER (WHERE active AND category='UTILES_OFICINA'),0) AS office, "
                + "coalesce(sum(amount) FILTER (WHERE active AND category='GASTOS_PERSONALES'),0) AS personal "
                + "FROM cash_movement" + filter, params);
        BigDecimal income = (BigDecimal) totals.get("income");
        BigDecimal office = (BigDecimal) totals.get("office");
        BigDecimal personal = (BigDecimal) totals.get("personal");
        long count = ((Number) totals.get("count")).longValue();
        params.addValue("limit", size).addValue("offset", (long)page * size);
        List<Movement> items = jdbc.query("SELECT * FROM cash_movement" + filter
                + " ORDER BY movement_date DESC, created_at DESC, source, source_id LIMIT :limit OFFSET :offset", params, this::map);
        var summary = new Summary(money(income), money(office), money(personal),
                money(income.subtract(office)), money(income.subtract(office).subtract(personal)), "GTQ");
        return new Overview(from, to, summary, new PageResponse<>(items, page, size, count,
                (int)Math.min(Integer.MAX_VALUE, (count + size - 1) / size)));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=15)
    public IncomeSummary incomeSummary(Long caseId) {
        var ledger = payments.ledger(caseId);
        String totalAmount = null;
        String pendingAmount = null;
        if (ledger.totalAgreed()) {
            totalAmount = money(ledger.totalAmount());
            pendingAmount = money(ledger.pendingAmount());
        }

        BigDecimal finalAmount = BigDecimal.ZERO;
        int finalCount = 0;
        LocalDate lastFinalDate = null;
        for (var payment : ledger.payments()) {
            if (payment.active() && payment.paymentType() == PaymentType.PAGO_FINAL) {
                finalAmount = finalAmount.add(payment.amount());
                finalCount += 1;
                if (lastFinalDate == null || payment.paymentDate().isAfter(lastFinalDate)) {
                    lastFinalDate = payment.paymentDate();
                }
            }
        }

        return new IncomeSummary(caseId, totalAmount, money(ledger.paidAmount()), pendingAmount,
                ledger.totalAgreed(), ledger.settled(), ledger.overpaid(), ledger.caseActive(), ledger.caseVersion(),
                money(finalAmount), finalCount, lastFinalDate);
    }

    @Transactional(timeout=15)
    public Mutation registerExpense(ExpenseRequest request) {
        validate(request);
        requireDate(request.date());
        var actor = access.current();
        String description = InputRules.required(request.description(), 500, "La descripción");
        String reference = InputRules.text(request.reference());
        String hash = requests.fingerprint(Arrays.asList("EXPENSE", request.category(), money(request.amount()),
                description, request.date(), request.paymentMethod(), reference));
        requests.lock(request.requestId());
        var previous = expenses.findByRequestId(request.requestId());
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!actor.getDpi().equals(saved.getRegisteredBy()) || !hash.equals(saved.getRequestHash())) conflict("La solicitud ya se utilizó con otros datos.");
            return new Mutation("EXPENSE", saved.getId().toString(), true, null);
        }
        if (paymentRepository.findByRequestId(request.requestId()).isPresent()) conflict("La solicitud ya identifica un ingreso.");
        Long categoryId = jdbc.queryForObject("SELECT id FROM payment_category WHERE code=:code AND active",
                Map.of("code", request.category().name()), Long.class);
        var expense = new ExpenseRecordEntity();
        expense.setCategoryId(categoryId);
        expense.setScope("OFICINA");
        if (request.category() == ExpenseCategory.GASTOS_PERSONALES) {
            expense.setScope("PERSONAL"); expense.setOwnerDpi(actor.getDpi());
        }
        expense.setAmount(request.amount().setScale(2, RoundingMode.UNNECESSARY));
        expense.setDescription(description);
        expense.setDate(request.date());
        expense.setPaymentMethod(request.paymentMethod());
        expense.setReference(reference);
        expense.setRegisteredBy(actor.getDpi());
        expense.setRequestId(request.requestId());
        expense.setRequestHash(hash);
        expenses.saveAndFlush(expense);
        return new Mutation("EXPENSE", expense.getId().toString(), false, null);
    }

    @Transactional(timeout=15)
    public Mutation registerIncome(IncomeRequest request) {
        validate(request);
        requireDate(request.date());
        requests.lock(request.requestId());
        if (expenses.findByRequestId(request.requestId()).isPresent()) conflict("La solicitud ya identifica un gasto.");
        var payment = new PaymentDtos.CreatePaymentRequest(request.requestId(), request.amount(), request.paymentType(),
                request.paymentMethod(), request.description(), request.date(), request.reference());
        var result = payments.register(request.caseId(), payment);
        var saved = paymentRepository.findByRequestId(request.requestId()).orElseThrow();
        return new Mutation("CASE_PAYMENT", saved.getId().toString(), result.replayed(), result.ledger().caseVersion());
    }

    @Transactional(readOnly=true, timeout=15)
    public Movement getExpense(Long id) {
        if (id == null || id <= 0) bad("El gasto no es válido.");
        String actor = access.current().getDpi();
        var items = jdbc.query("SELECT * FROM cash_movement WHERE source='EXPENSE' AND source_id=:id AND (owner_dpi IS NULL OR owner_dpi=:actor)",
                Map.of("id", id.toString(), "actor", actor), this::map);
        if (items.isEmpty()) notFound();
        return items.get(0);
    }

    @Transactional(timeout=15)
    public Movement annulExpense(Long id, AnnulRequest request) {
        if (id == null || id <= 0) bad("El gasto no es válido.");
        validate(request);
        String actor = access.current().getDpi();
        var expense = expenses.findForUpdate(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Movimiento no encontrado."));
        if (expense.getOwnerDpi() != null && !actor.equals(expense.getOwnerDpi())) notFound();
        String reason = InputRules.required(request.reason(), 500, "El motivo");
        if (!expense.isActive()) {
            if (actor.equals(expense.getAnnulledBy()) && reason.equals(expense.getAnnulReason())) return getExpense(id);
            conflict("El gasto ya está anulado.");
        }
        if (!Objects.equals(expense.getVersion(), request.version())) conflict("El gasto cambió. Recarga su información.");
        expense.setActive(false);
        expense.setAnnulledBy(actor);
        expense.setAnnulledAt(Instant.now());
        expense.setAnnulReason(reason);
        expenses.saveAndFlush(expense);
        return getExpense(id);
    }

    @Transactional(timeout=15)
    public Mutation annulIncome(Long caseId, UUID paymentId, AnnulRequest request) {
        validate(request);
        var ledger = payments.annulFromCash(caseId, paymentId, request.version(), request.reason());
        return new Mutation("CASE_PAYMENT", paymentId.toString(), false, ledger.caseVersion());
    }

    private Movement map(ResultSet row, int index) throws SQLException {
        Long caseId = row.getObject("legal_process_id", Long.class);
        var annulledAt = row.getTimestamp("annulled_at");
        Instant annulInstant = null;
        if (annulledAt != null) annulInstant = annulledAt.toInstant();
        return new Movement(row.getString("source"), row.getString("source_id"), row.getString("direction"),
                row.getString("category"), money(row.getBigDecimal("amount")), row.getString("description"),
                row.getDate("movement_date").toLocalDate(), PaymentMethod.valueOf(row.getString("payment_method")),
                row.getString("reference"), row.getBoolean("active"), row.getString("registered_by"),
                row.getTimestamp("created_at").toInstant(), caseId, row.getString("case_code"), row.getLong("version"),
                row.getString("annul_reason"), annulInstant, row.getString("annulled_by"));
    }
    private String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private void validate(Object value) {
        if (value == null || !validator.validate(value).isEmpty()) {
            bad("Completa los campos obligatorios con datos válidos.");
        }
    }

    private void requireDate(LocalDate date) {
        if (date.isAfter(LocalDate.now(ZoneId.of("America/Guatemala")))) {
            bad("No se permiten movimientos con fecha futura.");
        }
    }

    private void bad(String message) {
        throw new OperationException(HttpStatus.BAD_REQUEST, message);
    }

    private void conflict(String message) {
        throw new OperationException(HttpStatus.CONFLICT, message);
    }

    private void notFound() {
        throw new OperationException(HttpStatus.NOT_FOUND, "Movimiento no encontrado.");
    }
}
