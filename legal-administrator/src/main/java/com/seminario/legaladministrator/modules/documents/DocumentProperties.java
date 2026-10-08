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
    private String s3Bucket = "";
    private String awsRegion = "";
    private DataSize maxFileSize = DataSize.ofMegabytes(20);
    private DataSize maxRequestSize = DataSize.ofMegabytes(21);

    @PostConstruct
    void validate() {
        if (!provider.equals("local") && !provider.equals("s3")) {
            throw new IllegalStateException("DOCUMENT_STORAGE_PROVIDER debe ser local o s3.");
        }
        if (provider.equals("s3") && (s3Bucket.isBlank() || awsRegion.isBlank())) {
            throw new IllegalStateException("Configura DOCUMENT_S3_BUCKET y AWS_REGION para Amazon S3.");
        }
        if (maxFileSize.toBytes() <= 0 || maxFileSize.toBytes() > DataSize.ofMegabytes(50).toBytes()) {
            throw new IllegalStateException("El límite de documentos debe estar entre 1 byte y 50 MiB.");
        }
        if (maxRequestSize.toBytes() <= maxFileSize.toBytes()) {
            throw new IllegalStateException("DOCUMENT_MAX_REQUEST_SIZE debe superar DOCUMENT_MAX_FILE_SIZE para admitir el envoltorio multipart.");
        }
    }
}
