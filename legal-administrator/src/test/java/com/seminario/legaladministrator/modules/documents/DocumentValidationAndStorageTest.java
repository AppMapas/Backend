package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.UUID;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class DocumentValidationAndStorageTest {
    @TempDir Path directory;
    private final DocumentProperties properties = new DocumentProperties();
    private final DocumentValidator validator = new DocumentValidator(properties);

    @Test
    void acceptsPdfAndRealCameraImages() throws Exception {
        var pdf = validator.validate(new MockMultipartFile("file", "C:\\fakepath\\DPI.PDF", "application/pdf", "%PDF-1.7\n%%EOF".getBytes()));
        assertThat(pdf.name()).isEqualTo("DPI.PDF");
        for (String format : new String[]{"jpeg", "png"}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
            assertThat(validator.validate(new MockMultipartFile("file", "foto." + format,
                    "image/" + format, output.toByteArray())).contentType()).isEqualTo("image/" + format);
        }
    }

    @Test
    void rejectsDisguisedEmptyTruncatedAndOversizedFiles() {
        for (var file : new MockMultipartFile[]{
                new MockMultipartFile("file", "malware.pdf", "application/pdf", "<html>malware</html>".getBytes()),
                new MockMultipartFile("file", "dpi.pdf", "text/html", "%PDF-1.7\n%%EOF".getBytes()),
                new MockMultipartFile("file", "foto.heic", "image/heic", new byte[]{1}),
                new MockMultipartFile("file", "dpi.pdf", "application/pdf", new byte[0]),
                new MockMultipartFile("file", "dpi.pdf", "application/pdf", "%PDF-1.7".getBytes())}) {
            assertThatThrownBy(() -> validator.validate(file)).isInstanceOf(OperationException.class);
        }
        properties.setMaxFileSize(DataSize.ofBytes(10));
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "dpi.pdf", "application/pdf", new byte[11])))
                .isInstanceOfSatisfying(OperationException.class, error -> assertThat(error.getStatus().value()).isEqualTo(413));
    }

    @Test
    void localStoragePersistsAcrossInstancesAndRejectsTraversalAndOverwrite() throws Exception {
        properties.setLocalDirectory(directory.toString());
        var storage = new LocalDocumentStorage(properties);
        String key = UUID.randomUUID().toString();
        storage.put(key, new byte[]{1, 2, 3}, "application/pdf");
        assertThat(new LocalDocumentStorage(properties).read(key)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> storage.put(key, new byte[]{4}, "application/pdf")).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> storage.read("../secret")).isInstanceOf(java.io.IOException.class);
        storage.delete(key);
        assertThat(Files.exists(directory.resolve(key))).isFalse();
    }

    @Test
    void localModeNeedsNoCloudCredentialsAndInvalidProvidersFailFast() {
        properties.validate();
        properties.setProvider("gcs");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setGcsBucket("private-documents");
        properties.validate();
        properties.setProvider("typo");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
