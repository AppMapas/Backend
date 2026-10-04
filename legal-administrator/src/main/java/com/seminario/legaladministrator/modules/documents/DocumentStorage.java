package com.seminario.legaladministrator.modules.documents;

import java.io.IOException;

public interface DocumentStorage {
    String provider();
    void put(String key, byte[] content, String contentType) throws IOException;
    byte[] read(String key) throws IOException;
    void delete(String key) throws IOException;
}
