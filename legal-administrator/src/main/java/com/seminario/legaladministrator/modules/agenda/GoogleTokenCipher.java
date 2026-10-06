package com.seminario.legaladministrator.modules.agenda;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Cifra los tokens con AES-GCM y vincula cada credencial a su titular, identidad de Google y versión de clave. */
@Component
@RequiredArgsConstructor
public class GoogleTokenCipher {

    private final GoogleCalendarProperties properties;
    private final SecureRandom random = new SecureRandom();

    /** Utiliza un nonce nuevo por cifrado y autentica la identidad de la titular como datos asociados. */
    public String encrypt(String token, String owner, String subject) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, owner, subject);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible proteger la credencial de calendario.");
        }
    }

    /** Comprueba la versión de clave y autenticidad del token antes de devolverlo para una llamada a Google. */
    public String decrypt(String value, String owner, String subject, String keyVersion) {
        if (!properties.getKeyVersion().equals(keyVersion)) {
            throw new IllegalStateException("La credencial requiere renovación de clave.");
        }
        try {
            byte[] data = Base64.getDecoder().decode(value);
            if (data.length < 29) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = java.util.Arrays.copyOfRange(data, 0, 12);
            return new String(
                cipher(Cipher.DECRYPT_MODE, nonce, owner, subject).doFinal(data, 12, data.length - 12),
                StandardCharsets.UTF_8
            );
        } catch (Exception exception) {
            throw new IllegalStateException("La credencial de calendario requiere reconexión.");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String owner, String subject) throws Exception {
        byte[] key = Base64.getDecoder().decode(properties.getEncryptionKey());
        if (key.length != 32) {
            throw new IllegalArgumentException();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(
            (properties.getKeyVersion() + ":" + owner + ":" + subject).getBytes(StandardCharsets.UTF_8)
        );
        return cipher;
    }
}
