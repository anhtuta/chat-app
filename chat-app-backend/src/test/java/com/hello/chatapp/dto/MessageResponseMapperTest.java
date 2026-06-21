package com.hello.chatapp.dto;

import com.hello.chatapp.config.MediaStorageProperties;
import com.hello.chatapp.entity.MediaScanStatus;
import com.hello.chatapp.entity.MediaStatus;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import com.hello.chatapp.storage.S3ObjectStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageResponseMapperTest {

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
}
