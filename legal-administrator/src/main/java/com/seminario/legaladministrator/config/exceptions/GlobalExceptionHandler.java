package com.seminario.legaladministrator.config.exceptions;

import com.seminario.legaladministrator.modules.auth.exceptions.InvalidCredentialsException;
import com.seminario.legaladministrator.modules.processes.ProcessCatalogException;
import com.seminario.legaladministrator.modules.users.Exceptions.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.naming.AuthenticationException;
import java.nio.file.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import com.seminario.legaladministrator.shared.OperationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadSize(Exception ex) {
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera el límite de tamaño permitido.");
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleMultipart(Exception ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Envía un archivo en el campo 'file' mediante multipart/form-data.");
    }

    @ExceptionHandler(OperationException.class)
    public ResponseEntity<Map<String, Object>> handleOperation(OperationException ex) {
        return buildResponse(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(Exception ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "La solicitud contiene parámetros o JSON no válidos.");
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityAccessDenied(Exception ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "No tienes permisos para este recurso.");
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleBusyRecord(Exception ex) {
        return buildResponse(HttpStatus.CONFLICT, "El registro está siendo modificado. Inténtalo nuevamente.");
    }
    @ExceptionHandler(ProcessCatalogException.class)
    public ResponseEntity<Map<String, Object>> handleProcessCatalogException(ProcessCatalogException ex) {
        return buildResponse(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return buildResponse(HttpStatus.CONFLICT, "El registro ya existe o está siendo utilizado.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        return buildResponse(HttpStatus.CONFLICT, "El registro cambió durante la edición. Recarga su información.");
    }

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
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado en el servidor.");
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
