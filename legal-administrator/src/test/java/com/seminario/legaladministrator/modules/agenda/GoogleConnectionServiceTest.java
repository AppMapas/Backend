package com.seminario.legaladministrator.modules.agenda;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.users.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

class GoogleConnectionServiceTest {

    GoogleCalendarProperties p = new GoogleCalendarProperties();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OfficeAccess office = mock(OfficeAccess.class);
    GoogleCalendarGateway google = mock(GoogleCalendarGateway.class);

    GoogleConnectionService service() {
        p.setEnabled(true);
        p.setClientId("id");
        p.setClientSecret("secret");
        p.setCalendarId("office");
        p.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var user = new UserSystemEntity();
        user.setDpi("3002234560901");
        var role = new RoleEntity();
        role.setName("Abogada");
        user.setRole(role);
        when(office.current()).thenReturn(user);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new GoogleConnectionService(
            jdbc,
            new TransactionTemplate(manager),
            office,
            p,
            google,
            new GoogleTokenCipher(p),
            mock(AgendaRepository.class),
            mock(AgendaService.class)
        );
    }

    @Test
    void invalidOriginOrMissingHeaderNeverExchangeOAuthCode() {
        var service = service();
        assertThatThrownBy(() ->
            service.begin("https://evil.example", "XmlHttpRequest")
        ).hasMessageContaining("origen");
        assertThatThrownBy(() -> service.begin("http://localhost:5173", null)).hasMessageContaining("origen");
        verifyNoInteractions(google, jdbc);
    }

    @Test
    void consumedOrExpiredIntentCannotConnect() {
        var service = service();
        assertThatThrownBy(() ->
            service.connect(
                new AgendaDtos.ConnectRequest("auth-code", "expired-state"),
                "http://localhost:5173",
                "XmlHttpRequest"
            )
        ).hasMessageContaining("venció");
        verifyNoInteractions(google);
    }

    @Test
    void authorizationOnlyQueriesTheAuthenticatedUsersConnection() {
        var service = service();
        service.begin("http://localhost:5173", "XmlHttpRequest");
        verify(jdbc).query(
            eq("select * from google_calendar_connection where owner_dpi=? for update"),
            any(RowMapper.class),
            eq("3002234560901")
        );
        verifyNoInteractions(google);
    }
}
