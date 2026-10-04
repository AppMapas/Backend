package com.seminario.legaladministrator.modules.processes.controller;

import com.seminario.legaladministrator.modules.processes.dto.StageDtos.*;
import com.seminario.legaladministrator.modules.processes.service.StageConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/process-types/{typeId}/stages")
@RequiredArgsConstructor
public class StageConfigurationController {
    private final StageConfigurationService service;

    @GetMapping
    public Configuration get(@PathVariable Long typeId) {
        return service.get(typeId);
    }

    @PutMapping
    public Configuration configure(@PathVariable Long typeId, @Valid @RequestBody ConfigureRequest request) {
        return service.configure(typeId, request);
    }
}
