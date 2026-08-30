package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStorageProviderRegistryTest {

    @Test
    void getActiveProvider_returnsConfiguredMinioProvider() {
        MediaStorageProperties mediaStorageProperties = new MediaStorageProperties();
        mediaStorageProperties.setProvider(ObjectStorageProviderType.MINIO);

        ObjectStorageProviderRegistry registry = new ObjectStorageProviderRegistry(
                List.of(
                        new MinioObjectStorageProvider(mediaStorageProperties),
                        new S3ObjectStorageProvider(mediaStorageProperties)),
                mediaStorageProperties);

        ObjectStorageProvider provider = registry.getActiveProvider();

        assertThat(provider.getType()).isEqualTo(ObjectStorageProviderType.MINIO);
        assertThat(provider.describe().bucket()).isEqualTo("chat-media");
    }

    @Test
    void constructor_throwsWhenConfiguredProviderIsMissing() {
        MediaStorageProperties mediaStorageProperties = new MediaStorageProperties();
        mediaStorageProperties.setProvider(ObjectStorageProviderType.S3);

        assertThatThrownBy(() -> new ObjectStorageProviderRegistry(
                List.of(new MinioObjectStorageProvider(mediaStorageProperties)),
                mediaStorageProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No object storage provider registered for type S3");
    }
}
