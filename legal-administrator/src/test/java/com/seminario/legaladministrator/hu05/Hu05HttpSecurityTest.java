package com.seminario.legaladministrator.hu05;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import com.seminario.legaladministrator.modules.processes.controller.LegalProcessController;
import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.*;
import com.seminario.legaladministrator.modules.processes.service.LegalProcessService;
import com.seminario.legaladministrator.modules.users.controller.ClientUserController;
import com.seminario.legaladministrator.modules.users.service.ClientUserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(Hu05HttpSecurityTest.HttpConfiguration.class)
@WebAppConfiguration
class Hu05HttpSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired ClientUserService clients;
    @Autowired LegalProcessService cases;
    @Autowired JwtProvider jwt;
    @Autowired OfficeAccess officeAccess;
    private MockMvc mvc;

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, ClientUserController.class,
            LegalProcessController.class, GlobalExceptionHandler.class})
    static class HttpConfiguration {
        @Bean ClientUserService clients() { return mock(ClientUserService.class); }
        @Bean LegalProcessService cases() { return mock(LegalProcessService.class); }
        @Bean JwtProvider jwt() { return mock(JwtProvider.class); }
        @Bean OfficeAccess officeAccess() { return mock(OfficeAccess.class); }
    }

    @BeforeEach
    void prepare() {
        clients = AopTestUtils.getUltimateTargetObject(clients);
        cases = AopTestUtils.getUltimateTargetObject(cases);
        reset(clients, cases, jwt, officeAccess);
        when(officeAccess.allowed(any())).thenReturn(true);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void unauthenticatedRequestsReturn401WithoutCallingService() throws Exception {
        mvc.perform(get("/api/v1/clients/search")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/legal-processes").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(clients, cases);
    }

    @Test
    void unauthorizedRoleCannotReadOrWritePersonalData() throws Exception {
        var secretary = user("secretaria@system.com").authorities(new SimpleGrantedAuthority("Secretaria"));
        mvc.perform(get("/api/v1/clients").with(secretary)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/legal-processes/1").with(secretary)).andExpect(status().isForbidden());
        verifyNoInteractions(clients, cases);
    }

    @Test
    void authorizedRoleReadsAndResponsesPreventCaching() throws Exception {
        when(clients.getAllClients()).thenReturn(List.of());
        mvc.perform(get("/api/v1/clients").with(user("abogada@system.com")
                        .authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void malformedAndRefreshTokensReturn401() throws Exception {
        mvc.perform(get("/api/v1/clients").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        when(jwt.validateToken("refresh")).thenReturn(true);
        when(jwt.getEmailFromToken("refresh")).thenReturn("abogada@system.com");
        mvc.perform(get("/api/v1/clients").header("Authorization", "Bearer refresh"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(clients);
    }

    @Test
    void invalidBodyReturns400BeforeWriting() throws Exception {
        mvc.perform(post("/api/v1/clients").with(user("abogada@system.com")
                        .authorities(new SimpleGrantedAuthority("Abogada")))
                .contentType("application/json").content("{\"dpi\":\"abc\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.dpi").exists());
        verifyNoInteractions(clients);
    }

    @Test
    void wrongParameterTypesReturn400() throws Exception {
        mvc.perform(get("/api/v1/legal-processes?page=no")
                        .with(user("admin@system.com").authorities(new SimpleGrantedAuthority("Administrador"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cases);
    }

    @Test
    void corsRejectsUnknownOriginAndAllowsConfiguredFrontend() throws Exception {
        mvc.perform(options("/api/v1/clients")
                        .header("Origin", "https://unknown.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/clients")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsConfigurationRejectsWildcards() {
        var config = new SecurityConfig(new JwtFilter(jwt));
        ReflectionTestUtils.setField(config, "allowedOrigins", "*");
        assertThatThrownBy(config::corsConfigurationSource).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unexpectedFailureDoesNotExposeDatabaseDetails() throws Exception {
        when(clients.getAllClients()).thenThrow(new IllegalStateException("password=secret SQL private"));
        mvc.perform(get("/api/v1/clients").with(user("abogada@system.com")
                        .authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void creationReturns201AndRetryReturns200WithReplayHeader() throws Exception {
        String body = """
                {"requestId":"27d3bb30-53c5-4354-8a80-60f52237c9e8","clientDpi":"1234567890123",
                 "processTypeId":1,"processTypeVersion":0}
                """;
        when(cases.create(any())).thenReturn(new Creation(null, false), new Creation(null, true));
        var lawyer = user("abogada@system.com").authorities(new SimpleGrantedAuthority("Abogada"));
        mvc.perform(post("/api/v1/legal-processes").with(lawyer).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"));
        mvc.perform(post("/api/v1/legal-processes").with(lawyer).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
    }
}
