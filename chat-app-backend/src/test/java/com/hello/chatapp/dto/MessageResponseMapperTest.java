package com.hello.chatapp.dto;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import com.hello.chatapp.storage.S3ObjectStorageProvider;
import org.junit.jupiter.api.Test;

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
        assertThat(response.getAttachments().getFirst().getContentUrl()).contains("chat-media/media/1/photo.png");
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
     * Batch add-member events expose every added display name on the SYSTEM payload.
     */
    @Test
    void toResponse_mapsSystemEventSubjectNames() {
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
        message.setSystemEventSubjectNames(List.of("Bob", "Carol"));

        MessageResponse response = mapper.toResponse(message);

        assertThat(response.getSystemEventSubjectNames()).containsExactly("Bob", "Carol");
    }
}
