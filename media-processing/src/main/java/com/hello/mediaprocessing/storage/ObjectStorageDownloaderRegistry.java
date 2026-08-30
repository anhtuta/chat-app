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
 * Indexes object-storage downloaders and resolves the one configured for this worker instance.
 */
@Singleton
public class ObjectStorageDownloaderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageDownloaderRegistry.class);

    private final MediaProcessingStorageProperties storageProperties;
    private final Map<ObjectStorageProviderType, ObjectStorageDownloader> downloadersByType;

    public ObjectStorageDownloaderRegistry(
            List<ObjectStorageDownloader> downloaders,
            MediaProcessingStorageProperties storageProperties) {
        this.storageProperties = storageProperties;

        EnumMap<ObjectStorageProviderType, ObjectStorageDownloader> indexedDownloaders =
                new EnumMap<>(ObjectStorageProviderType.class);
        for (ObjectStorageDownloader downloader : downloaders) {
            ObjectStorageDownloader previous = indexedDownloaders.put(downloader.getType(), downloader);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate object storage downloader registered for type " + downloader.getType());
            }
        }

        this.downloadersByType = Collections.unmodifiableMap(indexedDownloaders);
        getConfiguredDownloader();
        logger.info("Configured storage provider: {}", storageProperties.getProvider());
    }

    /**
     * Returns the provider type configured for this worker instance.
     *
     * @return configured storage provider
     */
    public ObjectStorageProviderType getConfiguredProviderType() {
        return storageProperties.getProvider();
    }

    /**
     * Returns the downloader implementations currently registered with the worker.
     *
     * @return immutable set of provider types with a downloader bean
     */
    public Set<ObjectStorageProviderType> getAvailableProviderTypes() {
        return downloadersByType.keySet();
    }

    /**
     * Resolves the downloader for the configured provider type.
     *
     * @return downloader bound to {@link MediaProcessingStorageProperties#getProvider()}
     */
    public ObjectStorageDownloader getConfiguredDownloader() {
        return getDownloader(storageProperties.getProvider());
    }

    /**
     * Resolves the downloader for a specific provider type.
     *
     * @param providerType storage provider that should handle the download
     * @return downloader implementation for the requested provider
     */
    public ObjectStorageDownloader getDownloader(ObjectStorageProviderType providerType) {
        ObjectStorageDownloader downloader = downloadersByType.get(providerType);
        if (downloader == null) {
            throw new IllegalStateException("No object storage downloader registered for type " + providerType);
        }
        return downloader;
    }
}
