package com.seminario.legaladministrator.modules.documents;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3DocumentStorageTest {
    private final S3Client client = mock(S3Client.class);
    private final DocumentProperties properties = new DocumentProperties();

    private S3DocumentStorage storage() {
        properties.setS3Bucket("private-documents");
        properties.setAwsRegion("us-east-1");
        return new S3DocumentStorage(properties, client);
    }

    @Test
    void uploadsExactBytesWithPdfMetadataAndPreventsOverwrite() throws Exception {
        var storage = storage();
        byte[] bytes = "%PDF-1.7\n%%EOF".getBytes();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            assertThat(request.bucket()).isEqualTo("private-documents");
            assertThat(request.key()).isEqualTo("document-key");
            assertThat(request.contentType()).isEqualTo("application/pdf");
            assertThat(request.ifNoneMatch()).isEqualTo("*");
            try (var content = body.contentStreamProvider().newStream()) {
                assertThat(content.readAllBytes()).isEqualTo(bytes);
            }
            return PutObjectResponse.builder().build();
        });
        storage.put("document-key", new ByteArrayInputStream(bytes), "application/pdf");
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void readsBytesAndDeletesUsingThePrivateBucket() throws Exception {
        var storage = storage();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[]{1, 2, 3}));
        assertThat(storage.read("key")).containsExactly(1, 2, 3);
        storage.delete("key");
        verify(client).getObjectAsBytes(GetObjectRequest.builder().bucket("private-documents").key("key").build());
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("private-documents").key("key").build());
    }

    @Test
    void distinguishesMissingObjectsFromPermissionFailures() {
        var storage = storage();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> storage.read("key")).isInstanceOf(DocumentMissingException.class);
        var denied = S3Exception.builder().statusCode(403).build();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(denied);
        assertThatThrownBy(() -> storage.read("key")).isInstanceOf(IOException.class)
                .isNotInstanceOf(DocumentMissingException.class).hasCause(denied);
    }

    @Test
    void surfacesWriteAndDeleteFailures() {
        var storage = storage();
        var failure = S3Exception.builder().statusCode(503).build();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        assertThatThrownBy(() -> storage.put("key", new ByteArrayInputStream(new byte[]{1}), "application/pdf"))
                .isInstanceOf(IOException.class).hasCause(failure);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        assertThatThrownBy(() -> storage.delete("key")).isInstanceOf(IOException.class).hasCause(failure);
    }
}
