package com.seminario.legaladministrator.modules.calculations.controller;

import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationRequestDto;
import com.seminario.legaladministrator.modules.calculations.dto.ConversionResponseDto;
import com.seminario.legaladministrator.modules.calculations.dto.MeasurementRequestDto;
import com.seminario.legaladministrator.modules.calculations.service.CalculationService;
import com.seminario.legaladministrator.modules.calculations.service.ConversionService;
import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/calculations")
@RequiredArgsConstructor
public class AreaCalculationController {
    private final ConversionService conversionService;
    private final CalculationService calculationService;

    @PostMapping("/convert")
    public ResponseEntity<List<ConversionResponseDto>> convertUnits(@Valid @RequestBody List<MeasurementRequestDto> requests) {
        List<ConversionResponseDto> responses = conversionService.convertMeasurements(requests);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/save")
    public ResponseEntity<AreaCalculationEntity> saveCalculation(
            @Valid @RequestBody AreaCalculationRequestDto request,
            @RequestParam String clientDpi,
            @RequestParam String userSystemId) {

        // Simulación o mapeo previo de entidades dependientes (Cliente y Usuario del Sistema)
        ClientUserEntity client = ClientUserEntity.builder().dpi(clientDpi).build();
        UserSystemEntity userSystem = UserSystemEntity.builder().dpi(userSystemId).build();

        AreaCalculationEntity savedCalculation = calculationService.saveCalculation(request, client, userSystem);
        return ResponseEntity.ok(savedCalculation);
    }
}