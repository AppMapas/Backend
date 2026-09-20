package com.seminario.legaladministrator.modules.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    void shouldGenerateSecretAndQrDataUri() {
        String secret = totpService.generateSecret();

        assertThat(secret).isNotBlank();
        assertThat(secret.length()).isGreaterThan(10);

        String qrCodeDataUri = totpService.getQrCodeDataUri(secret, "ana@test.com");

        assertThat(qrCodeDataUri).startsWith("data:image/png;base64,");
    }

    @Test
    void shouldRejectInvalidOrBlankCodes() {
        String secret = totpService.generateSecret();

        assertThat(totpService.verifyCode(secret, "123456")).isFalse();
        assertThat(totpService.verifyCode(secret, "000000")).isFalse();
        assertThat(totpService.verifyCode(secret, "   ")).isFalse();
    }
}
