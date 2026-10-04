package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
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

    public record Summary(UUID id, String name, String contentType, long sizeBytes, Instant uploadedAt) {
        static Summary of(CaseDocumentEntity entity) {
            return new Summary(entity.getId(), entity.getOriginalName(), entity.getContentType(),
                    entity.getSizeBytes(), entity.getUploadedAt());
        }
    }
    public record Content(Summary document, byte[] bytes) { }
    public record Policy(long maxFileSize, List<String> extensions) { }

    public Policy policy() { return new Policy(properties.getMaxFileSize().toBytes(), List.of("pdf", "jpg", "jpeg", "png")); }

    @Transactional(readOnly = true)
    public List<Summary> list(Long caseId) {
        requireCase(caseId);
        return documents.findByLegalProcessIdOrderByUploadedAtDesc(caseId).stream().map(Summary::of).toList();
    }

    @Transactional
    public Summary upload(Long caseId, MultipartFile file) {
        var operator = officeAccess.current();
        var legalCase = requireCase(caseId);
        if (!legalCase.isActive()) throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        DocumentValidator.Validated validated;
        try { validated = validator.validate(file); }
        catch (IOException error) { throw unavailable(error); }
        var entity = new CaseDocumentEntity();
        entity.setId(UUID.randomUUID());
        entity.setLegalProcess(legalCase);
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
        return cases.findById(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));
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
