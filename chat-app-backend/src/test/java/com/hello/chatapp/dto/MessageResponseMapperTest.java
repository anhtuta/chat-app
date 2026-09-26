package com.hello.chatapp.dto;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.model.SystemEventPayload;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import com.hello.chatapp.storage.S3ObjectStorageProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps {@link Message} rows to API responses, including structured system-event fields.
 */
class MessageResponseMapperTest {

    /**
     * Attachment payloads include a storage-backed content URL.
     */
    @Test
    void toResponse_addsContentUrlForAttachments() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = new MessageResponseMapper(
                new ObjectStorageProviderRegistry(
                        List.of(new S3ObjectStorageProvider(properties)),
                        properties));

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(10L);
        message.setUser(user);
        message.setMessageType(MessageType.IMAGE);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(100L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/photo.png");
        attachment.setOriginalFilename("photo.png");
        attachment.setDeclaredMimeType("image/png");
        attachment.setSizeBytes(1234L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getAttachments()).hasSize(1);
        assertThat(response.getAttachments().getFirst().getContentUrl()).isNotBlank();
        assertThat(response.getAttachments().getFirst().getDownloadUrl()).isNotBlank();
        assertThat(response.getAttachments().getFirst().getContentUrl()).contains("chat-media/media/1/photo.png");
    }

    /**
     * Video attachments expose the canonical playback/download contract needed by the frontend player.
     */
    @Test
    void toResponse_mapsVideoPlaybackContractFields() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = new MessageResponseMapper(
                new ObjectStorageProviderRegistry(
                        List.of(new S3ObjectStorageProvider(properties)),
                        properties));

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(11L);
        message.setUser(user);
        message.setMessageType(MessageType.VIDEO);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(101L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/demo.transcoded.mp4");
        attachment.setOriginalFilename("demo.mov");
        attachment.setDeclaredMimeType("video/quicktime");
        attachment.setDetectedMimeType("video/mp4");
        attachment.setSizeBytes(4321L);
        attachment.setWidth(1920);
        attachment.setHeight(1080);
        attachment.setDurationMs(12_345L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        attachment.setThumbnailObjectKey("media/1/demo.thumbnail.jpg");
        attachment.setTranscodedObjectKey("media/1/demo.transcoded.mp4");
        attachment.setRendition480pObjectKey("media/1/demo.480p.mp4");
        attachment.setRendition480pSizeBytes(2_000L);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getAttachments()).hasSize(1);
        MessageAttachmentResponse mapped = response.getAttachments().getFirst();
        assertThat(mapped.getDurationMs()).isEqualTo(12_345L);
        assertThat(mapped.getWidth()).isEqualTo(1920);
        assertThat(mapped.getHeight()).isEqualTo(1080);
        assertThat(mapped.getPlaybackUrl()).isEqualTo(mapped.getTranscodedUrl());
        assertThat(mapped.getDownloadUrl()).isEqualTo(mapped.getContentUrl());
        assertThat(mapped.getPosterUrl()).isEqualTo(mapped.getThumbnailUrl());
        assertThat(mapped.getVideoSources()).hasSize(2);
        assertThat(mapped.getVideoSources().getFirst().role()).isEqualTo("CANONICAL");
        assertThat(mapped.getVideoSources().get(1).role()).isEqualTo("MOBILE");
        assertThat(mapped.getVideoSources().get(1).height()).isEqualTo(480);
        assertThat(mapped.getVideoSources().get(1).sizeBytes()).isEqualTo(2_000L);
    }

    /**
     * SYSTEM rows expose event type plus actor (updatedBy) separately from the subject user.
     */
    @Test
    void toResponse_mapsSystemEventMetadata() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = new MessageResponseMapper(
                new ObjectStorageProviderRegistry(
                        List.of(new S3ObjectStorageProvider(properties)),
                        properties));

        User actor = new User("alice", "secret", "Alice");
        actor.setId(1L);

        User subject = new User("bob", "secret", "Bob");
        subject.setId(2L);

        Message message = new Message();
        message.setId(12L);
        message.setUser(subject);
        message.setUpdatedBy(actor);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(SystemEventType.USER_LEFT.name());

        MessageResponse response = mapper.toResponse(message);

        assertThat(response).isNotNull();
        assertThat(response.getSystemEventType()).isEqualTo(SystemEventType.USER_LEFT);
        assertThat(response.getSystemEventActor()).isNotNull();
        assertThat(response.getSystemEventActor().getUsername()).isEqualTo("alice");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("bob");
    }

    /**
     * Batch add-member events expose every added display name on {@code systemEventPayload}.
     */
    @Test
    void toResponse_mapsSystemEventPayloadSubjectNames() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = new MessageResponseMapper(
                new ObjectStorageProviderRegistry(
                        List.of(new S3ObjectStorageProvider(properties)),
                        properties));

        User actor = new User("alice", "secret", "Alice");
        User subject = new User("bob", "secret", "Bob");
        Message message = new Message();
        message.setId(13L);
        message.setUser(subject);
        message.setUpdatedBy(actor);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(SystemEventType.USER_JOINED.name());
        message.setSystemEventPayload(SystemEventPayload.ofSubjectNames(List.of("Bob", "Carol")));

        MessageResponse response = mapper.toResponse(message);

        assertThat(response.getSystemEventPayload()).isNotNull();
        assertThat(response.getSystemEventPayload().getSubjectNames()).containsExactly("Bob", "Carol");
    }

    /**
     * Freshness keys advance for attachment processing updates, not just message edits.
     */
    @Test
    void toResponse_usesAttachmentUpdatesForFreshnessKey() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setProvider(ObjectStorageProviderType.S3);

        MessageResponseMapper mapper = new MessageResponseMapper(
                new ObjectStorageProviderRegistry(
                        List.of(new S3ObjectStorageProvider(properties)),
                        properties));

        LocalDateTime messageTimestamp = LocalDateTime.of(2026, 9, 24, 11, 0, 0);
        LocalDateTime attachmentUpdatedAt = messageTimestamp.plusMinutes(2);

        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(14L);
        message.setUser(user);
        message.setMessageType(MessageType.VIDEO);
        message.setTimestamp(messageTimestamp);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(102L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.S3);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1/demo.mp4");
        attachment.setOriginalFilename("demo.mp4");
        attachment.setDeclaredMimeType("video/mp4");
        attachment.setSizeBytes(3_210L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        attachment.setUpdatedAt(attachmentUpdatedAt);
        message.addAttachment(attachment);

        MessageResponse response = mapper.toResponse(message);

        assertThat(response.getFreshnessKey()).isEqualTo(attachmentUpdatedAt.toString());
    }
}
