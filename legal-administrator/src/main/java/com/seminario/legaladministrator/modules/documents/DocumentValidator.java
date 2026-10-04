package com.seminario.legaladministrator.modules.documents;

import com.seminario.legaladministrator.shared.OperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentValidator {
    private final DocumentProperties properties;
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png");

    public record Validated(String name, String contentType, long sizeBytes) { }

    public Validated validate(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw invalid("Selecciona un archivo que no esté vacío.");
        long limit = properties.getMaxFileSize().toBytes();
        if (file.getSize() > limit) throw tooLarge();
        String name = file.getOriginalFilename();
        if (name == null) throw invalid("El documento debe tener un nombre.");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.length() > 180 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("El nombre debe tener entre 1 y 180 caracteres, sin caracteres de control.");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(extension);
        if (type == null) throw invalid("Solo se permiten documentos PDF, JPG y PNG.");
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank() && !declared.equals("application/octet-stream")
                && !declared.equalsIgnoreCase(type)) {
            throw invalid("El tipo de archivo no coincide con su extensión.");
        }
        // Memoria acotada: contar los bytes reales y conservar solo firma y cola.
        byte[] header = new byte[8];
        byte[] tail = new byte[1024];
        byte[] buffer = new byte[8192];
        long size = 0;
        int tailLength = 0;
        try (var input = file.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (size + count > limit) throw tooLarge();
                if (size < header.length) {
                    System.arraycopy(buffer, 0, header, (int) size, Math.min(count, header.length - (int) size));
                }
                if (count >= tail.length) {
                    System.arraycopy(buffer, count - tail.length, tail, 0, tail.length);
                    tailLength = tail.length;
                } else {
                    int retained = Math.min(tailLength, tail.length - count);
                    System.arraycopy(tail, tailLength - retained, tail, 0, retained);
                    System.arraycopy(buffer, 0, tail, retained, count);
                    tailLength = retained + count;
                }
                size += count;
            }
        }
        boolean valid = switch (type) {
            case "application/pdf" -> starts(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))
                    && new String(tail, 0, tailLength, StandardCharsets.ISO_8859_1).contains("%%EOF");
            case "image/jpeg" -> size > 4 && starts(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                    && tail[tailLength - 2] == (byte) 0xff && tail[tailLength - 1] == (byte) 0xd9;
            case "image/png" -> size >= 33 && starts(header,
                    new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10});
            default -> false;
        };
        if (!valid) throw invalid("El contenido no corresponde a un archivo PDF, JPG o PNG válido.");
        return new Validated(name, type, size);
    }

    private boolean starts(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    private OperationException invalid(String message) { return new OperationException(HttpStatus.BAD_REQUEST, message); }
    private OperationException tooLarge() {
        return new OperationException(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera el límite de tamaño permitido.");
    }
}
