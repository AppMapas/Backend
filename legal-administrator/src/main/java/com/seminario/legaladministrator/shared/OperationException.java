package com.seminario.legaladministrator.shared;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class OperationException extends RuntimeException {
    private final HttpStatus status;
    public OperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
