package com.seminario.legaladministrator.modules.processes.controller;

import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.*;
import com.seminario.legaladministrator.modules.processes.service.LegalProcessService;
import com.seminario.legaladministrator.shared.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/legal-processes")
@RequiredArgsConstructor
public class LegalProcessController {
    private final LegalProcessService service;

    @PostMapping
    public ResponseEntity<Detail> create(@Valid @RequestBody CreateRequest request) {
        Creation result = service.create(request);
        HttpStatus status = HttpStatus.CREATED;
        if (result.replayed()) status = HttpStatus.OK;
        return ResponseEntity.status(status).header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.detail());
    }
    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id) {
        return service.get(id);
    }
    @PutMapping("/{id}")
    public Detail update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }
    @GetMapping
    public PageResponse<Summary> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String clientDpi,
            @RequestParam(required = false) Long processTypeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "true") Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(q, clientDpi, processTypeId, status, active, page, size);
    }
}
