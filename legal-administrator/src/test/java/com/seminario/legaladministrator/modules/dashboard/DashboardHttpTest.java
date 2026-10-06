package com.seminario.legaladministrator.modules.dashboard;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import com.seminario.legaladministrator.modules.agenda.*;
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

@SpringJUnitConfig(DashboardHttpTest.Config.class)
@WebAppConfiguration
class DashboardHttpTest {

    @Configuration
    @EnableWebMvc
    @Import({
        SecurityConfig.class,
        JwtFilter.class,
        DashboardController.class,
        GlobalExceptionHandler.class,
    })
    static class Config {

        @Bean
        DashboardService dashboard() {
            return mock(DashboardService.class);
        }

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

    @Autowired
    DashboardService dashboard;

    @Test
    void anonymousAndOtherRolesCannotReadDashboard() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
        mvc.perform(
            get("/api/v1/dashboard/reminders").with(
                user("other").authorities(new SimpleGrantedAuthority("Topografo"))
            )
        ).andExpect(status().isForbidden());
    }

    @Test
    void officeRoleIsRecheckedAndResultsCannotBeCached() throws Exception {
        var actor = user("abogada@system.com").authorities(new SimpleGrantedAuthority("Abogada"));
        mvc.perform(get("/api/v1/dashboard/summary").with(actor))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
        when(office.allowed(any())).thenReturn(false);
        mvc.perform(get("/api/v1/dashboard/summary").with(actor)).andExpect(status().isForbidden());
    }

    @Test
    void administratorCanReadRemindersAndUnknownKindsAreRejected() throws Exception {
        var actor = user("admin@system.com").authorities(new SimpleGrantedAuthority("Administrador"));
        mvc.perform(get("/api/v1/dashboard/reminders").with(actor))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/dashboard/reminders").param("kind", "DEBT").with(actor)).andExpect(
            status().isBadRequest()
        );
    }
}
