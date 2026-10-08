package com.seminario.legaladministrator.modules.documents;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.exception.SdkException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Component
public class S3DocumentStorage implements DocumentStorage {
    private final DocumentProperties properties;
    private volatile S3Client client;

    @Autowired
    public S3DocumentStorage(DocumentProperties properties) {
        this.properties = properties;
    }

    S3DocumentStorage(DocumentProperties properties, S3Client client) {
        this.properties = properties;
        this.client = client;
    }

    public String provider() { return "s3"; }

    private synchronized S3Client client() {
        if (client == null) {
            // Cadena predeterminada AWS: usa el task role de ECS en producción.
            client = S3Client.builder().region(Region.of(properties.getAwsRegion())).build();
        }
        return client;
    }

    public void put(String key, InputStream content, String contentType) throws IOException {
        // Archivo temporal para enviar una longitud conocida sin cargar el PDF en memoria.
        var temporary = Files.createTempFile("legal-document-", ".pdf");
        try {
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            client().putObject(PutObjectRequest.builder().bucket(properties.getS3Bucket()).key(key)
                    .contentType(contentType).cacheControl("private, no-store").ifNoneMatch("*").build(),
                    RequestBody.fromFile(temporary));
        } catch (SdkException error) {
            throw new IOException("No se pudo guardar el documento en S3.", error);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] read(String key) throws IOException {
        try {
            return client().getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.getS3Bucket()).key(key).build()).asByteArray();
        } catch (S3Exception error) {
            if (error.statusCode() == 404) throw new DocumentMissingException("El objeto no existe en S3.", error);
            throw new IOException("No se pudo leer el documento de S3.", error);
        } catch (SdkException error) {
            throw new IOException("No se pudo leer el documento de S3.", error);
        }
    }

    public void delete(String key) throws IOException {
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(properties.getS3Bucket()).key(key).build());
        } catch (SdkException error) {
            throw new IOException("No se pudo eliminar el documento de S3.", error);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) client.close();
    }
}
