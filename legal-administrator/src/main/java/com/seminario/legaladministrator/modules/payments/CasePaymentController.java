package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/legal-processes")
@RequiredArgsConstructor
public class CasePaymentController {
    private final CasePaymentService service;

    /**
     * Resumen y abonos del expediente. El saldo pendiente viaja en la misma
     * respuesta que la lista para que el frontend no calcule ni se desincronice.
     */
    @GetMapping("/{caseId}/payments")
    public Ledger ledger(@PathVariable Long caseId) {
        return service.ledger(caseId);
    }

    @PostMapping("/{caseId}/payments")
    public ResponseEntity<Ledger> register(@PathVariable Long caseId,
                                           @Valid @RequestBody CreatePaymentRequest request) {
        var result = service.register(caseId, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.ledger());
    }

    @PutMapping("/{caseId}/total-amount")
    public Ledger setTotalAmount(@PathVariable Long caseId,
                                 @Valid @RequestBody TotalAmountRequest request) {
        return service.setTotalAmount(caseId, request);
    }

    /** Anula el abono: lo conserva para auditoría, con {@code active = false}. */
    @DeleteMapping("/{caseId}/payments/{paymentId}")
    public Ledger annul(@PathVariable Long caseId, @PathVariable UUID paymentId) {
        return service.annul(caseId, paymentId);
    }
}