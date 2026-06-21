package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.MediaStatus;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class AsyncMediaProcessingService implements MediaProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncMediaProcessingService.class);

    private final MessageRepository messageRepository;
    private final MessageMediaRepository messageMediaRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    public AsyncMediaProcessingService(
            MessageRepository messageRepository,
            MessageMediaRepository messageMediaRepository,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler) {
        this.messageRepository = messageRepository;
        this.messageMediaRepository = messageMediaRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
    }

    @Override
    @Async("mediaProcessingExecutor")
    public void enqueueProcessing(Long messageId) {
        try {
            Message message = loadMessageWithMedia(messageId);
            if (message.getMessageType() != MessageType.IMAGE && message.getMessageType() != MessageType.VIDEO) {
                return;
            }

            List<MessageMedia> mediaList = messageMediaRepository.findByMessageIdOrderByAttachmentOrderAscIdAsc(messageId);
            if (mediaList.isEmpty()) {
                return;
            }

            boolean changedToInProgress = false;
            for (MessageMedia media : mediaList) {
                if (media.getStatus() == MediaStatus.PROCESSING_PENDING) {
                    media.setStatus(MediaStatus.PROCESSING_IN_PROGRESS);
                    media.setDetectedMimeType(resolveDetectedMimeType(media));
                    changedToInProgress = true;
                }
            }
            if (changedToInProgress) {
                messageMediaRepository.saveAll(mediaList);
                publishUpdatedMessage(messageId);
            }

            for (MessageMedia media : mediaList) {
                populateDerivedMetadata(message.getMessageType(), media);
                media.setStatus(MediaStatus.MEDIA_READY);
            }
            messageMediaRepository.saveAll(mediaList);
            publishUpdatedMessage(messageId);
        } catch (Exception e) {
            logger.error("Async media processing failed for messageId={}", messageId, e);
            markProcessingFailed(messageId);
        }
    }

    private void markProcessingFailed(Long messageId) {
        try {
            List<MessageMedia> mediaList = messageMediaRepository.findByMessageIdOrderByAttachmentOrderAscIdAsc(messageId);
            if (mediaList.isEmpty()) {
                return;
            }
            for (MessageMedia media : mediaList) {
                if (media.getStatus() == MediaStatus.PROCESSING_PENDING
                        || media.getStatus() == MediaStatus.PROCESSING_IN_PROGRESS) {
                    media.setStatus(MediaStatus.PROCESSING_FAILED);
                }
            }
            messageMediaRepository.saveAll(mediaList);
            publishUpdatedMessage(messageId);
        } catch (Exception publishFailure) {
            logger.error("Failed to mark media processing failure for messageId={}", messageId, publishFailure);
        }
    }

    private void populateDerivedMetadata(MessageType messageType, MessageMedia media) {
        String objectKey = media.getObjectKey();
        media.setDetectedMimeType(resolveDetectedMimeType(media));

        if (messageType == MessageType.IMAGE) {
            // TODO: Replace derived-key placeholders with real MinIO-backed thumbnail/preview generation.
            media.setThumbnailObjectKey(objectKey + ".thumbnail.jpg");
            media.setPreviewObjectKey(objectKey + ".preview");
            return;
        }

        if (messageType == MessageType.VIDEO) {
            // TODO: Replace derived-key placeholders with real MinIO-backed video thumbnail/transcode outputs.
            media.setThumbnailObjectKey(objectKey + ".thumbnail.jpg");
            media.setTranscodedObjectKey(objectKey + ".transcoded");
        }
    }

    private String resolveDetectedMimeType(MessageMedia media) {
        return media.getDetectedMimeType() != null ? media.getDetectedMimeType() : media.getDeclaredMimeType();
    }

    private Message loadMessageWithMedia(Long messageId) {
        return messageRepository.findWithMediaById(messageId)
                .orElseThrow(() -> new NotFoundException("Message with id " + messageId + " not found"));
    }

    private void publishUpdatedMessage(Long messageId) {
        Message message = loadMessageWithMedia(messageId);
        MessageResponse response = Objects.requireNonNull(MessageResponse.fromMessage(message));
        String destination = message.getGroup() == null
                ? "/topic/public"
                : "/topic/group." + Objects.requireNonNull(message.getGroup().getId());
        messagingTemplate.convertAndSend(destination, response);
        rabbitMQBrokerHandler.publishToRabbitMQ(destination, response);
    }
}
