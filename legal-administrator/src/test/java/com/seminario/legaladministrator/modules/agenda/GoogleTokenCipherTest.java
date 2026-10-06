package com.seminario.legaladministrator.modules.agenda;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GoogleTokenCipherTest {

    GoogleCalendarProperties properties() {
        var p = new GoogleCalendarProperties();
        p.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return p;
    }

    @Test
    void authenticatesTokensWithUniqueNoncesAndConnectionBinding() {
        var p = properties();
        var cipher = new GoogleTokenCipher(p);
        String value = cipher.encrypt("refresh-secret", "actor", "subject");
        assertThat(value)
            .doesNotContain("refresh-secret")
            .isNotEqualTo(cipher.encrypt("refresh-secret", "actor", "subject"));
        assertThat(cipher.decrypt(value, "actor", "subject", "v1")).isEqualTo("refresh-secret");
        assertThatThrownBy(() -> cipher.decrypt(value, "other", "subject", "v1")).isInstanceOf(
            IllegalStateException.class
        );
        assertThatThrownBy(() -> cipher.decrypt(value, "actor", "other", "v1")).isInstanceOf(
            IllegalStateException.class
        );
        byte[] data = Base64.getDecoder().decode(value);
        data[data.length - 1] ^= 1;
        assertThatThrownBy(() ->
            cipher.decrypt(Base64.getEncoder().encodeToString(data), "actor", "subject", "v1")
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyChangeRequiresReconnectAndInvalidKeysNeverWritePlaintext() {
        var p = properties();
        var cipher = new GoogleTokenCipher(p);
        String value = cipher.encrypt("secret", "actor", "subject");
        p.setKeyVersion("v2");
        assertThatThrownBy(() -> cipher.decrypt(value, "actor", "subject", "v1")).hasMessageContaining(
            "clave"
        );
        p.setEncryptionKey("bad");
        assertThatThrownBy(() -> cipher.encrypt("secret", "actor", "subject")).isInstanceOf(
            IllegalStateException.class
        );
    }

    @Test
    void disabledOrInsecureConfigurationDoesNotEnableOAuth() {
        var p = properties();
        p.setClientId("public");
        p.setClientSecret("secret");
        p.setCalendarId("office");
        assertThat(p.configured()).isFalse();
        p.setEnabled(true);
        assertThat(p.configured()).isTrue();
        p.setFrontendOrigin("http://office.example");
        assertThat(p.configured()).isFalse();
        p.setFrontendOrigin("https://office.example/anything");
        assertThat(p.configured()).isFalse();
    }
}
