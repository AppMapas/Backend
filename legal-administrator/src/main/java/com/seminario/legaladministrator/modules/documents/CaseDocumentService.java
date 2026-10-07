package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRequirementRepository;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.shared.OperationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class CaseDocumentService {
    private final CaseDocumentRepository documents;
    private final LegalProcessRepository cases;
    private final OfficeAccess officeAccess;
    private final DocumentValidator validator;
    private final DocumentProperties properties;
    private final List<DocumentStorage> storages;
    private final LegalProcessRequirementRepository requirements;
    private final com.seminario.legaladministrator.modules.payments.CasePaymentRepository payments;

    public record Summary(UUID id, String name, String contentType, long sizeBytes, Instant uploadedAt) {
        static Summary of(CaseDocumentEntity entity) {
            return new Summary(entity.getId(), entity.getOriginalName(), entity.getContentType(),
                    entity.getSizeBytes(), entity.getUploadedAt());
        }
    }
    public record Content(Summary document, byte[] bytes) { }
    public record Policy(long maxFileSize, List<String> extensions) { }

    public Policy policy() { return new Policy(properties.getMaxFileSize().toBytes(), List.of("pdf")); }

    public record StatusRequest(@jakarta.validation.constraints.Pattern(regexp = "PENDING|COMPLETED")
            @jakarta.validation.constraints.NotNull String status) { }

    @Transactional
    public Long completeCase(Long caseId) {
        var legalCase = requireCase(caseId);
        if (!legalCase.isActive()) throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        var total = legalCase.getTotalAmount();
        if (total == null || total.signum() <= 0) {
            throw new OperationException(HttpStatus.CONFLICT, "Define un costo total mayor que cero antes de completar el expediente.");
        }
        var paid = payments.findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(caseId).stream()
                .map(com.seminario.legaladministrator.modules.payments.CasePaymentEntity::getAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (paid.compareTo(total) < 0) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente debe estar pagado al 100% antes de marcarlo como completado.");
        }
        var items = requirements.findByLegalProcessIdOrderByDisplayOrderAsc(caseId);
        for (var requirement : items) {
            if (requirement.isRequiredSnapshot() && !"COMPLETED".equals(requirement.getStatus())) {
                throw new OperationException(HttpStatus.CONFLICT,
                        "Completa todos los requisitos obligatorios antes de completar el expediente.");
            }
            if (requirement.isRequiredSnapshot() && requirement.isRequiresDocumentSnapshot()
                    && !hasPdf(caseId, requirement.getId())) {
                throw new OperationException(HttpStatus.CONFLICT,
                        "El requisito obligatorio " + requirement.getNameSnapshot() + " necesita un PDF guardado.");
            }
        }
        legalCase.setCurrentStatus("COMPLETED");
        return cases.saveAndFlush(legalCase).getVersion();
    }

    private boolean hasPdf(Long caseId, Long requirementId) {
        return documents.findByLegalProcessIdOrderByUploadedAtDesc(caseId).stream()
                .anyMatch(d -> d.getLegalProcessRequirement() != null
                        && requirementId.equals(d.getLegalProcessRequirement().getId())
                        && "application/pdf".equals(d.getContentType()));
    }

    @Transactional
    public void updateRequirementStatus(Long caseId, Long requirementId, StatusRequest request) {
        var legalCase = requireCase(caseId);
        if (!legalCase.isActive()) throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        var requirement = requireRequirement(caseId, requirementId);
        if ("COMPLETED".equals(request.status()) && requirement.isRequiresDocumentSnapshot()) {
            if (!hasPdf(caseId, requirementId)) throw new OperationException(HttpStatus.CONFLICT,
                    "Adjunta y guarda un PDF antes de marcar este requisito como completado.");
        }
        requirement.setStatus(request.status());
        var now = Instant.now();
        requirement.setCompletedAt("COMPLETED".equals(request.status()) ? now : null);
        requirement.setUpdatedAt(now);
        try {
            requirements.saveAndFlush(requirement);
        } catch (org.springframework.dao.DataIntegrityViolationException error) {
            log.error("No se pudo guardar el estado del requisito {} del expediente {}", requirementId, caseId, error);
            throw new OperationException(HttpStatus.CONFLICT,
                    "No fue posible guardar el estado del requisito. Actualiza el expediente e inténtalo nuevamente.");
        }
        if ("PENDING".equals(request.status()) && requirement.isRequiredSnapshot()
                && "COMPLETED".equals(legalCase.getCurrentStatus())) {
            legalCase.setCurrentStatus("OPEN");
            cases.saveAndFlush(legalCase);
        }
    }

    @Transactional(readOnly = true)
    public List<Summary> list(Long caseId) {
        requireCase(caseId);
        return documents.findByLegalProcessIdOrderByUploadedAtDesc(caseId).stream().map(Summary::of).toList();
    }

    @Transactional(readOnly = true)
    public List<Summary> listByRequirement(Long caseId, Long requirementId) {
        requireCase(caseId);
        requireRequirement(caseId, requirementId);
        return documents.findByLegalProcessIdOrderByUploadedAtDesc(caseId).stream()
                .filter(d -> d.getLegalProcessRequirement() != null
                        && requirementId.equals(d.getLegalProcessRequirement().getId()))
                .map(Summary::of).toList();
    }

    @Transactional
    public Summary uploadToRequirement(Long caseId, Long requirementId, MultipartFile file) {
        requireCase(caseId);
        var requirement = requireRequirement(caseId, requirementId);
        return store(caseId, file, requirement);
    }

    @Transactional
    public Summary upload(Long caseId, MultipartFile file) {
        return store(caseId, file, null);
    }

    @Transactional
    public Summary uploadReceipt(Long caseId, UUID requestId, MultipartFile file) {
        requireCase(caseId);
        var payment = payments.findByRequestId(requestId)
                .filter(p -> caseId.equals(p.getLegalProcess().getId()))
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Abono no encontrado en este expediente."));
        if (!receipts(caseId, payment.getId()).isEmpty()) {
            throw new OperationException(HttpStatus.CONFLICT, "El abono ya tiene un comprobante. Quita el anterior antes de adjuntar otro.");
        }
        return store(caseId, file, null, payment);
    }

    @Transactional
    public void removeReceipt(Long caseId, UUID paymentId, UUID documentId) {
        var legalCase = requireCase(caseId);
        if (!legalCase.isActive()) throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        var document = documents.findByIdAndLegalProcessId(documentId, caseId)
                .filter(d -> d.getPayment() != null && paymentId.equals(d.getPayment().getId()))
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Comprobante no encontrado en este abono."));
        documents.delete(document);
        documents.flush();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { storage(document.getStorageProvider()).delete(document.getObjectKey()); }
                catch (IOException | RuntimeException error) { log.error("No se pudo limpiar el comprobante {}", documentId, error); }
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Summary> receipts(Long caseId, UUID paymentId) {
        requireCase(caseId);
        return documents.findByLegalProcessIdOrderByUploadedAtDesc(caseId).stream()
                .filter(d -> d.getPayment() != null && paymentId.equals(d.getPayment().getId()))
                .map(Summary::of).toList();
    }

    private Summary store(Long caseId, MultipartFile file, LegalProcessRequirementEntity requirement) {
        return store(caseId, file, requirement, null);
    }

    private Summary store(Long caseId, MultipartFile file, LegalProcessRequirementEntity requirement,
            com.seminario.legaladministrator.modules.payments.CasePaymentEntity payment) {
        var operator = officeAccess.current();
        var legalCase = requireCase(caseId);
        if (!legalCase.isActive()) throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        DocumentValidator.Validated validated;
        try { validated = validator.validate(file); }
        catch (IOException error) { throw unavailable(error); }
        var entity = new CaseDocumentEntity();
        entity.setId(UUID.randomUUID());
        entity.setLegalProcess(legalCase);
        entity.setLegalProcessRequirement(requirement);
        entity.setPayment(payment);
        entity.setOriginalName(validated.name());
        entity.setContentType(validated.contentType());
        entity.setSizeBytes(validated.sizeBytes());
        entity.setStorageProvider(properties.getProvider());
        entity.setObjectKey(entity.getId().toString());
        entity.setUploadedBy(operator);
        entity.setUploadedAt(Instant.now());
        var storage = storage(entity.getStorageProvider());
        try (var input = file.getInputStream()) {
            storage.put(entity.getObjectKey(), input, validated.contentType());
        }
        catch (IOException | RuntimeException error) { throw unavailable(error); }
        // También cubre errores de commit, posteriores al retorno del método.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try { storage.delete(entity.getObjectKey()); }
                    catch (IOException | RuntimeException error) {
                        log.error("No se pudo limpiar documento huérfano {}", entity.getId(), error);
                    }
                }
            }
        });
        documents.saveAndFlush(entity);
        return Summary.of(entity);
    }

    @Transactional(readOnly = true)
    public Content content(Long caseId, UUID id) {
        requireCase(caseId);
        var entity = documents.findByIdAndLegalProcessId(id, caseId)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Documento no encontrado en este expediente."));
        try {
            return new Content(Summary.of(entity), storage(entity.getStorageProvider()).read(entity.getObjectKey()));
        } catch (DocumentMissingException error) {
            // Los metadatos existen pero el objeto no: 410 y no un 503 reintentable.
            log.error("El documento {} no está en el almacenamiento ({})", id, entity.getStorageProvider(), error);
            throw new OperationException(HttpStatus.GONE,
                    "El archivo ya no está disponible en el almacenamiento. Vuelve a adjuntarlo para poder consultarlo.");
        } catch (IOException | RuntimeException error) { throw unavailable(error); }
    }

    private LegalProcessEntity requireCase(Long id) {
        var legalCase = cases.findById(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));
        var operator = officeAccess.current();
        if (!"Administrador".equals(operator.getRole().getName())
                && !operator.getDpi().equals(legalCase.getAssignedUser().getDpi())) {
            throw new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado.");
        }
        return legalCase;
    }

    private LegalProcessRequirementEntity requireRequirement(Long caseId, Long requirementId) {
        var requirement = requirements.findById(requirementId).orElse(null);
        if (requirement == null || !caseId.equals(requirement.getLegalProcess().getId())) {
            throw new OperationException(HttpStatus.NOT_FOUND, "Requisito no encontrado en este expediente.");
        }
        return requirement;
    }

    private DocumentStorage storage(String provider) {
        return storages.stream().filter(storage -> storage.provider().equals(provider)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Proveedor de documentos desconocido."));
    }

    private OperationException unavailable(Exception error) {
        log.error("Error en almacenamiento de documentos", error);
        return new OperationException(HttpStatus.SERVICE_UNAVAILABLE,
                "No fue posible acceder al almacenamiento de documentos. Inténtalo nuevamente.");
    }
}
