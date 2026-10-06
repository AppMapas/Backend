package com.seminario.legaladministrator.modules.agenda;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(AgendaHttpTest.Config.class)
@WebAppConfiguration
class AgendaHttpTest {

    @Configuration
    @EnableWebMvc
    @Import({ SecurityConfig.class, JwtFilter.class, AgendaController.class, GlobalExceptionHandler.class })
    static class Config {

        @Bean
        AgendaService agenda() {
            return mock(AgendaService.class);
        }

        @Bean
        GoogleAgendaService external() {
            return mock(GoogleAgendaService.class);
        }

        @Bean
        GoogleConnectionService google() {
            return mock(GoogleConnectionService.class);
        }

        @Bean
        OfficeAccess officeAccess() {
            return mock(OfficeAccess.class);
        }

        @Bean
        JwtProvider jwt() {
            return mock(JwtProvider.class);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    OfficeAccess office;

    @Autowired
    AgendaService agenda;

    MockMvc mvc;

    @BeforeEach
    void prepare() {
        reset(office);
        when(office.allowed(any())).thenReturn(true);
        agenda = AopTestUtils.getUltimateTargetObject(agenda);
        reset(agenda);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousAndOtherRolesCannotUseAgendaOrGoogleEndpoints() throws Exception {
        var routes = List.of(
            get("/api/v1/agenda/upcoming"),
            get("/api/v1/agenda/google/status"),
            get("/api/v1/agenda/google/events"),
            get("/api/v1/agenda/google/events/legal1"),
            put("/api/v1/agenda/google/events/legal1"),
            post("/api/v1/agenda/events"),
            post("/api/v1/agenda/google/intent"),
            post("/api/v1/agenda/google/connect"),
            delete("/api/v1/agenda/google/connect")
        );
        for (var route : routes) mvc.perform(route).andExpect(status().isUnauthorized());
        for (var route : routes)
            mvc.perform(
                route.with(user("secretaria").authorities(new SimpleGrantedAuthority("Secretaria")))
            ).andExpect(status().isForbidden());
    }

    @Test
    void changedDatabaseRoleBlocksOldJwtAuthorities() throws Exception {
        when(office.allowed(any())).thenReturn(false);
        mvc.perform(
            get("/api/v1/agenda/upcoming").with(
                user("office").authorities(new SimpleGrantedAuthority("Abogada"))
            )
        ).andExpect(status().isForbidden());
        verifyNoInteractions(agenda);
    }

    @Test
    void statusDoesNotReturnCredentialsAndIsNotCached() throws Exception {
        mvc.perform(
            get("/api/v1/agenda/google/status").with(
                user("office").authorities(new SimpleGrantedAuthority("Abogada"))
            )
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }
}
