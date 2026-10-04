package com.seminario.legaladministrator.modules.documents;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba el archivo real de configuración: los límites de multipart deben seguir a
 * {@code app.documents.*} y no quedar con un valor fijo desactualizado.
 */
class DocumentConfigurationTest {

    @Test
    void multipartLimitsFollowTheConfiguredDocumentLimits() throws IOException {
        var environment = new MockEnvironment();
        // Sin variables de entorno: se comprueban los valores predeterminados del archivo.
        environment.getPropertySources().addLast(new PropertiesPropertySource("application.properties",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        var binder = Binder.get(environment);

        var documents = binder.bind("app.documents", DocumentProperties.class).get();
        var multipart = binder.bind("spring.servlet.multipart", MultipartProperties.class).get();

        assertThat(documents.getProvider()).isEqualTo("local");
        assertThat(documents.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(documents.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(21));
        assertThat(multipart.getMaxFileSize()).isEqualTo(documents.getMaxFileSize());
        assertThat(multipart.getMaxRequestSize()).isEqualTo(documents.getMaxRequestSize());
        assertThat(multipart.getMaxRequestSize().toBytes()).isGreaterThan(multipart.getMaxFileSize().toBytes());
        // Disco desde el primer byte: evita retener el archivo completo en memoria.
        assertThat(multipart.getFileSizeThreshold()).isEqualTo(DataSize.ofBytes(0));
    }

    @Test
    void defaultConfigurationIsValid() {
        new DocumentProperties().validate();
    }
}
