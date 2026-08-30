package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MediaScanStatus;
import com.hello.chatapp.constant.MediaStatus;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageResponseTest {

    @Test
    void fromMessage_mapsMediaFieldsAndAttachments() {
        User user = new User("alice", "secret", "Alice");
        user.setId(1L);

        Message message = new Message();
        message.setId(10L);
        message.setUser(user);
        message.setMessageType(MessageType.IMAGE);
        message.setContent(null);

        MessageMedia attachment = new MessageMedia();
        attachment.setId(100L);
        attachment.setAttachmentOrder(0);
        attachment.setStorageProvider(ObjectStorageProviderType.MINIO);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/1.png");
        attachment.setOriginalFilename("photo.png");
        attachment.setDeclaredMimeType("image/png");
        attachment.setSizeBytes(1234L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        attachment.setWidth(640);
        attachment.setHeight(480);
        message.addAttachment(attachment);

        MessageResponse response = MessageResponse.fromMessage(message);

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo(MessageType.IMAGE);
        assertThat(response.getContent()).isNull();
        assertThat(response.getAttachments()).hasSize(1);
        assertThat(response.getAttachments().getFirst().getOriginalFilename()).isEqualTo("photo.png");
        assertThat(response.getAttachments().getFirst().getStatus()).isEqualTo(MediaStatus.MEDIA_READY);
        assertThat(response.getAttachments().getFirst().getScanStatus()).isEqualTo(MediaScanStatus.SCAN_PASSED);
    }

    @Test
    void fromMessage_mapsStructuredSystemEventMetadata() {
        User actor = new User("alice", "secret", "Alice");
        actor.setId(1L);

        User subject = new User("bob", "secret", "Bob");
        subject.setId(2L);

        Message message = new Message();
        message.setId(11L);
        message.setUser(subject);
        message.setUpdatedBy(actor);
        message.setMessageType(MessageType.SYSTEM);
        message.setContent(SystemEventType.USER_JOINED.name());

        MessageResponse response = MessageResponse.fromMessage(message);

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(response.getSystemEventType()).isEqualTo(SystemEventType.USER_JOINED);
        assertThat(response.getSystemEventActor()).isNotNull();
        assertThat(response.getSystemEventActor().getUsername()).isEqualTo("alice");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("bob");
    }
}
