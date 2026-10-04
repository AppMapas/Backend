package com.seminario.legaladministrator.modules.documents;

import java.io.IOException;

/**
 * El objeto está registrado en la base de datos pero ya no existe en el almacenamiento.
 * Es un estado definitivo: reintentar no lo resuelve, a diferencia de un fallo de disco,
 * de permisos o de red, que se traducen a 503.
 */
public class DocumentMissingException extends IOException {
    public DocumentMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
