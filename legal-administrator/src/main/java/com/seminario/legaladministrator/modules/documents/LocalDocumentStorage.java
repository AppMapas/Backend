package com.seminario.legaladministrator.modules.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

@Component
public class LocalDocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";
    private final Path root;

    public LocalDocumentStorage(DocumentProperties properties) {
        root = Path.of(properties.getLocalDirectory()).toAbsolutePath().normalize();
    }

    public String provider() { return "local"; }

    private Path resolve(String key) throws IOException {
        // Las claves son UUID internos, nunca nombres enviados por el cliente.
        if (!key.matches("[0-9a-f-]{36}")) throw new IOException("Clave de documento inválida.");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path)) throw new IOException("Ruta inválida.");
        return path;
    }

    public void put(String key, InputStream content, String contentType) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("El directorio de documentos no puede ser un enlace simbólico.");
        prepareRoot();
        Path path = resolve(key);
        // Crear el archivo falla si la clave ya existe: nunca se sobrescribe un documento previo.
        try { Files.createFile(path, attributes(FILE_PERMISSIONS)); }
        catch (UnsupportedOperationException error) { createWithoutAttributes(path); }
        try (var output = Files.newOutputStream(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            content.transferTo(output);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(path); }
            catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
            throw error;
        }
    }

    private void prepareRoot() throws IOException {
        if (Files.isDirectory(root)) { restrict(root, DIRECTORY_PERMISSIONS); return; }
        try { Files.createDirectories(root, attributes(DIRECTORY_PERMISSIONS)); }
        catch (UnsupportedOperationException error) { Files.createDirectories(root); }
        restrict(root, DIRECTORY_PERMISSIONS);
    }

    private void createWithoutAttributes(Path path) throws IOException {
        log.warn("El sistema de archivos no admite permisos POSIX; se usan los permisos del proceso.");
        Files.createFile(path);
    }

    private FileAttribute<?>[] attributes(String permissions) {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) return new FileAttribute<?>[0];
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))};
    }

    /**
     * Restringe el acceso al propietario. Un directorio montado con otro propietario no
     * debe inutilizar la carga: se avisa y se continúa con los permisos existentes.
     */
    private void restrict(Path path, String permissions) {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)); }
        catch (IOException | UnsupportedOperationException error) {
            log.warn("No se pudieron aplicar permisos {} al almacenamiento de documentos: {}",
                    permissions, error.getMessage());
        }
    }

    public byte[] read(String key) throws IOException { return Files.readAllBytes(resolve(key)); }
    public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
