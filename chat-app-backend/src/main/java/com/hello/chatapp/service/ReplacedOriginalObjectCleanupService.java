package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Deletes replaced original objects after the canonical pointer commits, with durable retries.
 */
@Service
public class ReplacedOriginalObjectCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(ReplacedOriginalObjectCleanupService.class);

    private final MessageMediaRepository messageMediaRepository;
    private final ObjectStorageProviderRegistry storageProviderRegistry;
    private final MediaProcessingIntegrationProperties processingProperties;

    public ReplacedOriginalObjectCleanupService(
            MessageMediaRepository messageMediaRepository,
            ObjectStorageProviderRegistry storageProviderRegistry,
            MediaProcessingIntegrationProperties processingProperties) {
        this.messageMediaRepository = messageMediaRepository;
        this.storageProviderRegistry = storageProviderRegistry;
        this.processingProperties = processingProperties;
    }

    /**
     * Idempotently deletes a replaced original and clears the persisted pending key on success.
     * Failures leave the pending key so a later callback or sweep can retry.
     *
     * @param mediaId attachment identifier
     * @param provider storage provider that holds the replaced object
     * @param originalObjectKey replaced original object key
     */
    public void deleteOriginal(Long mediaId, ObjectStorageProvider provider, String originalObjectKey) {
        try {
            if (provider.objectExists(originalObjectKey)) {
                provider.deleteObject(originalObjectKey);
            }
            messageMediaRepository.clearReplacedOriginalObjectKey(mediaId, originalObjectKey);
            logger.debug("Deleted replaced original media object {}", originalObjectKey);
        } catch (RuntimeException e) {
            logger.error(
                    "Failed to delete replaced original media object {}; pending cleanup remains for retry",
                    originalObjectKey,
                    e);
        }
    }

    /**
     * Retries leftover replaced-original deletions independently of worker callbacks.
     */
    @Scheduled(fixedDelayString = "${chat.media.processing.replaced-original-cleanup-interval-ms:60000}")
    public void retryPendingCleanups() {
        logger.debug("Retrying pending replaced-original deletions");
        if (!processingProperties.isEnabled()) {
            return;
        }
        List<MessageMedia> pending = messageMediaRepository.findByReplacedOriginalObjectKeyIsNotNull();
        for (MessageMedia media : pending) {
            String originalObjectKey = media.getReplacedOriginalObjectKey();
            if (originalObjectKey == null || originalObjectKey.isBlank()) {
                continue;
            }
            if (originalObjectKey.equals(media.getObjectKey())
                    || originalObjectKey.equals(media.getTranscodedObjectKey())) {
                logger.warn(
                        "Skipping unsafe replaced-original cleanup for mediaId={} objectKey={}",
                        media.getId(),
                        originalObjectKey);
                messageMediaRepository.clearReplacedOriginalObjectKey(media.getId(), originalObjectKey);
                continue;
            }
            ObjectStorageProvider provider = storageProviderRegistry.getProvider(media.getStorageProvider());
            deleteOriginal(media.getId(), provider, originalObjectKey);
        }
    }
}
