package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class MessageResponseMapper {

    private final ObjectStorageProviderRegistry objectStorageProviderRegistry;

    public MessageResponseMapper(ObjectStorageProviderRegistry objectStorageProviderRegistry) {
        this.objectStorageProviderRegistry = objectStorageProviderRegistry;
    }

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
        response.setContentUrl(provider.buildReadUrl(media.getObjectKey()));
        response.setThumbnailUrl(buildDerivedUrlIfExists(provider, media.getThumbnailObjectKey()));
        response.setPreviewUrl(buildDerivedUrlIfExists(provider, media.getPreviewObjectKey()));
        response.setTranscodedUrl(buildDerivedUrlIfExists(provider, media.getTranscodedObjectKey()));
        return response;
    }

    private String buildDerivedUrlIfExists(ObjectStorageProvider provider, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return provider.objectExists(objectKey) ? provider.buildReadUrl(objectKey) : null;
    }
}
