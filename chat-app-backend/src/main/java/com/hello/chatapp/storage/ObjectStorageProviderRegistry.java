package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry that indexes providers and resolves the active configured one.
 * This is the lookup/selection layer.
 */
@Component
public class ObjectStorageProviderRegistry {

    private final MediaStorageProperties mediaStorageProperties;
    private final Map<ObjectStorageProviderType, ObjectStorageProvider> providersByType;

    public ObjectStorageProviderRegistry(List<ObjectStorageProvider> providers,
            MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;

        EnumMap<ObjectStorageProviderType, ObjectStorageProvider> indexedProviders = new EnumMap<>(
                ObjectStorageProviderType.class);
        for (ObjectStorageProvider provider : providers) {
            ObjectStorageProvider previous = indexedProviders.put(provider.getType(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate storage provider registered for type " + provider.getType());
            }
        }

        this.providersByType = Collections.unmodifiableMap(indexedProviders);

        // Validate that the active provider is available. (We don't use its returned value.)
        // The constructor builds providersByType, then calls this method once so startup will immediately fail if
        // chat.media.provider points to a provider type that has no registered implementation.
        getActiveProvider();
    }

    public Set<ObjectStorageProviderType> getAvailableProviderTypes() {
        return providersByType.keySet();
    }

    public ObjectStorageProvider getActiveProvider() {
        return getProvider(mediaStorageProperties.getProvider());
    }

    public ObjectStorageProvider getProvider(ObjectStorageProviderType type) {
        ObjectStorageProvider provider = providersByType.get(type);
        if (provider == null) {
            throw new IllegalStateException("No object storage provider registered for type " + type);
        }
        return provider;
    }
}
