package com.seminario.legaladministrator.modules.auth;

import com.seminario.legaladministrator.config.security.JwtProvider;
import com.seminario.legaladministrator.modules.auth.dto.LoginRequestDto;
import com.seminario.legaladministrator.modules.auth.dto.LoginResponseDto;
import com.seminario.legaladministrator.modules.auth.exceptions.InvalidCredentialsException;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AuthService {
    private final UserSystemRepository userSystemRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public LoginResponseDto login(LoginRequestDto request) {
        UserSystemEntity user = userSystemRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Credenciales inválidas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Credenciales inválidas");
        }

        return getLoginResponseDto(user);
    }

    public LoginResponseDto refreshToken(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new RuntimeException("Refresh token inválido o expirado");
        }

        String email = jwtProvider.getEmailFromToken(refreshToken);

        UserSystemEntity user = userSystemRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        return getLoginResponseDto(user);
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
                .build();
    }
}