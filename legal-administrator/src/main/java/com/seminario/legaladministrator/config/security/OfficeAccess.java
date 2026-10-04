package com.seminario.legaladministrator.config.security;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import com.seminario.legaladministrator.shared.OperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("officeAccess")
@RequiredArgsConstructor
public class OfficeAccess {
    private final UserSystemRepository users;

    public boolean allowed(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return users.findByEmail(authentication.getName())
                .filter(user -> user.getRole() != null)
                .map(user -> user.getRole().getName())
                .filter(role -> role.equals("Abogada") || role.equals("Administrador"))
                .isPresent();
    }

    public UserSystemEntity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!allowed(authentication)) {
            throw new OperationException(HttpStatus.FORBIDDEN, "Tu perfil no tiene acceso a la gestión de expedientes.");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new OperationException(HttpStatus.UNAUTHORIZED, "La sesión no corresponde a un usuario vigente."));
    }
}
