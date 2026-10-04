package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(CaseDocumentHttpTest.Config.class)
@WebAppConfiguration
class CaseDocumentHttpTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, CaseDocumentController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean CaseDocumentService documents() { return mock(CaseDocumentService.class); }
        @Bean OfficeAccess officeAccess() { return mock(OfficeAccess.class); }
        @Bean JwtProvider jwt() { return mock(JwtProvider.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired CaseDocumentService service;
    @Autowired OfficeAccess officeAccess;
    MockMvc mvc;

    @BeforeEach void prepare() {
        service = AopTestUtils.getUltimateTargetObject(service);
        reset(service, officeAccess);
        when(officeAccess.allowed(any())).thenReturn(true);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void allDocumentEndpointsRequireOfficeRole() throws Exception {
        String base = "/api/v1/legal-processes/1/documents";
        for (String path : new String[]{base, base + "/" + UUID.randomUUID() + "/content", "/api/v1/legal-processes/documents/policy"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("secretaria").authorities(new SimpleGrantedAuthority("Secretaria"))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(multipart(base).file("file", new byte[]{1})).andExpect(status().isUnauthorized());
        mvc.perform(multipart(base).file("file", new byte[]{1})
                .with(user("secretaria").authorities(new SimpleGrantedAuthority("Secretaria"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void revokedOfficeAccessBlocksOtherwiseValidRole() throws Exception {
        when(officeAccess.allowed(any())).thenReturn(false);
        mvc.perform(get("/api/v1/legal-processes/1/documents")
                .with(user("abogada").authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void returnsFileWithPrivateHeadersAndDownloadDisposition() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = "%PDF-1.7\n%%EOF".getBytes();
        when(service.content(1L, id)).thenReturn(new CaseDocumentService.Content(
                new CaseDocumentService.Summary(id, "Escritura pública.pdf", "application/pdf", bytes.length, Instant.now()), bytes));
        mvc.perform(get("/api/v1/legal-processes/1/documents/" + id + "/content?download=true")
                .with(user("abogada").authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
    }

    @Test void missingStoredFileReturns410SoTheClientDoesNotOfferToRetry() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.content(1L, id)).thenThrow(new OperationException(HttpStatus.GONE,
                "El archivo ya no está disponible en el almacenamiento. Vuelve a adjuntarlo para poder consultarlo."));
        mvc.perform(get("/api/v1/legal-processes/1/documents/" + id + "/content")
                .with(user("abogada").authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ya no está disponible")));
    }

    @Test void uploadUsesMultipartFileAndMissingFileReturns400() throws Exception {
        var auth = user("abogada").authorities(new SimpleGrantedAuthority("Abogada"));
        mvc.perform(multipart("/api/v1/legal-processes/1/documents").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/legal-processes/1/documents")
                .file(new MockMultipartFile("file", "DPI.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes()))
                .with(auth)).andExpect(status().isCreated());
        verify(service).upload(eq(1L), any());
    }

    @Test void uploadReturns201WithMetadataAndHidesTheStorageAddress() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.upload(eq(1L), any())).thenReturn(new CaseDocumentService.Summary(
                id, "Escritura.pdf", "application/pdf", 16, Instant.parse("2026-10-04T15:00:00Z")));
        mvc.perform(multipart("/api/v1/legal-processes/1/documents")
                .file(new MockMultipartFile("file", "Escritura.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes()))
                .with(user("abogada").authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Escritura.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(16))
                .andExpect(jsonPath("$.uploadedAt").value("2026-10-04T15:00:00Z"))
                // La dirección interna del objeto no sale en la respuesta.
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.storageProvider").doesNotExist())
                .andExpect(jsonPath("$.legalProcess").doesNotExist())
                .andExpect(jsonPath("$.uploadedBy").doesNotExist())
                .andExpect(jsonPath("$.length()").value(5));
    }
}
