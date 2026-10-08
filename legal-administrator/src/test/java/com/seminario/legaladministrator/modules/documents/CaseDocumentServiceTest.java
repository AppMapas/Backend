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
    com.seminario.legaladministrator.modules.payments.CasePaymentRepository payments =
            mock(com.seminario.legaladministrator.modules.payments.CasePaymentRepository.class);
    com.seminario.legaladministrator.modules.processes.repository.LegalProcessRequirementRepository requirements =
            mock(com.seminario.legaladministrator.modules.processes.repository.LegalProcessRequirementRepository.class);
    CaseDocumentService service = new CaseDocumentService(documents, cases, access,
            new DocumentValidator(properties), properties, List.of(local, cloud),
            requirements, payments);
    MockMultipartFile file = new MockMultipartFile("file", "DPI.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes());

    @BeforeEach void prepare() {
        when(local.provider()).thenReturn("local");
        when(cloud.provider()).thenReturn("s3");
        var operator = new UserSystemEntity();
        var role = new com.seminario.legaladministrator.modules.users.RoleEntity();
        role.setName("Administrador");
        operator.setRole(role);
        when(access.current()).thenReturn(operator);
        var legalCase = new LegalProcessEntity();
        legalCase.setTotalAmount(new java.math.BigDecimal("100.00"));
        when(cases.findById(1L)).thenReturn(Optional.of(legalCase));
        var payment = new com.seminario.legaladministrator.modules.payments.CasePaymentEntity();
        payment.setAmount(new java.math.BigDecimal("100.00"));
        when(payments.findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(1L)).thenReturn(List.of(payment));
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test void caseCompletionRequiresMandatoryRequirementsButNotOptionalOnes() {
        var legalCase = new LegalProcessEntity();
        legalCase.setId(1L);
        legalCase.setVersion(0L);
        legalCase.setTotalAmount(new java.math.BigDecimal("100.00"));
        when(cases.findById(1L)).thenReturn(Optional.of(legalCase));
        when(cases.saveAndFlush(legalCase)).thenReturn(legalCase);
        var mandatory = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        mandatory.setRequiredSnapshot(true);
        var optional = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        when(requirements.findByLegalProcessIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(mandatory, optional));
        assertThatThrownBy(() -> service.completeCase(1L)).isInstanceOf(OperationException.class)
                .hasMessageContaining("todos los requisitos obligatorios");
        assertThat(legalCase.getCurrentStatus()).isEqualTo("OPEN");
        mandatory.setStatus("COMPLETED");
        service.completeCase(1L);
        assertThat(legalCase.getCurrentStatus()).isEqualTo("COMPLETED");
        assertThat(optional.getStatus()).isEqualTo("PENDING");
    }

    @Test void caseCompletionRechecksRequiredPdfEvenForPreviouslyCompletedRequirement() {
        var mandatory = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        mandatory.setId(10L);
        mandatory.setRequiredSnapshot(true);
        mandatory.setRequiresDocumentSnapshot(true);
        mandatory.setStatus("COMPLETED");
        when(requirements.findByLegalProcessIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(mandatory));
        assertThatThrownBy(() -> service.completeCase(1L)).isInstanceOf(OperationException.class)
                .hasMessageContaining("necesita un PDF guardado");
        verify(cases, never()).saveAndFlush(any());
    }

    @Test void caseCompletionRejectsOutstandingBalanceIncludingOneCent() {
        var payment = new com.seminario.legaladministrator.modules.payments.CasePaymentEntity();
        payment.setAmount(new java.math.BigDecimal("99.99"));
        when(payments.findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(1L)).thenReturn(List.of(payment));
        assertThatThrownBy(() -> service.completeCase(1L)).isInstanceOf(OperationException.class)
                .hasMessageContaining("pagado al 100%");
        verify(cases, never()).saveAndFlush(any());
    }

    @Test void caseCompletionRequiresAgreedPositiveTotal() {
        var legalCase = new LegalProcessEntity();
        when(cases.findById(1L)).thenReturn(Optional.of(legalCase));
        assertThatThrownBy(() -> service.completeCase(1L)).isInstanceOf(OperationException.class)
                .hasMessageContaining("costo total mayor que cero");
        legalCase.setTotalAmount(java.math.BigDecimal.ZERO);
        assertThatThrownBy(() -> service.completeCase(1L)).isInstanceOf(OperationException.class)
                .hasMessageContaining("costo total mayor que cero");
    }

    @Test void uploadAssociatesDocumentWithTheRequestedRequirement() {
        var legalCase = new LegalProcessEntity();
        legalCase.setId(1L);
        var requirement = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        requirement.setId(10L);
        requirement.setLegalProcess(legalCase);
        when(requirements.findById(10L)).thenReturn(Optional.of(requirement));
        service.uploadToRequirement(1L, 10L, file);
        var captor = ArgumentCaptor.forClass(CaseDocumentEntity.class);
        verify(documents).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLegalProcessRequirement()).isSameAs(requirement);
    }

    @Test void receiptIsAssociatedWithPaymentAndRejectsOtherCase() {
        var requestId = UUID.randomUUID();
        var legalCase = new LegalProcessEntity();
        legalCase.setId(1L);
        var payment = new com.seminario.legaladministrator.modules.payments.CasePaymentEntity();
        payment.setId(UUID.randomUUID());
        payment.setLegalProcess(legalCase);
        when(payments.findByRequestId(requestId)).thenReturn(Optional.of(payment));
        service.uploadReceipt(1L, requestId, file);
        var captor = ArgumentCaptor.forClass(CaseDocumentEntity.class);
        verify(documents).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPayment()).isSameAs(payment);
        when(documents.findByLegalProcessIdOrderByUploadedAtDesc(1L)).thenReturn(List.of(captor.getValue()));
        assertThatThrownBy(() -> service.uploadReceipt(1L, requestId, file))
                .isInstanceOf(OperationException.class).hasMessageContaining("ya tiene un comprobante");
        legalCase.setId(2L);
        assertThatThrownBy(() -> service.uploadReceipt(1L, requestId, file))
                .isInstanceOf(OperationException.class).hasMessageContaining("Abono no encontrado");
    }

    @Test void rejectsRequirementFromAnotherCaseBeforeStoringFile() {
        var legalCase = new LegalProcessEntity();
        legalCase.setId(2L);
        var requirement = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        requirement.setLegalProcess(legalCase);
        when(requirements.findById(10L)).thenReturn(Optional.of(requirement));
        assertThatThrownBy(() -> service.uploadToRequirement(1L, 10L, file))
                .isInstanceOf(OperationException.class).hasMessageContaining("Requisito no encontrado");
        verifyNoInteractions(documents);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void requiredPdfCannotCompleteUntilAnAssociatedPdfIsStored(boolean required) {
        var legalCase = new LegalProcessEntity();
        legalCase.setId(1L);
        var requirement = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        requirement.setId(10L);
        requirement.setLegalProcess(legalCase);
        requirement.setRequiresDocumentSnapshot(true);
        requirement.setRequiredSnapshot(required);
        when(requirements.findById(10L)).thenReturn(Optional.of(requirement));
        when(documents.findByLegalProcessIdOrderByUploadedAtDesc(1L)).thenReturn(List.of());
        var request = new CaseDocumentService.StatusRequest("COMPLETED");
        assertThatThrownBy(() -> service.updateRequirementStatus(1L, 10L, request))
                .isInstanceOf(OperationException.class).hasMessageContaining("guarda un PDF");
        assertThat(requirement.getStatus()).isEqualTo("PENDING");
        var pdf = new CaseDocumentEntity();
        pdf.setLegalProcessRequirement(requirement);
        pdf.setContentType("application/pdf");
        when(documents.findByLegalProcessIdOrderByUploadedAtDesc(1L)).thenReturn(List.of(pdf));
        service.updateRequirementStatus(1L, 10L, request);
        assertThat(requirement.getStatus()).isEqualTo("COMPLETED");
        assertThat(requirement.getCompletedAt()).isNotNull().isEqualTo(requirement.getUpdatedAt());
        verify(requirements).saveAndFlush(requirement);
        service.updateRequirementStatus(1L, 10L, new CaseDocumentService.StatusRequest("PENDING"));
        assertThat(requirement.getStatus()).isEqualTo("PENDING");
        assertThat(requirement.getCompletedAt()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void optionalPdfRequirementCanCompleteWithoutAttachment(boolean required) {
        var legalCase = new LegalProcessEntity();
        legalCase.setId(1L);
        var requirement = new com.seminario.legaladministrator.modules.processes.LegalProcessRequirementEntity();
        requirement.setLegalProcess(legalCase);
        requirement.setRequiredSnapshot(required);
        when(requirements.findById(10L)).thenReturn(Optional.of(requirement));
        service.updateRequirementStatus(1L, 10L, new CaseDocumentService.StatusRequest("COMPLETED"));
        assertThat(requirement.getStatus()).isEqualTo("COMPLETED");
        assertThat(requirement.getCompletedAt()).isNotNull();
    }

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
        properties.setProvider("s3");
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
