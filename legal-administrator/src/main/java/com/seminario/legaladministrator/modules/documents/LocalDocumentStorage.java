package com.seminario.legaladministrator.modules.documents;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;

@Component
public class LocalDocumentStorage implements DocumentStorage {
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

    public void put(String key, byte[] content, String contentType) throws IOException {
        Files.createDirectories(root);
        Path path = resolve(key);
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            try {
                output.write(content);
            } catch (IOException error) {
                Files.deleteIfExists(path);
                throw error;
            }
        }
    }

    public byte[] read(String key) throws IOException { return Files.readAllBytes(resolve(key)); }
    public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
