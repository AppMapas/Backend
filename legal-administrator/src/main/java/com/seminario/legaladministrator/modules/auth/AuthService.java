package com.seminario.legaladministrator.modules.auth;

import com.seminario.legaladministrator.config.security.JwtProvider;
import com.seminario.legaladministrator.modules.auth.dto.LoginRequestDto;
import com.seminario.legaladministrator.modules.auth.dto.LoginResponseDto;
import com.seminario.legaladministrator.modules.auth.dto.TwoFactorSetupResponseDto;
import com.seminario.legaladministrator.modules.auth.dto.TwoFactorVerificationRequestDto;
import com.seminario.legaladministrator.modules.auth.exceptions.InvalidCredentialsException;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class AuthService {
    private final UserSystemRepository userSystemRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final TotpService totpService;

    public LoginResponseDto login(LoginRequestDto request) {
        UserSystemEntity user = userSystemRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Credenciales inválidas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Credenciales inválidas");
        }

        if (user.isTwoFactorEnabled()) {
            return LoginResponseDto.builder()
                    .email(user.getEmail())
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .twoFactorRequired(true)
                    .message("Se requiere código de verificación en dos pasos (2FA)")
                    .build();
        }

        return getLoginResponseDto(user);
    }

    public TwoFactorSetupResponseDto setup2fa(String email) {
        UserSystemEntity user = userSystemRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Usuario no encontrado con email: " + email));

        String secret = totpService.generateSecret();
        String qrCodeUri = totpService.getQrCodeDataUri(secret, user.getEmail());

        user.setTwoFactorCode(secret);
        user.setTwoFactorExpiry(LocalDateTime.now().plusMinutes(15));
        userSystemRepository.save(user);

        return TwoFactorSetupResponseDto.builder()
                .secret(secret)
                .qrCodeUri(qrCodeUri)
                .manualEntryKey(secret)
                .build();
    }

    public Map<String, Object> enable2fa(String email, String code) {
        UserSystemEntity user = userSystemRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Usuario no encontrado con email: " + email));

        if (user.getTwoFactorCode() == null || user.getTwoFactorCode().isBlank()) {
            throw new InvalidCredentialsException("No se ha iniciado la configuración de 2FA para este usuario");
        }

        if (user.getTwoFactorExpiry() != null && LocalDateTime.now().isAfter(user.getTwoFactorExpiry())) {
            throw new InvalidCredentialsException("La sesión de configuración de 2FA ha expirado. Solicite una nueva configuración.");
        }

        if (!totpService.verifyCode(user.getTwoFactorCode(), code)) {
            throw new InvalidCredentialsException("Código 2FA incorrecto");
        }

        user.setTwoFactorEnabled(true);
        user.setTwoFactorExpiry(null);
        userSystemRepository.save(user);

        return Map.of(
                "message", "2FA habilitado exitosamente",
                "enabled", true
        );
    }

    public Map<String, Object> disable2fa(String email, String code) {
        UserSystemEntity user = userSystemRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Usuario no encontrado con email: " + email));

        if (!user.isTwoFactorEnabled()) {
            return Map.of(
                    "message", "El usuario ya tiene 2FA deshabilitado",
                    "enabled", false
            );
        }

        if (!totpService.verifyCode(user.getTwoFactorCode(), code)) {
            throw new InvalidCredentialsException("Código 2FA incorrecto");
        }

        user.setTwoFactorEnabled(false);
        user.setTwoFactorCode(null);
        user.setTwoFactorExpiry(null);
        userSystemRepository.save(user);

        return Map.of(
                "message", "2FA deshabilitado exitosamente",
                "enabled", false
        );
    }

    public LoginResponseDto verify2faLogin(TwoFactorVerificationRequestDto request) {
        UserSystemEntity user = userSystemRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Credenciales inválidas"));

        if (!user.isTwoFactorEnabled()) {
            throw new InvalidCredentialsException("El usuario no tiene 2FA habilitado");
        }

        if (!totpService.verifyCode(user.getTwoFactorCode(), request.getCode())) {
            throw new InvalidCredentialsException("Código 2FA incorrecto o expirado");
        }

        return getLoginResponseDto(user);
    }

    public LoginResponseDto refreshToken(String refreshToken) {
        if (refreshToken == null || !jwtProvider.validateRefreshToken(refreshToken)) {
            throw new RuntimeException("Refresh Token inválido o expirado");
        }

        String email = jwtProvider.getEmailFromToken(refreshToken);

        UserSystemEntity user = userSystemRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        String role = user.getRole().getName();

        // Generar el nuevo Access Token con su rol correspondiente
        String newAccessToken = jwtProvider.generateAccessToken(email, role);

        return LoginResponseDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private LoginResponseDto getLoginResponseDto(UserSystemEntity user) {
        String roleName = (user.getRole() != null) ? user.getRole().getName() : "USER";
        String newAccessToken = jwtProvider.generateAccessToken(user.getEmail(), roleName);
        String newRefreshToken = jwtProvider.generateRefreshToken(user.getEmail());

        return LoginResponseDto.builder()
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(roleName)
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .twoFactorRequired(false)
                .build();
    }
}