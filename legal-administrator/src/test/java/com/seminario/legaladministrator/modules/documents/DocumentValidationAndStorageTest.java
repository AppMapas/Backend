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
    void validatesSignatureAndCountedSizeForFilesLargerThanTheReadBuffer() throws Exception {
        // La validación es en streaming: firma al inicio y terminador al final del último bloque.
        var image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 400; x++) {
            for (int y = 0; y < 400; y++) image.setRGB(x, y, (x * 7919) ^ (y * 104729));
        }
        for (String format : new String[]{"jpeg", "png"}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, format, output);
            byte[] content = output.toByteArray();
            assertThat(content.length).isGreaterThan(32 * 1024);
            var validated = validator.validate(new MockMultipartFile("file", "escaneo." + format,
                    "image/" + format, content));
            assertThat(validated.sizeBytes()).isEqualTo(content.length);
        }
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
