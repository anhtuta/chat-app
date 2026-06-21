package com.hello.chatapp.dto;

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
                .content(message.getContent())
                .attachments(toAttachmentResponses(message.getAttachments()))
                .timestamp(message.getTimestamp())
                .build();
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
