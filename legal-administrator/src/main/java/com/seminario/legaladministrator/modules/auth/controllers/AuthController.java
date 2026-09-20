package com.seminario.legaladministrator.modules.auth.controllers;

import com.seminario.legaladministrator.modules.auth.AuthService;
import com.seminario.legaladministrator.modules.auth.dto.LoginRequestDto;
import com.seminario.legaladministrator.modules.auth.dto.LoginResponseDto;
import com.seminario.legaladministrator.modules.auth.dto.TwoFactorSetupResponseDto;
import com.seminario.legaladministrator.modules.auth.dto.TwoFactorVerificationRequestDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        LoginResponseDto response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<LoginResponseDto> verify2faLogin(@Valid @RequestBody TwoFactorVerificationRequestDto request) {
        LoginResponseDto response = authService.verify2faLogin(request);
        return ResponseEntity.ok(response);
    }

    /*@PostMapping("/2fa/setup")
    public ResponseEntity<TwoFactorSetupResponseDto> setup2fa(
            Authentication authentication,
            @RequestParam(required = false) String email,
            @RequestBody(required = false) Map<String, String> body) {
        String targetEmail = resolveEmail(authentication, email, body);
        TwoFactorSetupResponseDto response = authService.setup2fa(targetEmail);
        return ResponseEntity.ok(response);
    }*/

    @PostMapping("/2fa/setup")
    public ResponseEntity<TwoFactorSetupResponseDto> setup2fa(@RequestBody Map<String, String> requestBody) {
        String email = requestBody.get("email");
        TwoFactorSetupResponseDto response = authService.setup2fa(email);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/2fa/enable")
    public ResponseEntity<Map<String, Object>> enable2fa(
            Authentication authentication,
            @Valid @RequestBody TwoFactorVerificationRequestDto request) {
        String targetEmail = (request.getEmail() != null && !request.getEmail().isBlank())
                ? request.getEmail()
                : (authentication != null ? authentication.getName() : null);

        if (targetEmail == null || targetEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El correo electrónico es requerido"));
        }

        Map<String, Object> response = authService.enable2fa(targetEmail, request.getCode());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/2fa/disable")
    public ResponseEntity<Map<String, Object>> disable2fa(
            Authentication authentication,
            @Valid @RequestBody TwoFactorVerificationRequestDto request) {
        String targetEmail = (request.getEmail() != null && !request.getEmail().isBlank())
                ? request.getEmail()
                : (authentication != null ? authentication.getName() : null);

        if (targetEmail == null || targetEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El correo electrónico es requerido"));
        }

        Map<String, Object> response = authService.disable2fa(targetEmail, request.getCode());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDto> refreshToken(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        LoginResponseDto response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    private String resolveEmail(Authentication authentication, String queryEmail, Map<String, String> body) {
        if (queryEmail != null && !queryEmail.isBlank()) {
            return queryEmail.trim();
        }
        if (body != null && body.get("email") != null && !body.get("email").isBlank()) {
            return body.get("email").trim();
        }
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName().trim();
        }
        throw new IllegalArgumentException("El correo electrónico es requerido para configurar 2FA");
    }
}