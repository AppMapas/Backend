package com.seminario.legaladministrator.modules.calculations.controller;

import com.seminario.legaladministrator.modules.calculations.dto.*;
import com.seminario.legaladministrator.modules.calculations.service.CalculationService;
import com.seminario.legaladministrator.modules.calculations.service.ConversionService;
import com.seminario.legaladministrator.modules.calculations.service.PdfReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/calculations")
@RequiredArgsConstructor
public class AreaCalculationController {
    private final ConversionService conversionService;
    private final CalculationService calculationService;
    private final PdfReportService pdfReportService;

    @PostMapping("/convert")
    public ResponseEntity<List<ConversionResponseDto>> convertUnits(@Valid @RequestBody List<MeasurementRequestDto> requests) {
        List<ConversionResponseDto> responses = conversionService.convertMeasurements(requests);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/save")
    public ResponseEntity<AreaCalculationResponseDto> saveCalculation(
            @Valid @RequestBody AreaCalculationRequestDto request) {

        AreaCalculationResponseDto savedCalculation = calculationService.saveCalculation(request);
        return ResponseEntity.ok(savedCalculation);
    }

    @PostMapping("/polygon")
    public ResponseEntity<AreaCalculationResponseDto> calculateAndSavePolygon(
            @Valid @RequestBody AreaCalculationRequestDto request) {
        AreaCalculationResponseDto response = calculationService.calculateAndSavePolygon(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/split")
    public ResponseEntity<List<AreaCalculationResponseDto>> splitPolygon(
            @Valid @RequestBody PolygonSplitRequestDto request) {
        List<AreaCalculationResponseDto> subLots = calculationService.splitPolygon(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(subLots);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdfReport(@PathVariable Long id) {
        byte[] pdfBytes = pdfReportService.generatePreliminaryReportPdf(id);

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reporte-preliminar-" + id + ".pdf")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}