package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class DocumentValidationAndStorageTest {
    @TempDir Path directory;
    private final DocumentProperties properties = new DocumentProperties();
    private final DocumentValidator validator = new DocumentValidator(properties);

    @Test
    void acceptsPdfAndRejectsRealCameraImages() throws Exception {
        var pdf = validator.validate(new MockMultipartFile("file", "C:\\fakepath\\DPI.PDF", "application/pdf", "%PDF-1.7\n%%EOF".getBytes()));
        assertThat(pdf.name()).isEqualTo("DPI.PDF");
        for (String format : new String[]{"jpeg", "png"}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
            assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "foto." + format,
                    "image/" + format, output.toByteArray()))).isInstanceOf(OperationException.class);
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
    void validatesSignatureAndCountedSizeForFilesLargerThanTheReadBuffer() throws Exception {
        // La validación es en streaming: firma al inicio y terminador al final del último bloque.
        byte[] content = ("%PDF-1.7\n" + "x".repeat(64 * 1024) + "\n%%EOF").getBytes();
        var validated = validator.validate(new MockMultipartFile("file", "escaneo.pdf",
                "application/pdf", content));
        assertThat(validated.sizeBytes()).isEqualTo(content.length);
        properties.setMaxFileSize(DataSize.ofBytes(content.length - 1));
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "escaneo.pdf",
                "application/pdf", content)))
                .isInstanceOfSatisfying(OperationException.class, error -> assertThat(error.getStatus().value()).isEqualTo(413));
    }

    @Test
    void rejectsTruncatedImageEndingOutsideTheRetainedTail() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        byte[] truncated = java.util.Arrays.copyOf(output.toByteArray(), output.size() - 3);
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "foto.jpg", "image/jpeg", truncated)))
                .isInstanceOf(OperationException.class);
    }

    @Test
    void localStoragePersistsAcrossInstancesAndRejectsTraversalAndOverwrite() throws Exception {
        properties.setLocalDirectory(directory.resolve("nested/documents").toString());
        var storage = new LocalDocumentStorage(properties);
        String key = UUID.randomUUID().toString();
        storage.put(key, new ByteArrayInputStream(new byte[]{1, 2, 3}), "application/pdf");
        assertThat(new LocalDocumentStorage(properties).read(key)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> storage.put(key, new ByteArrayInputStream(new byte[]{4}), "application/pdf"))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> storage.read("../secret")).isInstanceOf(java.io.IOException.class);
        storage.delete(key);
        assertThat(Files.exists(directory.resolve("nested/documents").resolve(key))).isFalse();
    }

    @Test
    void localStorageSignalsMissingObjectsSeparatelyFromOtherFailures() throws Exception {
        properties.setLocalDirectory(directory.toString());
        var storage = new LocalDocumentStorage(properties);
        String key = UUID.randomUUID().toString();
        // Antes de escribir, el objeto no existe: es un estado definitivo, no un fallo de disco.
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(DocumentMissingException.class);
        storage.put(key, new ByteArrayInputStream(new byte[]{1}), "application/pdf");
        assertThat(storage.read(key)).containsExactly(1);
        storage.delete(key);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(DocumentMissingException.class);
        // Una clave mal formada sigue siendo un error de configuración, no "documento ausente".
        assertThatThrownBy(() -> storage.read("no-es-uuid"))
                .isNotInstanceOf(DocumentMissingException.class).isInstanceOf(IOException.class);
    }

    @Test
    void localStorageRemovesPartialFileAndKeepsPermissionsPrivate() throws Exception {
        properties.setLocalDirectory(directory.toString());
        var storage = new LocalDocumentStorage(properties);
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> storage.put(key, failingStream(), "application/pdf")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(directory.resolve(key))).isFalse();
        storage.put(key, new ByteArrayInputStream(new byte[]{7}), "application/pdf");
        Path path = directory.resolve(key);
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(path))
                    .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(directory))
                    .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(Files.isSymbolicLink(directory)).isFalse();
    }

    private InputStream failingStream() {
        return new InputStream() {
            private int delivered;
            @Override public int read() { return ++delivered <= 4 ? delivered : -1; }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (delivered >= 4) throw new IOException("disco lleno");
                int count = Math.min(4 - delivered, length);
                for (int i = 0; i < count; i++) buffer[offset + i] = (byte) ++delivered;
                return count;
            }
        };
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

    @Test
    void uploadLimitDefaultsToTwentyMebibytesAndRequestLimitMustExceedIt() {
        assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(21));
        properties.validate();
        properties.setMaxRequestSize(DataSize.ofMegabytes(20));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setMaxFileSize(DataSize.ofMegabytes(51));
        properties.setMaxRequestSize(DataSize.ofMegabytes(52));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
