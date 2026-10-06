package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.modules.cash.CashDtos.*;
import com.seminario.legaladministrator.modules.payments.PaymentMethod;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/cash")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class CashController {
    private final CashService service;
    @GetMapping("/categories")
    public List<Category> categories() {
        return CashDtos.categories();
    }

    @GetMapping
    public Overview overview(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) String category,
            @RequestParam(required=false) PaymentMethod paymentMethod, @RequestParam(defaultValue="ACTIVE") String status,
            @RequestParam(defaultValue="") String q, @RequestParam(required=false) Long caseId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="12") int size) {
        return service.overview(from, to, category, paymentMethod, status, q, caseId, page, size);
    }
    @PostMapping("/expenses")
    public ResponseEntity<Mutation> expense(@Valid @RequestBody ExpenseRequest body) {
        return created(service.registerExpense(body));
    }

    @PostMapping("/incomes")
    public ResponseEntity<Mutation> income(@Valid @RequestBody IncomeRequest body) {
        return created(service.registerIncome(body));
    }

    @GetMapping("/incomes/{caseId}/summary")
    public ResponseEntity<IncomeSummary> incomeSummary(@PathVariable Long caseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.incomeSummary(caseId));
    }

    @GetMapping("/expenses/{id}")
    public Movement expense(@PathVariable Long id) {
        return service.getExpense(id);
    }

    @PostMapping("/expenses/{id}/annul")
    public Movement annulExpense(@PathVariable Long id, @Valid @RequestBody AnnulRequest body) {
        return service.annulExpense(id, body);
    }

    @PostMapping("/incomes/{caseId}/{paymentId}/annul")
    public Mutation annulIncome(@PathVariable Long caseId, @PathVariable UUID paymentId,
                                @Valid @RequestBody AnnulRequest body) {
        return service.annulIncome(caseId, paymentId, body);
    }

    private ResponseEntity<Mutation> created(Mutation result) {
        HttpStatus status = HttpStatus.CREATED;
        if (result.replayed()) status = HttpStatus.OK;
        return ResponseEntity.status(status).header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
