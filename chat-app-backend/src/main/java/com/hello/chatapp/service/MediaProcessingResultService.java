package com.hello.chatapp.service;

import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.dto.MediaProcessingResultRequest;
import com.hello.chatapp.dto.MediaProcessingVideoRenditionRequest;
import com.hello.chatapp.dto.MediaProcessingVideoMetadataRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.util.AfterCommit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies media-worker callbacks to chat-owned rows and republishes the updated message after commit.
 */
@Service
public class MediaProcessingResultService {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingResultService.class);

    private final MessageMediaRepository messageMediaRepository;
    private final MessageRepository messageRepository;
    private final ObjectStorageProviderRegistry storageProviderRegistry;
    private final MessageResponseMapper messageResponseMapper;
    private final RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    public MediaProcessingResultService(
            MessageMediaRepository messageMediaRepository,
            MessageRepository messageRepository,
            ObjectStorageProviderRegistry storageProviderRegistry,
            MessageResponseMapper messageResponseMapper,
            RealtimeMessageDeliveryService realtimeMessageDeliveryService) {
        this.messageMediaRepository = messageMediaRepository;
        this.messageRepository = messageRepository;
        this.storageProviderRegistry = storageProviderRegistry;
        this.messageResponseMapper = messageResponseMapper;
        this.realtimeMessageDeliveryService = realtimeMessageDeliveryService;
    }

    /**
     * Locks and updates one attachment. Canonical-pointer publication and original deletion occur only after commit.
     *
     * @param request normalized result returned by media-processing-service
     */
    @Transactional
    public void apply(MediaProcessingResultRequest request) {
        MessageMedia media = messageMediaRepository.findByIdAndMessageId(request.mediaId(), request.messageId())
                .orElseThrow(() -> new NotFoundException(
                        "Media " + request.mediaId() + " for message " + request.messageId() + " not found"));
        validateJobId(media, request);
        if (media.getStatus() == MediaStatus.MEDIA_READY) {
            logger.info("Ignoring duplicate callback for completed jobId={}", request.jobId());
            return;
        }
        validateSourceObject(media, request);

        applyMetadata(media, request.videoMetadata());
        applyPoster(media, request);
        applyVideoRenditions(media, request);
        switch (request.status()) {
            case PROCESSING_IN_PROGRESS -> media.setStatus(MediaStatus.PROCESSING_IN_PROGRESS);
            case PROCESSING_FAILED -> media.setStatus(MediaStatus.PROCESSING_FAILED);
            case MEDIA_READY -> applyReadyResult(media, request);
        }
        messageMediaRepository.save(media);

        Long messageId = request.messageId();
        AfterCommit.run(
                () -> publishUpdatedMessage(messageId),
                "Failed to republish processed media messageId=" + messageId);
    }

    /**
     * Verifies the callback still refers to the source object for this attachment or an idempotently applied result.
     */
    private void validateJobId(MessageMedia media, MediaProcessingResultRequest request) {
        String expectedJobId = "media-" + media.getId();
        if (!expectedJobId.equals(request.jobId())) {
            throw new BadRequestException("Unexpected media-processing job id");
        }
    }

    /**
     * Rejects callbacks for an object that is no longer the attachment's current source.
     */
    private void validateSourceObject(MessageMedia media, MediaProcessingResultRequest request) {
        if (!request.originalObjectKey().equals(media.getObjectKey())) {
            throw new BadRequestException("Media-processing callback refers to a stale source object");
        }
    }

    /**
     * Copies available technical metadata into the chat-owned media row.
     */
    private void applyMetadata(MessageMedia media, MediaProcessingVideoMetadataRequest metadata) {
        if (metadata == null) {
            return;
        }
        media.setWidth(metadata.width());
        media.setHeight(metadata.height());
        media.setDurationMs(metadata.durationMillis());
        media.setDetectedMimeType(metadata.detectedMimeType());
    }

    /**
     * Persists an available poster object after verifying the derived asset exists.
     */
    private void applyPoster(MessageMedia media, MediaProcessingResultRequest request) {
        String thumbnailObjectKey = request.thumbnailObjectKey();
        if (thumbnailObjectKey == null || thumbnailObjectKey.isBlank()) {
            return;
        }
        ObjectStorageProvider provider = storageProviderRegistry.getProvider(media.getStorageProvider());
        if (!provider.objectExists(thumbnailObjectKey)) {
            throw new BadRequestException("Poster media object does not exist");
        }
        media.setThumbnailObjectKey(thumbnailObjectKey);
    }

    /**
     * Verifies and persists the first supported secondary 480p MP4 rendition.
     */
    private void applyVideoRenditions(MessageMedia media, MediaProcessingResultRequest request) {
        if (request.videoRenditions().isEmpty()) {
            return;
        }
        if (request.videoRenditions().size() > 1) {
            throw new BadRequestException("Only one secondary video rendition is supported");
        }

        MediaProcessingVideoRenditionRequest rendition = request.videoRenditions().getFirst();
        if (rendition.height() != 480 || !"video/mp4".equalsIgnoreCase(rendition.mimeType())) {
            throw new BadRequestException("Unsupported secondary video rendition");
        }
        ObjectStorageProvider provider = storageProviderRegistry.getProvider(media.getStorageProvider());
        if (!provider.objectExists(rendition.objectKey())) {
            throw new BadRequestException("Secondary video rendition object does not exist");
        }
        media.setRendition480pObjectKey(rendition.objectKey());
        media.setRendition480pSizeBytes(rendition.sizeBytes());
    }

    /**
     * Verifies and switches to the canonical playback object, then schedules deletion of a replaced original.
     */
    private void applyReadyResult(MessageMedia media, MediaProcessingResultRequest request) {
        String canonicalKey = request.transcodedObjectKey();
        if (canonicalKey == null || canonicalKey.isBlank()) {
            throw new BadRequestException("MEDIA_READY callback requires transcodedObjectKey");
        }
        if (request.canonicalObjectSize() == null || request.canonicalObjectSize() <= 0) {
            throw new BadRequestException("MEDIA_READY callback requires canonicalObjectSize");
        }

        ObjectStorageProvider provider = storageProviderRegistry.getProvider(media.getStorageProvider());
        if (!provider.objectExists(canonicalKey)) {
            throw new BadRequestException("Canonical media object does not exist");
        }

        String currentObjectKey = media.getObjectKey();
        boolean replaceOriginal = !request.reusedOriginalObject() && request.originalObjectKey().equals(currentObjectKey);
        media.setObjectKey(canonicalKey);
        media.setTranscodedObjectKey(canonicalKey);
        media.setDetectedMimeType("video/mp4");
        media.setSizeBytes(request.canonicalObjectSize());
        media.setStatus(MediaStatus.MEDIA_READY);

        if (replaceOriginal && !request.originalObjectKey().equals(canonicalKey)) {
            String originalObjectKey = request.originalObjectKey();
            AfterCommit.run(
                    () -> deleteOriginal(provider, originalObjectKey),
                    "Failed to delete replaced original media object " + originalObjectKey);
        }
    }

    /**
     * Deletes a replaced original after the canonical-pointer transaction commits.
     */
    private void deleteOriginal(ObjectStorageProvider provider, String originalObjectKey) {
        provider.deleteObject(originalObjectKey);
        logger.info("Deleted replaced original media object {}", originalObjectKey);
    }

    /**
     * Maps and republishes the latest committed message state.
     */
    private void publishUpdatedMessage(Long messageId) {
        Message message = messageRepository.findWithMediaById(messageId)
                .orElseThrow(() -> new NotFoundException("Message with id " + messageId + " not found"));
        MessageResponse response = Objects.requireNonNull(messageResponseMapper.toResponse(message));
        if (message.getGroup() == null) {
            realtimeMessageDeliveryService.publishToPublic(response);
        } else {
            realtimeMessageDeliveryService.publishToGroup(
                    Objects.requireNonNull(message.getGroup().getId()), response);
        }
    }
}
