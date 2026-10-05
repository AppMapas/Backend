package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.config.exceptions.GlobalExceptionHandler;
import com.seminario.legaladministrator.config.security.*;
import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Contrato HTTP de los abonos: quién puede llamarlos, qué se rechaza antes de
 * tocar la base y qué recibe el frontend. Sin base de datos.
 */
@SpringJUnitConfig(CasePaymentHttpTest.Config.class)
@WebAppConfiguration
class CasePaymentHttpTest {
    private static final String BASE = "/api/v1/legal-processes/1";
    private static final LocalDate TODAY = LocalDate.now();

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, CasePaymentController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean CasePaymentService payments() { return mock(CasePaymentService.class); }
        @Bean OfficeAccess officeAccess() { return mock(OfficeAccess.class); }
        @Bean JwtProvider jwt() { return mock(JwtProvider.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired CasePaymentService service;
    @Autowired OfficeAccess officeAccess;
    MockMvc mvc;

    @BeforeEach void prepare() {
        service = AopTestUtils.getUltimateTargetObject(service);
        reset(service, officeAccess);
        when(officeAccess.allowed(any())).thenReturn(true);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void everyPaymentEndpointIsClosedToOtherRoles() throws Exception {
        var secretary = user("secretaria").authorities(new SimpleGrantedAuthority("Secretaria"));
        UUID paymentId = UUID.randomUUID();
        var requests = List.of(
                get(BASE + "/payments"),
                post(BASE + "/payments").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put(BASE + "/total-amount").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete(BASE + "/payments/" + paymentId));

        for (var request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (var request : requests) {
            mvc.perform(request.with(secretary)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void revokedOfficeAccessBlocksAnOtherwiseValidRole() throws Exception {
        when(officeAccess.allowed(any())).thenReturn(false);

        mvc.perform(get(BASE + "/payments")
                .with(user("abogada").authorities(new SimpleGrantedAuthority("Abogada"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void anInvalidAmountIsRejectedNamingTheOffendingField() throws Exception {
        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("10.005")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.amount").exists())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test void aMissingConceptAndAMissingVersionAreAlsoFieldErrors() throws Exception {
        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("100.00").replace("\"concept\":\"50% inicial\"", "\"concept\":\"  \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.concept").exists());

        mvc.perform(put(BASE + "/total-amount").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalAmount\":\"1000.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.version").exists());
        verifyNoInteractions(service);
    }

    @Test void aFutureDateIsRejectedBeforeTheServiceSeesIt() throws Exception {
        String json = paymentJson("100.00").replace(TODAY.toString(), TODAY.plusDays(1).toString());

        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.paymentDate").exists());
        verifyNoInteractions(service);
    }

    @Test void malformedJsonAndAnUnknownPaymentTypeAreBadRequests() throws Exception {
        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("JSON no válidos")));

        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("100.00").replace("\"ABONO\"", "\"REPARTO\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void aNewPaymentIsCreatedAndItsReplayIsMarkedInTheHeader() throws Exception {
        when(service.register(eq(1L), any()))
                .thenReturn(new Creation(ledger(), false))
                .thenReturn(new Creation(ledger(), true));

        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("5000.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.paidAmount").value(100.00))
                .andExpect(jsonPath("$.pendingAmount").value(9900.00));

        // El mismo toque repetido no duplica el abono: responde 200 y lo dice.
        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("5000.00")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        verify(service, times(2)).register(eq(1L), any());
    }

    @Test void theLedgerTravelsWithItsPendingBalanceInTwoDecimals() throws Exception {
        when(service.ledger(1L)).thenReturn(ledger());

        // La escala 2 llega al frontend: 100.00 no puede viajar como 100.
        mvc.perform(get(BASE + "/payments").with(abogada()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"paidAmount\":100.00")))
                .andExpect(jsonPath("$.totalAgreed").value(true))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.overpaid").value(false))
                .andExpect(jsonPath("$.caseVersion").value(4))
                .andExpect(jsonPath("$.payments[0].registeredBy").value("3002234560901"));
    }

    @Test void anUnagreedTotalSendsNullPendingInsteadOfInventingZero() throws Exception {
        when(service.ledger(2L)).thenReturn(new Ledger(null, new BigDecimal("100.00"),
                BigDecimal.ZERO, null, false, false, false, 1L, true, List.of()));

        // Sin costo pactado el saldo pendiente viaja explícitamente null: el
        // frontend debe distinguir "no definido" de un pendiente de Q0.00.
        mvc.perform(get("/api/v1/legal-processes/2/payments").with(abogada()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"totalAmount\":null")))
                .andExpect(content().string(containsString("\"pendingAmount\":null")))
                .andExpect(jsonPath("$.totalAgreed").value(false));
    }

    @Test void serviceErrorsKeepTheirStatusAndMessage() throws Exception {
        when(service.register(eq(1L), any())).thenThrow(
                new OperationException(HttpStatus.CONFLICT, "La clave de solicitud ya se utilizó."));
        when(service.annul(eq(1L), any())).thenThrow(
                new OperationException(HttpStatus.NOT_FOUND, "El abono no existe en este expediente."));
        UUID paymentId = UUID.randomUUID();

        mvc.perform(post(BASE + "/payments").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("5000.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La clave de solicitud ya se utilizó."));

        mvc.perform(delete(BASE + "/payments/" + paymentId).with(abogada()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("El abono no existe en este expediente."));
    }

    @Test void aPaymentIdThatIsNotAUuidIsABadRequest() throws Exception {
        mvc.perform(delete(BASE + "/payments/no-es-uuid").with(abogada()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void settingTheTotalAndAnnullingReturnTheUpdatedLedger() throws Exception {
        when(service.setTotalAmount(eq(1L), any()))
                .thenReturn(new Ledger(new BigDecimal("10000.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("10000.00"), true, false, false, 5L, true, List.of()));
        when(service.annul(eq(1L), any())).thenReturn(ledger());

        mvc.perform(put(BASE + "/total-amount").with(abogada())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"totalAmount\":\"10000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAmount").value(10000.00))
                .andExpect(jsonPath("$.caseVersion").value(5));

        mvc.perform(delete(BASE + "/payments/" + UUID.randomUUID()).with(abogada()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].active").value(false));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor abogada() {
        return user("abogada").authorities(new SimpleGrantedAuthority("Abogada"));
    }

    private static Ledger ledger() {
        return new Ledger(new BigDecimal("10000.00"), new BigDecimal("100.00"), new BigDecimal("100.00"),
                new BigDecimal("9900.00"), true, false, false, 4L, true,
                List.of(new PaymentResponse(UUID.randomUUID(), new BigDecimal("100.00"), PaymentType.ANTICIPO,
                        PaymentMethod.EFECTIVO, "50% inicial", TODAY, null, false,
                        "3002234560901", Instant.parse("2026-10-01T15:00:00Z"))));
    }

    private static String paymentJson(String amount) {
        return """
                {"requestId":"%s","amount":%s,"paymentType":"ABONO","paymentMethod":"EFECTIVO",
                 "concept":"50%% inicial","paymentDate":"%s","reference":"recibo 1"}
                """.formatted(UUID.randomUUID(), amount, TODAY);
    }
}