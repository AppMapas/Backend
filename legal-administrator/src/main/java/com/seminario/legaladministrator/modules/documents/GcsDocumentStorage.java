package com.seminario.legaladministrator.modules.documents;

import com.google.cloud.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class GcsDocumentStorage implements DocumentStorage {
    private final DocumentProperties properties;
    private volatile Storage client;

    public String provider() { return "gcs"; }

    private synchronized Storage client() throws IOException {
        if (properties.getGcsBucket().isBlank()) throw new IOException("Bucket de documentos no configurado.");
        // ADC: cuenta de servicio del despliegue. No se requieren credenciales en modo local.
        if (client == null) client = StorageOptions.getDefaultInstance().getService();
        return client;
    }

    public void put(String key, InputStream content, String contentType) throws IOException {
        client().createFrom(BlobInfo.newBuilder(properties.getGcsBucket(), key)
                .setContentType(contentType).setCacheControl("private, no-store").build(),
                content, 256 * 1024, Storage.BlobWriteOption.doesNotExist());
    }

    public byte[] read(String key) throws IOException {
        return client().readAllBytes(properties.getGcsBucket(), key);
    }

    public void delete(String key) throws IOException {
        client().delete(properties.getGcsBucket(), key);
    }
}
