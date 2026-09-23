package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers idempotent replaced-original deletion and independent retry sweeps.
 */
class ReplacedOriginalObjectCleanupServiceTest {

    private MessageMediaRepository mediaRepository;
    private ObjectStorageProviderRegistry providerRegistry;
    private ObjectStorageProvider provider;
    private MediaProcessingIntegrationProperties processingProperties;
    private ReplacedOriginalObjectCleanupService cleanupService;

    /**
     * Creates the cleanup service with mocked storage and persistence.
     */
    @BeforeEach
    void setUp() {
        mediaRepository = mock(MessageMediaRepository.class);
        providerRegistry = mock(ObjectStorageProviderRegistry.class);
        provider = mock(ObjectStorageProvider.class);
        processingProperties = new MediaProcessingIntegrationProperties();
        processingProperties.setEnabled(true);
        when(providerRegistry.getProvider(ObjectStorageProviderType.MINIO)).thenReturn(provider);
        cleanupService = new ReplacedOriginalObjectCleanupService(
                mediaRepository, providerRegistry, processingProperties);
    }

    /**
     * Verifies a missing original is treated as success and still clears pending cleanup.
     */
    @Test
    void deleteOriginal_missingObject_clearsPendingCleanup() {
        when(provider.objectExists("input.mov")).thenReturn(false);

        cleanupService.deleteOriginal(20L, provider, "input.mov");

        verify(provider, never()).deleteObject("input.mov");
        verify(mediaRepository).clearReplacedOriginalObjectKey(20L, "input.mov");
    }

    /**
     * Verifies the sweeper retries persisted originals when media processing is enabled.
     */
    @Test
    void retryPendingCleanups_deletesPersistedOriginals() {
        MessageMedia media = new MessageMedia();
        media.setId(20L);
        media.setStorageProvider(ObjectStorageProviderType.MINIO);
        media.setObjectKey("input.transcoded.mp4");
        media.setTranscodedObjectKey("input.transcoded.mp4");
        media.setReplacedOriginalObjectKey("input.mov");
        when(mediaRepository.findByReplacedOriginalObjectKeyIsNotNull()).thenReturn(List.of(media));
        when(provider.objectExists("input.mov")).thenReturn(true);

        cleanupService.retryPendingCleanups();

        verify(provider).deleteObject("input.mov");
        verify(mediaRepository).clearReplacedOriginalObjectKey(20L, "input.mov");
    }

    /**
     * Verifies the sweeper does not run while media-processing integration is disabled.
     */
    @Test
    void retryPendingCleanups_disabled_skipsSweep() {
        processingProperties.setEnabled(false);

        cleanupService.retryPendingCleanups();

        verify(mediaRepository, never()).findByReplacedOriginalObjectKeyIsNotNull();
    }
}
