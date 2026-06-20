package com.hello.chatapp.dto;

import com.hello.chatapp.entity.MediaScanStatus;
import com.hello.chatapp.entity.MediaStatus;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.MessageType;
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
}
