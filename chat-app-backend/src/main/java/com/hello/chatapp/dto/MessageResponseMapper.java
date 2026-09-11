package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps persisted {@link Message} rows to API payloads, including structured system-event metadata.
 */
@Component
public class MessageResponseMapper {

    private final ObjectStorageProviderRegistry objectStorageProviderRegistry;

    public MessageResponseMapper(ObjectStorageProviderRegistry objectStorageProviderRegistry) {
        this.objectStorageProviderRegistry = objectStorageProviderRegistry;
    }

    /**
     * Maps a persisted message, including optional {@code systemEventPayload} for batch adds.
     */
    public MessageResponse toResponse(Message message) {
        if (message == null) {
            return null;
        }

        return MessageResponse.builder()
                .id(message.getId())
                .user(message.getUser() != null ? UserResponse.fromUser(message.getUser()) : null)
                .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                .messageType(message.getMessageType())
                .content(resolveContent(message))
                .systemEventType(resolveSystemEventType(message))
                .systemEventActor(message.getUpdatedBy() != null ? UserResponse.fromUser(message.getUpdatedBy()) : null)
                .systemEventPayload(message.getSystemEventPayload())
                .updatedBy(message.getUpdatedBy() != null ? UserResponse.fromUser(message.getUpdatedBy()) : null)
                .updatedAt(message.getUpdatedAt())
                .deletedBy(message.getDeletedBy() != null ? UserResponse.fromUser(message.getDeletedBy()) : null)
                .deletedAt(message.getDeletedAt())
                .attachments(resolveAttachments(message))
                .timestamp(message.getTimestamp())
                .build();
    }

    private SystemEventType resolveSystemEventType(Message message) {
        if (message == null || message.getMessageType() != MessageType.SYSTEM || message.getContent() == null) {
            return null;
        }
        try {
            return SystemEventType.valueOf(message.getContent());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String resolveContent(Message message) {
        if (message == null || message.getDeletedAt() != null) {
            return null;
        }
        return message.getContent();
    }

    private List<MessageAttachmentResponse> resolveAttachments(Message message) {
        if (message == null || message.getDeletedAt() != null) {
            return Collections.emptyList();
        }
        return toAttachmentResponses(message.getAttachments());
    }

    private List<MessageAttachmentResponse> toAttachmentResponses(List<MessageMedia> attachments) {
        if (attachments == null) {
            return Collections.emptyList();
        }
        return attachments.stream()
                .map(this::toAttachmentResponse)
                .toList();
    }

    private MessageAttachmentResponse toAttachmentResponse(MessageMedia media) {
        MessageAttachmentResponse response = MessageAttachmentResponse.fromEntity(media);
        if (response == null) {
            return null;
        }

        ObjectStorageProvider provider = objectStorageProviderRegistry.getProvider(media.getStorageProvider());
        String contentUrl = provider.buildReadUrl(media.getObjectKey());
        String thumbnailUrl = buildDerivedUrlIfExists(provider, media.getThumbnailObjectKey());
        String previewUrl = buildDerivedUrlIfExists(provider, media.getPreviewObjectKey());
        String transcodedUrl = buildDerivedUrlIfExists(provider, media.getTranscodedObjectKey());

        response.setContentUrl(contentUrl);
        response.setDownloadUrl(contentUrl);
        response.setThumbnailUrl(thumbnailUrl);
        response.setPosterUrl(isVideoAttachment(media) ? thumbnailUrl : null);
        response.setPreviewUrl(previewUrl);
        response.setTranscodedUrl(transcodedUrl);
        response.setPlaybackUrl(resolvePlaybackUrl(media, contentUrl, transcodedUrl));
        response.setVideoSources(resolveVideoSources(media, provider, contentUrl));
        return response;
    }

    /**
     * Builds the ordered canonical and optional mobile video source contract.
     */
    private List<VideoSourceResponse> resolveVideoSources(
            MessageMedia media,
            ObjectStorageProvider provider,
            String contentUrl) {
        if (!isVideoAttachment(media)) {
            return List.of();
        }

        List<VideoSourceResponse> sources = new ArrayList<>();
        sources.add(new VideoSourceResponse(
                contentUrl,
                "video/mp4",
                media.getWidth(),
                media.getHeight(),
                media.getSizeBytes(),
                "CANONICAL"));
        String mobileUrl = buildDerivedUrlIfExists(provider, media.getRendition480pObjectKey());
        if (mobileUrl != null) {
            sources.add(new VideoSourceResponse(
                    mobileUrl,
                    "video/mp4",
                    resolveRenditionWidth(media.getWidth(), media.getHeight(), 480),
                    480,
                    media.getRendition480pSizeBytes(),
                    "MOBILE"));
        }
        return List.copyOf(sources);
    }

    /**
     * Resolves the client-facing playback URL for playable media.
     */
    private String resolvePlaybackUrl(MessageMedia media, String contentUrl, String transcodedUrl) {
        if (isVideoAttachment(media) || isAudioAttachment(media)) {
            return transcodedUrl != null ? transcodedUrl : contentUrl;
        }
        return null;
    }

    /**
     * Returns whether the attachment is a video based on its resolved MIME type.
     */
    private boolean isVideoAttachment(MessageMedia media) {
        String mimeType = media.getDetectedMimeType() != null ? media.getDetectedMimeType() : media.getDeclaredMimeType();
        return mimeType != null && mimeType.startsWith("video/");
    }

    /**
     * Returns whether the attachment is audio based on its resolved MIME type.
     */
    private boolean isAudioAttachment(MessageMedia media) {
        String mimeType = media.getDetectedMimeType() != null ? media.getDetectedMimeType() : media.getDeclaredMimeType();
        return mimeType != null && mimeType.startsWith("audio/");
    }

    /**
     * Calculates a proportional even width for a height-constrained source.
     */
    private Integer resolveRenditionWidth(Integer sourceWidth, Integer sourceHeight, int targetHeight) {
        if (sourceWidth == null || sourceHeight == null || sourceHeight <= 0) {
            return null;
        }
        double proportionalWidth = sourceWidth * (targetHeight / (double) sourceHeight);
        return Math.max(2, (int) Math.round(proportionalWidth / 2.0d) * 2);
    }

    private String buildDerivedUrlIfExists(ObjectStorageProvider provider, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return provider.objectExists(objectKey) ? provider.buildReadUrl(objectKey) : null;
    }
}
