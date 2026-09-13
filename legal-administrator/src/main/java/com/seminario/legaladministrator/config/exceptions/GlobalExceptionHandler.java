package com.seminario.legaladministrator.config.exceptions;

import com.seminario.legaladministrator.modules.auth.exceptions.InvalidCredentialsException;
import com.seminario.legaladministrator.modules.users.Exceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.naming.AuthenticationException;
import java.nio.file.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({
            UserAlreadyExistsException.class
    })
    public ResponseEntity<Map<String, Object>> handleConflictExceptions(RuntimeException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({
            UserNotFoundException.class,
            MaritalStatusNotFoundException.class,
            NationalityNotFoundException.class,
            RoleNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleNotFoundExceptions(RuntimeException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({
            InvalidPasswordException.class,
            InvalidCredentialsException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequestExceptions(RuntimeException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "No tienes permisos suficientes para acceder a este recurso.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Token inválido, expirado o ausente.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGlobalException(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado en el servidor: " + ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(
            org.springframework.web.bind.MethodArgumentNotValidException ex) {

        Map<String, String> errors = new java.util.HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("timestamp", java.time.LocalDateTime.now());
        response.put("status", org.springframework.http.HttpStatus.BAD_REQUEST.value());
        response.put("error", "Bad Request");
        response.put("message", "Errores de validación en los campos");
        response.put("details", errors);

        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(response);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now().toString());
        errorDetails.put("status", status.value());
        errorDetails.put("error", status.getReasonPhrase());
        errorDetails.put("message", message);
        return ResponseEntity.status(status).body(errorDetails);
    }
}