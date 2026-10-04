package com.seminario.legaladministrator.modules.documents;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@ConfigurationProperties(prefix = "app.documents")
@Getter @Setter
public class DocumentProperties {
    private String provider = "local";
    private String localDirectory = "./data/documents";
    private String gcsBucket = "";
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    @PostConstruct
    void validate() {
        if (!provider.equals("local") && !provider.equals("gcs")) {
            throw new IllegalStateException("DOCUMENT_STORAGE_PROVIDER debe ser local o gcs.");
        }
        if (provider.equals("gcs") && gcsBucket.isBlank()) {
            throw new IllegalStateException("Configura DOCUMENT_GCS_BUCKET para Google Cloud Storage.");
        }
        if (maxFileSize.toBytes() <= 0 || maxFileSize.toBytes() > DataSize.ofMegabytes(50).toBytes()) {
            throw new IllegalStateException("El límite de documentos debe estar entre 1 byte y 50 MiB.");
        }
    }
}
