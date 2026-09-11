package com.hello.chatapp.service;

import com.hello.chatapp.constant.MediaProcessingJobStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.ProcessingTarget;
import com.hello.chatapp.dto.MediaProcessingResultRequest;
import com.hello.chatapp.dto.MediaProcessingVideoMetadataRequest;
import com.hello.chatapp.dto.MediaProcessingVideoRenditionRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.repository.MessageMediaRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.storage.ObjectStorageProvider;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers idempotent backend application of media-processing callbacks.
 */
class MediaProcessingResultServiceTest {

    private MessageMediaRepository mediaRepository;
    private MessageRepository messageRepository;
    private ObjectStorageProviderRegistry providerRegistry;
    private MessageResponseMapper responseMapper;
    private RealtimeMessageDeliveryService deliveryService;
    private ObjectStorageProvider provider;
    private MessageMedia media;
    private Message message;
    private MediaProcessingResultService service;

    /**
     * Creates one public video message and mocked callback dependencies.
     */
    @BeforeEach
    void setUp() {
        mediaRepository = mock(MessageMediaRepository.class);
        messageRepository = mock(MessageRepository.class);
        providerRegistry = mock(ObjectStorageProviderRegistry.class);
        responseMapper = mock(MessageResponseMapper.class);
        deliveryService = mock(RealtimeMessageDeliveryService.class);
        provider = mock(ObjectStorageProvider.class);
        message = new Message();
        message.setId(10L);
        media = new MessageMedia();
        media.setId(20L);
        media.setMessage(message);
        media.setStorageProvider(ObjectStorageProviderType.MINIO);
        media.setObjectKey("media/7/video/input.mov");
        media.setStatus(MediaStatus.PROCESSING_PENDING);
        when(mediaRepository.findByIdAndMessageId(20L, 10L)).thenReturn(Optional.of(media));
        when(providerRegistry.getProvider(ObjectStorageProviderType.MINIO)).thenReturn(provider);
        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(responseMapper.toResponse(message)).thenReturn(new MessageResponse());
        service = new MediaProcessingResultService(
                mediaRepository, messageRepository, providerRegistry, responseMapper, deliveryService);
    }

    /**
     * Verifies a ready result switches to the canonical MP4, updates metadata, deletes the original, and republishes.
     */
    @Test
    void apply_readyResult_switchesCanonicalObjectAndDeletesOriginal() {
        when(provider.objectExists("media/7/video/input.thumbnail.jpg")).thenReturn(true);
        when(provider.objectExists("media/7/video/input.transcoded.mp4")).thenReturn(true);
        when(provider.objectExists("media/7/video/input.480p.mp4")).thenReturn(true);

        service.apply(readyRequest());

        assertThat(media.getObjectKey()).isEqualTo("media/7/video/input.transcoded.mp4");
        assertThat(media.getTranscodedObjectKey()).isEqualTo("media/7/video/input.transcoded.mp4");
        assertThat(media.getThumbnailObjectKey()).isEqualTo("media/7/video/input.thumbnail.jpg");
        assertThat(media.getRendition480pObjectKey()).isEqualTo("media/7/video/input.480p.mp4");
        assertThat(media.getRendition480pSizeBytes()).isEqualTo(40L);
        assertThat(media.getDetectedMimeType()).isEqualTo("video/mp4");
        assertThat(media.getSizeBytes()).isEqualTo(80L);
        assertThat(media.getWidth()).isEqualTo(1920);
        assertThat(media.getHeight()).isEqualTo(1080);
        assertThat(media.getDurationMs()).isEqualTo(12_345L);
        assertThat(media.getStatus()).isEqualTo(MediaStatus.MEDIA_READY);
        verify(provider).deleteObject("media/7/video/input.mov");
        verify(deliveryService).publishToPublic(org.mockito.ArgumentMatchers.any(MessageResponse.class));
    }

    /**
     * Verifies an idempotent duplicate callback does not delete the original again.
     */
    @Test
    void apply_duplicateReadyResult_doesNotDeleteAgain() {
        media.setObjectKey("media/7/video/input.transcoded.mp4");
        media.setTranscodedObjectKey("media/7/video/input.transcoded.mp4");
        media.setStatus(MediaStatus.MEDIA_READY);
        when(provider.objectExists("media/7/video/input.transcoded.mp4")).thenReturn(true);

        service.apply(readyRequest());

        verify(provider, never()).deleteObject("media/7/video/input.mov");
        assertThat(media.getStatus()).isEqualTo(MediaStatus.MEDIA_READY);
    }

    /**
     * Verifies a failed worker result preserves the original object and marks processing failed.
     */
    @Test
    void apply_failedResult_preservesOriginal() {
        MediaProcessingResultRequest request = new MediaProcessingResultRequest(
                "media-20",
                10L,
                20L,
                MediaProcessingJobStatus.PROCESSING_FAILED,
                null,
                Set.of(),
                Set.of(ProcessingTarget.TRANSCODE),
                "media/7/video/input.mov",
                null,
                null,
                null,
                false,
                java.util.List.of());

        service.apply(request);

        assertThat(media.getObjectKey()).isEqualTo("media/7/video/input.mov");
        assertThat(media.getStatus()).isEqualTo(MediaStatus.PROCESSING_FAILED);
        verify(provider, never()).deleteObject(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * Builds a successful derived-MP4 callback.
     *
     * @return ready callback request
     */
    private MediaProcessingResultRequest readyRequest() {
        return new MediaProcessingResultRequest(
                "media-20",
                10L,
                20L,
                MediaProcessingJobStatus.MEDIA_READY,
                new MediaProcessingVideoMetadataRequest(
                        12_345L, 1920, 1080, "video/quicktime", "mov", "h264", "aac"),
                Set.of(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE),
                Set.of(),
                "media/7/video/input.mov",
                "media/7/video/input.thumbnail.jpg",
                "media/7/video/input.transcoded.mp4",
                80L,
                false,
                java.util.List.of(new MediaProcessingVideoRenditionRequest(
                        "media/7/video/input.480p.mp4",
                        "video/mp4",
                        854,
                        480,
                        40L)));
    }
}
