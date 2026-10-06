package com.seminario.legaladministrator.modules.agenda;

import java.net.URI;
import java.util.Base64;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Valida la configuración necesaria antes de habilitar Google. La clave de cifrado y el secreto OAuth solo pertenecen al backend. */
@Component
@ConfigurationProperties(prefix = "app.agenda.google")
@Getter
@Setter
public class GoogleCalendarProperties {

    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String calendarId = "";
    private String frontendOrigin = "http://localhost:5173";
    private String encryptionKey = "";
    private String keyVersion = "v1";
    public static final String SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    /** Habilita Google solo si están presentes las credenciales, el origen permitido y una clave Base64 de 32 bytes. */
    public boolean configured() {
        try {
            URI origin = URI.create(frontendOrigin);
            boolean secure = origin.getScheme().equals("https");
            boolean local =
                origin.getScheme().equals("http") &&
                ("localhost".equals(origin.getHost()) || "127.0.0.1".equals(origin.getHost()));
            boolean bareOrigin =
                origin.getHost() != null &&
                origin.getUserInfo() == null &&
                origin.getQuery() == null &&
                origin.getFragment() == null &&
                (origin.getPath() == null || origin.getPath().isEmpty());
            return (
                enabled &&
                secureOrLocal(secure, local) &&
                bareOrigin &&
                !clientId.isBlank() &&
                !clientSecret.isBlank() &&
                !calendarId.isBlank() &&
                calendarId.length() <= 255 &&
                !keyVersion.isBlank() &&
                keyVersion.length() <= 40 &&
                Base64.getDecoder().decode(encryptionKey).length == 32
            );
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean secureOrLocal(boolean secure, boolean local) {
        return secure || local;
    }
}
