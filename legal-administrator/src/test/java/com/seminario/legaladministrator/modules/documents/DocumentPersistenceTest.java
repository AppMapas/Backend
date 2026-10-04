package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "hu05.integration", matches = "true")
@DataJpaTest(showSql = false, properties = {"spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CaseDocumentService.class, DocumentValidator.class, DocumentProperties.class, LocalDocumentStorage.class, OfficeAccess.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentPersistenceTest {
    @TempDir static Path directory;
    @Autowired CaseDocumentService service;
    @Autowired CaseDocumentRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private Long caseId;
    private final MockMultipartFile file = new MockMultipartFile("file", "Escritura.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("hu05.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hu05_test")) {
            throw new IllegalArgumentException("Se requiere una base local desechable hu05_test.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("hu05.test.user", "michael"));
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.documents.provider", () -> "local");
        registry.add("app.documents.local-directory", () -> directory.toString());
    }

    @BeforeEach void prepare() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "abogada@system.com", null, List.of(new SimpleGrantedAuthority("Abogada"))));
        Long typeId = jdbc.queryForObject("INSERT INTO process_type(name, status) VALUES (?, 'PUBLISHED') RETURNING id",
                Long.class, "Documentos " + UUID.randomUUID());
        caseId = jdbc.queryForObject("""
                INSERT INTO legal_process(dpi_client, id_user_system_assigned, id_process_type, current_status,
                    created_at, process_type_name_snapshot, process_type_version_snapshot)
                VALUES ('2541234560101', '3002234560901', ?, 'OPEN', current_date, 'Documentos', 0) RETURNING id
                """, Long.class, typeId);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void committedUploadCanBeListedAndDownloadedInAnotherTransaction() throws Exception {
        var uploaded = service.upload(caseId, file);
        assertThat(repository.findById(uploaded.id())).isPresent();
        assertThat(Files.readAllBytes(directory.resolve(uploaded.id().toString()))).isEqualTo(file.getBytes());
        assertThat(service.list(caseId)).extracting(CaseDocumentService.Summary::id).contains(uploaded.id());
        assertThat(service.content(caseId, uploaded.id()).bytes()).isEqualTo(file.getBytes());
    }

    @Test void rollingBackMetadataAlsoRemovesStoredFile() {
        UUID id = new TransactionTemplate(transactions).execute(status -> {
            var uploaded = service.upload(caseId, file);
            assertThat(Files.exists(directory.resolve(uploaded.id().toString()))).isTrue();
            status.setRollbackOnly();
            return uploaded.id();
        });
        assertThat(repository.findById(id)).isEmpty();
        assertThat(Files.exists(directory.resolve(id.toString()))).isFalse();
    }

    @Test void largeFileIsStreamedToStorageWithoutTruncationOrCorruption() throws Exception {
        byte[] content = syntheticPdf(5 * 1024 * 1024 + 12_345);
        var large = new MockMultipartFile("file", "Contrato.pdf", "application/pdf", content);
        var uploaded = service.upload(caseId, large);
        Path stored = directory.resolve(uploaded.id().toString());
        assertThat(uploaded.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.size(stored)).isEqualTo(content.length);
        // Compara todo el contenido: detecta omisión, duplicación o Corruption del streaming.
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM case_document WHERE id = ?", Long.class, uploaded.id()))
                .isEqualTo((long) content.length);
    }

    private byte[] syntheticPdf(int size) {
        byte[] content = new byte[size];
        new Random(20261004L).nextBytes(content);
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] trailer = "\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, header.length);
        System.arraycopy(trailer, 0, content, size - trailer.length, trailer.length);
        return content;
    }
}
