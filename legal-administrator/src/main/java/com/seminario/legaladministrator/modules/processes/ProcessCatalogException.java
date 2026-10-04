package com.seminario.legaladministrator.modules.processes;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ProcessCatalogException extends RuntimeException {
    private final HttpStatus status;

    public ProcessCatalogException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
