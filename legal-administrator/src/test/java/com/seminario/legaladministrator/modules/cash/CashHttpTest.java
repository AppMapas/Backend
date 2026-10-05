package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import com.seminario.legaladministrator.modules.cash.CashDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(CashHttpTest.Config.class)
@WebAppConfiguration
class CashHttpTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, CashController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean CashService cash() { return mock(CashService.class); }
        @Bean OfficeAccess officeAccess() { return mock(OfficeAccess.class); }
        @Bean JwtProvider jwt() { return mock(JwtProvider.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired CashService service;
    @Autowired OfficeAccess access;
    MockMvc mvc;
    @BeforeEach void prepare() {
        service = AopTestUtils.getUltimateTargetObject(service);
        reset(service, access);
        when(access.allowed(any())).thenReturn(true);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void allRoutesRequireOfficeRoleAndAuthentication() throws Exception {
        var requests = List.of(get("/api/v1/cash"), get("/api/v1/cash/categories"),
                get("/api/v1/cash/incomes/1/summary"),
                get("/api/v1/cash/expenses/1"), post("/api/v1/cash/expenses"),
                post("/api/v1/cash/incomes"), post("/api/v1/cash/expenses/1/annul"),
                post("/api/v1/cash/incomes/1/11111111-1111-4111-8111-111111111111/annul"));
        for (var request : requests) mvc.perform(request).andExpect(status().isUnauthorized());
        for (var request : requests) {
            mvc.perform(request.with(user("secretaria").authorities(new SimpleGrantedAuthority("Secretaria"))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void revokedDatabaseRoleCannotUseATokenWithOldPermissions() throws Exception {
        when(access.allowed(any())).thenReturn(false);
        mvc.perform(get("/api/v1/cash/categories").with(office())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void rejectsMissingFieldsRoundingAndIncompatibleCategories() throws Exception {
        for (String amount : List.of("0", "-1", "10.005", "1000000000000.00")) {
            mvc.perform(post("/api/v1/cash/expenses").with(office()).contentType(MediaType.APPLICATION_JSON)
                    .content(expense(amount))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.amount").exists());
        }
        mvc.perform(post("/api/v1/cash/expenses").with(office()).contentType(MediaType.APPLICATION_JSON)
                .content(expense("1").replace("UTILES_OFICINA", "TRAMITES"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/cash/incomes").with(office()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.caseId").exists());
        mvc.perform(post("/api/v1/cash/expenses/1/annul").with(office()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":-1,\"reason\":\"  \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.reason").exists());
        verifyNoInteractions(service);
    }

    @Test void preservesCreationAndReplayStatusAndPreventsBrowserCaching() throws Exception {
        when(service.registerExpense(any())).thenReturn(new Mutation("EXPENSE", "12", false, null))
                .thenReturn(new Mutation("EXPENSE", "12", true, null));
        mvc.perform(post("/api/v1/cash/expenses").with(office()).contentType(MediaType.APPLICATION_JSON)
                .content(expense("0.10"))).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Idempotency-Replayed", "false"));
        mvc.perform(post("/api/v1/cash/expenses").with(office()).contentType(MediaType.APPLICATION_JSON)
                .content(expense("0.10"))).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
    }

    @Test void categoryCatalogHasExactlyTheSupportedDirections() throws Exception {
        mvc.perform(get("/api/v1/cash/categories").with(office())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("INGRESO"))
                .andExpect(jsonPath("$[1].direction").value("EGRESO"))
                .andExpect(jsonPath("$[2].direction").value("EGRESO"));
    }

    @Test void agreedAndFinalPaymentInformationTravelsAsExactMoneyWithoutCaching() throws Exception {
        when(service.incomeSummary(1L)).thenReturn(new IncomeSummary(1L, "2500.00", "2500.00", "0.00",
                true, true, false, true, 4L, "1000.00", 1, java.time.LocalDate.of(2026, 10, 1)));
        mvc.perform(get("/api/v1/cash/incomes/1/summary").with(office()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalAmount").value("2500.00"))
                .andExpect(jsonPath("$.pendingAmount").value("0.00"))
                .andExpect(jsonPath("$.finalPaymentCount").value(1))
                .andExpect(jsonPath("$.finalPaymentAmount").value("1000.00"))
                .andExpect(jsonPath("$.settled").value(true));
    }

    @Test void revokedRoleAlsoBlocksTheFinancialPreview() throws Exception {
        when(access.allowed(any())).thenReturn(false);
        mvc.perform(get("/api/v1/cash/incomes/1/summary").with(office()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor office() {
        return user("abogada").authorities(new SimpleGrantedAuthority("Abogada"));
    }
    private String expense(String amount) {
        return """
                {"requestId":"11111111-1111-4111-8111-111111111111", "category":"UTILES_OFICINA",
                 "amount":"%s", "description":"Papel", "date":"2020-01-01", "paymentMethod":"EFECTIVO"}
                """.formatted(amount);
    }
}
