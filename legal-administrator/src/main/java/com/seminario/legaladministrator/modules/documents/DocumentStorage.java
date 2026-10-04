package com.seminario.legaladministrator.modules.documents;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentStorage {
    String provider();
    // El llamador conserva la responsabilidad de cerrar el stream.
    void put(String key, InputStream content, String contentType) throws IOException;
    byte[] read(String key) throws IOException;
    void delete(String key) throws IOException;
}
