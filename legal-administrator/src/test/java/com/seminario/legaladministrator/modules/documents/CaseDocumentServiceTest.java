package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaseDocumentServiceTest {
    CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
    LegalProcessRepository cases = mock(LegalProcessRepository.class);
    OfficeAccess access = mock(OfficeAccess.class);
    DocumentStorage local = mock(DocumentStorage.class);
    DocumentStorage cloud = mock(DocumentStorage.class);
    DocumentProperties properties = new DocumentProperties();
    CaseDocumentService service = new CaseDocumentService(documents, cases, access,
            new DocumentValidator(properties), properties, List.of(local, cloud));
    MockMultipartFile file = new MockMultipartFile("file", "DPI.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes());

    @BeforeEach void prepare() {
        when(local.provider()).thenReturn("local");
        when(cloud.provider()).thenReturn("gcs");
        when(access.current()).thenReturn(new UserSystemEntity());
        when(cases.findById(1L)).thenReturn(Optional.of(new LegalProcessEntity()));
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test void storesMetadataAndCleansObjectOnDatabaseRollback() throws Exception {
        var summary = service.upload(1L, file);
        var captor = ArgumentCaptor.forClass(CaseDocumentEntity.class);
        verify(documents).saveAndFlush(captor.capture());
        var entity = captor.getValue();
        // La metadata queda asociada al expediente solicitado y al usuario que la subió.
        assertThat(entity.getLegalProcess()).isSameAs(cases.findById(1L).orElseThrow());
        assertThat(entity.getUploadedBy()).isSameAs(access.current());
        assertThat(entity.getOriginalName()).isEqualTo("DPI.pdf");
        assertThat(entity.getStorageProvider()).isEqualTo("local");
        assertThat(entity.getSizeBytes()).isEqualTo(file.getSize());
        assertThat(entity.getUploadedAt()).isNotNull();
        // En la base solo queda la dirección del archivo: clave interna, nunca el nombre del cliente.
        assertThat(entity.getObjectKey()).isEqualTo(entity.getId().toString());
        assertThat(entity.getObjectKey()).doesNotContain(file.getOriginalFilename());
        verify(local).put(eq(summary.id().toString()), any(), eq("application/pdf"));
        for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(local).delete(summary.id().toString());
    }

    @Test void storageFailureDoesNotPersistMetadata() throws Exception {
        doThrow(new IOException("disk unavailable")).when(local).put(anyString(), any(), anyString());
        assertThatThrownBy(() -> service.upload(1L, file)).isInstanceOfSatisfying(OperationException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(503));
        verifyNoInteractions(documents);
    }

    @Test void documentMustBelongToRequestedCase() throws Exception {
        UUID id = UUID.randomUUID();
        when(documents.findByIdAndLegalProcessId(id, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.content(1L, id)).isInstanceOfSatisfying(OperationException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(404));
        verify(local, never()).read(anyString());
    }

    @Test void inactiveCaseRejectsUploads() {
        var inactive = new LegalProcessEntity();
        inactive.setActive(false);
        when(cases.findById(1L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.upload(1L, file)).isInstanceOfSatisfying(OperationException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verifyNoInteractions(documents);
    }

    @Test void changingProviderDoesNotRedirectExistingDocuments() throws Exception {
        properties.setProvider("gcs");
        UUID id = UUID.randomUUID();
        var entity = new CaseDocumentEntity();
        entity.setId(id);
        entity.setStorageProvider("local");
        entity.setObjectKey(id.toString());
        when(documents.findByIdAndLegalProcessId(id, 1L)).thenReturn(Optional.of(entity));
        when(local.read(id.toString())).thenReturn(file.getBytes());
        assertThat(service.content(1L, id).bytes()).isEqualTo(file.getBytes());
        verify(cloud, never()).read(anyString());
    }

    @Test void metadataWithoutStoredFileIsDefinitiveAndNotRetryable() throws Exception {
        UUID id = UUID.randomUUID();
        var entity = new CaseDocumentEntity();
        entity.setId(id);
        entity.setStorageProvider("local");
        entity.setObjectKey(id.toString());
        when(documents.findByIdAndLegalProcessId(id, 1L)).thenReturn(Optional.of(entity));
        when(local.read(id.toString()))
                .thenThrow(new DocumentMissingException("no existe", new java.nio.file.NoSuchFileException(id.toString())));
        // 410 y no 503: el cliente no debe ofrecer reintentar un archivo que ya no está.
        assertThatThrownBy(() -> service.content(1L, id)).isInstanceOfSatisfying(OperationException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(410);
            assertThat(error.getMessage()).contains("ya no está disponible");
        });
    }

    @Test void otherStorageFailuresRemainRetryable() throws Exception {
        UUID id = UUID.randomUUID();
        var entity = new CaseDocumentEntity();
        entity.setId(id);
        entity.setStorageProvider("local");
        entity.setObjectKey(id.toString());
        when(documents.findByIdAndLegalProcessId(id, 1L)).thenReturn(Optional.of(entity));
        when(local.read(id.toString())).thenThrow(new IOException("disco no disponible"));
        assertThatThrownBy(() -> service.content(1L, id)).isInstanceOfSatisfying(OperationException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(503));
    }
}
