package com.seminario.legaladministrator.modules.processes.controller;

import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.RequirementRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.RequirementResponse;
import com.seminario.legaladministrator.modules.processes.service.RequirementService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
public class RequirementController {
    private final RequirementService requirementService;

    @GetMapping
    public List<RequirementResponse> list() {
        return requirementService.list();
    }

    @GetMapping("/{id}")
    public RequirementResponse get(@PathVariable Long id) {
        return requirementService.get(id);
    }

    @PostMapping
    public ResponseEntity<RequirementResponse> create(@Valid @RequestBody RequirementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requirementService.create(request));
    }

    @PutMapping("/{id}")
    public RequirementResponse update(@PathVariable Long id, @Valid @RequestBody RequirementRequest request) {
        return requirementService.update(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    public RequirementResponse deactivate(@PathVariable Long id) {
        return requirementService.deactivate(id);
    }
}
