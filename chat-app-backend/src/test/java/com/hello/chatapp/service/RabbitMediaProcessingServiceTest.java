package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.ProcessingTarget;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.model.MediaProcessingJobMessage;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers Phase 7 publication of video-processing jobs to RabbitMQ.
 */
class RabbitMediaProcessingServiceTest {

    /**
     * Verifies one stable job is published for a committed video attachment.
     */
    @Test
    void enqueueProcessing_video_publishesWorkerContract() {
        MessageRepository messageRepository = mock(MessageRepository.class);
        MessageMediaRepository mediaRepository = mock(MessageMediaRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        MediaProcessingIntegrationProperties properties = enabledProperties();
        Message message = new Message();
        message.setId(10L);
        message.setMessageType(MessageType.VIDEO);
        MessageMedia media = videoMedia(message);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(message));
        when(mediaRepository.findByMessageIdOrderByAttachmentOrderAscIdAsc(10L)).thenReturn(List.of(media));

        new RabbitMediaProcessingService(messageRepository, mediaRepository, rabbitTemplate, properties)
                .enqueueProcessing(10L);

        ArgumentCaptor<MediaProcessingJobMessage> jobCaptor =
                ArgumentCaptor.forClass(MediaProcessingJobMessage.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("media.processing"),
                org.mockito.ArgumentMatchers.eq("media.processing.video"),
                jobCaptor.capture());
        MediaProcessingJobMessage job = jobCaptor.getValue();
        assertThat(job.jobId()).isEqualTo("media-20");
        assertThat(job.messageId()).isEqualTo(10L);
        assertThat(job.mediaId()).isEqualTo(20L);
        assertThat(job.objectKey()).isEqualTo("media/7/video/input.mov");
        assertThat(job.processingTargets())
                    .containsExactly(
                            ProcessingTarget.METADATA,
                            ProcessingTarget.THUMBNAIL,
                            ProcessingTarget.TRANSCODE);
    }

    /**
     * Verifies disabled integration does not load or publish work.
     */
    @Test
    void enqueueProcessing_disabled_doesNotPublish() {
        MessageRepository messageRepository = mock(MessageRepository.class);
        MessageMediaRepository mediaRepository = mock(MessageMediaRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        new RabbitMediaProcessingService(
                messageRepository,
                mediaRepository,
                rabbitTemplate,
                new MediaProcessingIntegrationProperties())
                .enqueueProcessing(10L);

        verify(messageRepository, never()).findById(10L);
        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * Builds enabled integration properties with the default topology.
     *
     * @return enabled properties
     */
    private MediaProcessingIntegrationProperties enabledProperties() {
        MediaProcessingIntegrationProperties properties = new MediaProcessingIntegrationProperties();
        properties.setEnabled(true);
        return properties;
    }

    /**
     * Builds a persisted MOV attachment for publisher tests.
     *
     * @param message parent video message
     * @return media attachment
     */
    private MessageMedia videoMedia(Message message) {
        MessageMedia media = new MessageMedia();
        media.setId(20L);
        media.setMessage(message);
        media.setStorageProvider(ObjectStorageProviderType.MINIO);
        media.setBucket("chat-media");
        media.setObjectKey("media/7/video/input.mov");
        media.setOriginalFilename("input.mov");
        media.setDeclaredMimeType("video/quicktime");
        media.setSizeBytes(100L);
        media.setStatus(MediaStatus.PROCESSING_PENDING);
        return media;
    }
}
