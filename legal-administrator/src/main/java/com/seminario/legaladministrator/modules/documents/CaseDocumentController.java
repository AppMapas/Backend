package com.seminario.legaladministrator.modules.documents;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/legal-processes")
@RequiredArgsConstructor
public class CaseDocumentController {
    private final CaseDocumentService service;

    @GetMapping("/documents/policy")
    public CaseDocumentService.Policy policy() { return service.policy(); }

    @GetMapping("/{caseId}/documents")
    public List<CaseDocumentService.Summary> list(@PathVariable Long caseId) { return service.list(caseId); }

    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaseDocumentService.Summary> upload(@PathVariable Long caseId,
                                                            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(caseId, file));
    }

    @PatchMapping("/{caseId}/requirements/{requirementId}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable Long caseId, @PathVariable Long requirementId,
            @jakarta.validation.Valid @RequestBody CaseDocumentService.StatusRequest request) {
        service.updateRequirementStatus(caseId, requirementId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{caseId}/requirements/{requirementId}/documents")
    public List<CaseDocumentService.Summary> listByRequirement(@PathVariable Long caseId, @PathVariable Long requirementId) {
        return service.listByRequirement(caseId, requirementId);
    }

    @PostMapping(value = "/{caseId}/requirements/{requirementId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaseDocumentService.Summary> uploadToRequirement(@PathVariable Long caseId,
            @PathVariable Long requirementId, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadToRequirement(caseId, requirementId, file));
    }

    @GetMapping("/{caseId}/documents/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long caseId, @PathVariable UUID id,
                                         @RequestParam(defaultValue = "false") boolean download) {
        var result = service.content(caseId, id);
        var disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(result.document().name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .contentType(MediaType.parseMediaType(result.document().contentType()))
                .contentLength(result.bytes().length).body(result.bytes());
    }
}
