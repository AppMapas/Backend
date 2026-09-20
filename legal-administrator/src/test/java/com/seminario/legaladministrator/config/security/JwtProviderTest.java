package com.seminario.legaladministrator.config.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private final JwtProvider jwtProvider = new JwtProvider();

    @Test
    void shouldGenerateAndValidateAccessToken() {
        String token = jwtProvider.generateAccessToken("ana@test.com", "ADMIN");

        assertThat(token).isNotBlank();
        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getEmailFromToken(token)).isEqualTo("ana@test.com");
        assertThat(jwtProvider.getRoleFromToken(token)).isEqualTo("ADMIN");
    }

    @Test
    void shouldValidateRefreshTokenAndRejectInvalidToken() {
        String refreshToken = jwtProvider.generateRefreshToken("ana@test.com");

        assertThat(refreshToken).isNotBlank();
        assertThat(jwtProvider.validateToken(refreshToken)).isTrue();
        assertThat(jwtProvider.validateToken("token-invalido")).isFalse();
    }
}
