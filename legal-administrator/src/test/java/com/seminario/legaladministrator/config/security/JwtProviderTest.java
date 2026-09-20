package com.seminario.legaladministrator.config.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    // Pasamos una llave secreta de prueba en Base64 o texto largo que cumpla con HS512
    private final String testSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private final JwtProvider jwtProvider = new JwtProvider(testSecret);

    @Test
    void shouldGenerateAndValidateAccessToken() {
        String token = jwtProvider.generateAccessToken("ana@test.com", "Administrador");

        assertThat(token).isNotBlank();
        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getEmailFromToken(token)).isEqualTo("ana@test.com");
        assertThat(jwtProvider.getRoleFromToken(token)).isEqualTo("Administrador");
    }

    @Test
    void shouldValidateRefreshTokenAndRejectInvalidToken() {
        String refreshToken = jwtProvider.generateRefreshToken("ana@test.com");

        assertThat(refreshToken).isNotBlank();
        assertThat(jwtProvider.validateToken(refreshToken)).isTrue();
        assertThat(jwtProvider.validateToken("token-invalido")).isFalse();
    }
}
