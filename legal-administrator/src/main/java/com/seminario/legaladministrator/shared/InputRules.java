package com.seminario.legaladministrator.shared;

import org.springframework.http.HttpStatus;
import java.text.Normalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class InputRules {
    private InputRules() { }
    public static String text(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "El texto contiene caracteres de control.");
        }
        return normalized;
    }
    public static String required(String value, int max, String label) {
        String result = text(value);
        if (result == null || result.length() > max) {
            throw new OperationException(HttpStatus.BAD_REQUEST, label + " es obligatorio y admite hasta " + max + " caracteres.");
        }
        return result;
    }
    public static String dpi(String value) {
        if (value == null || !value.matches("[0-9]{13}")) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "El DPI debe contener exactamente 13 dígitos.");
        }
        return value;
    }
    public static PageRequest page(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La página debe ser no negativa y el tamaño estar entre 1 y 100.");
        }
        return PageRequest.of(page, size, sort);
    }
    public static String search(String query) {
        String result = text(query);
        if (result == null) {
            return "";
        }
        if (result.length() > 100) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La búsqueda admite hasta 100 caracteres.");
        }
        return result.toLowerCase(java.util.Locale.ROOT);
    }
}
