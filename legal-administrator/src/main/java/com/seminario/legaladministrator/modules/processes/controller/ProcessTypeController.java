package com.seminario.legaladministrator.modules.processes.controller;

import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeResponse;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeSummaryResponse;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.VersionRequest;
import com.seminario.legaladministrator.modules.processes.service.ProcessTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;

import java.util.List;

@RestController
@RequestMapping("/api/v1/process-types")
@RequiredArgsConstructor
public class ProcessTypeController {
    private final ProcessTypeService processTypeService;

    @GetMapping
    public List<ProcessTypeSummaryResponse> list(@RequestParam(required = false) ProcessTypeStatus status) {
        return processTypeService.list(status);
    }

    @GetMapping("/{id}")
    public ProcessTypeResponse get(@PathVariable Long id) {
        return processTypeService.get(id);
    }

    @PostMapping
    public ResponseEntity<ProcessTypeResponse> create(@Valid @RequestBody ProcessTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(processTypeService.create(request));
    }

    @PutMapping("/{id}")
    public ProcessTypeResponse update(@PathVariable Long id, @Valid @RequestBody ProcessTypeRequest request) {
        return processTypeService.update(id, request);
    }

    @PatchMapping("/{id}/publish")
    public ProcessTypeResponse publish(@PathVariable Long id, @Valid @RequestBody VersionRequest request) {
        return processTypeService.publish(id, request.version());
    }

    @PatchMapping("/{id}/deactivate")
    public ProcessTypeResponse deactivate(@PathVariable Long id, @Valid @RequestBody VersionRequest request) {
        return processTypeService.deactivate(id, request.version());
    }
}
