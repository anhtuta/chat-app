package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes object-storage uploaders and resolves the one configured for this worker instance.
 */
@Singleton
public class ObjectStorageUploaderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageUploaderRegistry.class);

    private final MediaProcessingStorageProperties storageProperties;
    private final Map<ObjectStorageProviderType, ObjectStorageUploader> uploadersByType;

    public ObjectStorageUploaderRegistry(
            List<ObjectStorageUploader> uploaders, MediaProcessingStorageProperties storageProperties) {
        this.storageProperties = storageProperties;

        EnumMap<ObjectStorageProviderType, ObjectStorageUploader> indexedUploaders =
                new EnumMap<>(ObjectStorageProviderType.class);
        for (ObjectStorageUploader uploader : uploaders) {
            ObjectStorageUploader previous = indexedUploaders.put(uploader.getType(), uploader);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate object storage uploader registered for type " + uploader.getType());
            }
        }

        this.uploadersByType = Collections.unmodifiableMap(indexedUploaders);
        getConfiguredUploader();
        logger.info("Configured storage upload provider: {}", storageProperties.getProvider());
    }

    /**
     * Returns the uploader implementations currently registered with the worker.
     *
     * @return immutable set of provider types with an uploader bean
     */
    public Set<ObjectStorageProviderType> getAvailableProviderTypes() {
        return uploadersByType.keySet();
    }

    /**
     * Resolves the uploader for the configured provider type.
     *
     * @return uploader bound to {@link MediaProcessingStorageProperties#getProvider()}
     */
    public ObjectStorageUploader getConfiguredUploader() {
        return getUploader(storageProperties.getProvider());
    }

    /**
     * Resolves the uploader for a specific provider type.
     *
     * @param providerType storage provider that should handle the upload
     * @return uploader implementation for the requested provider
     */
    public ObjectStorageUploader getUploader(ObjectStorageProviderType providerType) {
        ObjectStorageUploader uploader = uploadersByType.get(providerType);
        if (uploader == null) {
            throw new IllegalStateException("No object storage uploader registered for type " + providerType);
        }
        return uploader;
    }
}
