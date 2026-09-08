package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.ProcessingTarget;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.model.MediaProcessingJobMessage;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.repository.MessageRepository;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes post-commit video-processing jobs to the dedicated RabbitMQ work queue.
 */
@Service
public class RabbitMediaProcessingService implements MediaProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMediaProcessingService.class);
    private static final List<ProcessingTarget> VIDEO_TARGETS =
            List.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL, ProcessingTarget.TRANSCODE);

    private final MessageRepository messageRepository;
    private final MessageMediaRepository messageMediaRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MediaProcessingIntegrationProperties properties;

    public RabbitMediaProcessingService(
            MessageRepository messageRepository,
            MessageMediaRepository messageMediaRepository,
            RabbitTemplate rabbitTemplate,
            MediaProcessingIntegrationProperties properties) {
        this.messageRepository = messageRepository;
        this.messageMediaRepository = messageMediaRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Publishes one job per video attachment. The caller invokes this only after the message transaction commits.
     *
     * @param messageId committed media-message identifier
     */
    @Override
    public void enqueueProcessing(Long messageId) {
        Long safeMessageId = Objects.requireNonNull(messageId, "messageId must not be null");
        if (!properties.isEnabled()) {
            logger.info("Media-processing integration disabled; leaving messageId={} pending", safeMessageId);
            return;
        }

        Message message = messageRepository.findById(safeMessageId)
                .orElseThrow(() -> new NotFoundException("Message with id " + safeMessageId + " not found"));
        if (message.getMessageType() != MessageType.VIDEO) {
            logger.debug("Skipping external media processing for non-video messageId={}", safeMessageId);
            return;
        }

        List<MessageMedia> attachments =
                messageMediaRepository.findByMessageIdOrderByAttachmentOrderAscIdAsc(safeMessageId);
        for (MessageMedia media : attachments) {
            MediaProcessingJobMessage job = new MediaProcessingJobMessage(
                    "media-" + media.getId(),
                    safeMessageId,
                    media.getId(),
                    MessageType.VIDEO,
                    media.getStorageProvider(),
                    media.getBucket(),
                    media.getObjectKey(),
                    media.getDeclaredMimeType(),
                    VIDEO_TARGETS);
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(), job);
            logger.info(
                    "Published media-processing jobId={} messageId={} mediaId={} objectKey={}",
                    job.jobId(),
                    safeMessageId,
                    media.getId(),
                    media.getObjectKey());
        }
    }
}
