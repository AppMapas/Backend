package com.seminario.legaladministrator.modules.auth;

import com.seminario.legaladministrator.config.security.JwtProvider;
import com.seminario.legaladministrator.modules.auth.dto.LoginRequestDto;
import com.seminario.legaladministrator.modules.auth.dto.LoginResponseDto;
import com.seminario.legaladministrator.modules.auth.dto.TwoFactorVerificationRequestDto;
import com.seminario.legaladministrator.modules.auth.exceptions.InvalidCredentialsException;
import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import com.seminario.legaladministrator.modules.users.RoleEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtProvider jwtProvider = new JwtProvider();

    @Mock
    private UserSystemRepository userSystemRepository;

    @Mock
    private TotpService totpService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginShouldReturnJwtWhenCredentialsAreValidAndTwoFactorIsDisabled() {
        UserSystemEntity user = buildUser();
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        LoginResponseDto response = service.login(new LoginRequestDto("ana@test.com", "secret123"));

        assertThat(response.getEmail()).isEqualTo("ana@test.com");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.isTwoFactorRequired()).isFalse();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
    }

    @Test
    void loginShouldRequireTwoFactorWhenUserHasItEnabled() {
        UserSystemEntity user = buildUser();
        user.setTwoFactorEnabled(true);
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        LoginResponseDto response = service.login(new LoginRequestDto("ana@test.com", "secret123"));

        assertThat(response.isTwoFactorRequired()).isTrue();
        assertThat(response.getMessage()).contains("2FA");
    }

    @Test
    void loginShouldThrowWhenPasswordIsWrong() {
        UserSystemEntity user = buildUser();
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);

        assertThatThrownBy(() -> service.login(new LoginRequestDto("ana@test.com", "wrong-pass")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Credenciales inválidas");
    }

    @Test
    void setup2faShouldGenerateSecretAndSaveUser() {
        UserSystemEntity user = buildUser();
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));
        when(totpService.generateSecret()).thenReturn("JBSWY3DPEHPK3PXP");
        when(totpService.getQrCodeDataUri(anyString(), anyString())).thenReturn("data:image/png;base64,abc123");

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        var response = service.setup2fa("ana@test.com");

        assertThat(response.getSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(response.getQrCodeUri()).startsWith("data:image/png;base64,");
        assertThat(user.getTwoFactorCode()).isEqualTo("JBSWY3DPEHPK3PXP");
        verify(userSystemRepository).save(user);
    }

    @Test
    void enable2faShouldActivateUserWhenCodeIsValid() {
        UserSystemEntity user = buildUser();
        user.setTwoFactorCode("JBSWY3DPEHPK3PXP");
        user.setTwoFactorExpiry(LocalDateTime.now().plusMinutes(5));
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));
        when(totpService.verifyCode("JBSWY3DPEHPK3PXP", "123456")).thenReturn(true);

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        Map<String, Object> response = service.enable2fa("ana@test.com", "123456");

        assertThat(response.get("enabled")).isEqualTo(true);
        assertThat(user.isTwoFactorEnabled()).isTrue();
        verify(userSystemRepository).save(user);
    }

    @Test
    void verify2faLoginShouldReturnTokensWhenCodeIsValid() {
        UserSystemEntity user = buildUser();
        user.setTwoFactorEnabled(true);
        user.setTwoFactorCode("JBSWY3DPEHPK3PXP");
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));
        when(totpService.verifyCode("JBSWY3DPEHPK3PXP", "123456")).thenReturn(true);

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        LoginResponseDto response = service.verify2faLogin(new TwoFactorVerificationRequestDto("ana@test.com", "123456"));

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.isTwoFactorRequired()).isFalse();
    }

    @Test
    void refreshTokenShouldReturnNewLoginResponseForValidToken() {
        UserSystemEntity user = buildUser();
        when(userSystemRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        AuthService service = new AuthService(userSystemRepository, passwordEncoder, jwtProvider, totpService);
        String refreshToken = jwtProvider.generateRefreshToken("ana@test.com");
        LoginResponseDto response = service.refreshToken(refreshToken);

        assertThat(response.getEmail()).isEqualTo("ana@test.com");
        assertThat(response.getRefreshToken()).isNotBlank();
    }

    private UserSystemEntity buildUser() {
        RoleEntity role = RoleEntity.builder()
                .id(1L)
                .name("ADMIN")
                .description("Administrador")
                .build();

        MaritalStatusEntity maritalStatus = MaritalStatusEntity.builder()
                .id(1L)
                .name("Soltero")
                .description("Persona soltera")
                .build();

        CountryEntity country = CountryEntity.builder()
                .id(1L)
                .name("Guatemala")
                .isoCode("GT")
                .build();

        return UserSystemEntity.builder()
                .dpi("123456789012")
                .firstName("Ana")
                .lastName("López")
                .age(30)
                .email("ana@test.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .maritalStatus(maritalStatus)
                .nationality(country)
                .role(role)
                .createdAt(LocalDate.now())
                .build();
    }
}
