package com.hello.chatapp.service;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.MediaScanStatus;
import com.hello.chatapp.entity.MediaStatus;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.MessageMedia;
import com.hello.chatapp.entity.MessageType;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(MessageHistoryService.class)
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MessageHistoryServiceIntegrationTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, MessageHistoryServiceIntegrationTest.class);
    }

    @Autowired
    private MessageHistoryService messageHistoryService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void getPublicMessages_includesMediaAttachments() {
        User user = userRepository.saveAndFlush(new User("public-user", "secret", "Public User"));

        Message message = new Message();
        message.setUser(user);
        message.setMessageType(MessageType.IMAGE);
        message.setTimestamp(LocalDateTime.now());

        MessageMedia attachment = buildAttachment("public-photo.png", 0);
        message.addAttachment(attachment);
        messageRepository.saveAndFlush(message);

        List<MessageResponse> responses = messageHistoryService.getPublicMessages();

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getMessageType()).isEqualTo(MessageType.IMAGE);
        assertThat(responses.getFirst().getAttachments()).hasSize(1);
        assertThat(responses.getFirst().getAttachments().getFirst().getOriginalFilename()).isEqualTo("public-photo.png");
    }

    @Test
    void getGroupMessages_returnsAscendingMessagesWithMedia() {
        User user = userRepository.saveAndFlush(new User("group-user", "secret", "Group User"));
        Group group = groupRepository.saveAndFlush(new Group("Media Group", user));

        Message older = new Message(user, "hello");
        older.setGroup(group);
        older.setTimestamp(LocalDateTime.now().minusMinutes(1));
        older = messageRepository.saveAndFlush(older);

        Message newer = new Message();
        newer.setUser(user);
        newer.setGroup(group);
        newer.setMessageType(MessageType.FILE);
        newer.setTimestamp(LocalDateTime.now());
        newer.addAttachment(buildAttachment("report.pdf", 0));
        messageRepository.saveAndFlush(newer);

        List<MessageResponse> responses = messageHistoryService.getGroupMessages(group, null, null, 10);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getId()).isEqualTo(older.getId());
        assertThat(responses.get(1).getMessageType()).isEqualTo(MessageType.FILE);
        assertThat(responses.get(1).getAttachments()).hasSize(1);
        assertThat(responses.get(1).getAttachments().getFirst().getOriginalFilename()).isEqualTo("report.pdf");
    }

    private MessageMedia buildAttachment(String filename, int order) {
        MessageMedia attachment = new MessageMedia();
        attachment.setAttachmentOrder(order);
        attachment.setStorageProvider(ObjectStorageProviderType.MINIO);
        attachment.setBucket("chat-media");
        attachment.setObjectKey("media/test/" + filename);
        attachment.setOriginalFilename(filename);
        attachment.setDeclaredMimeType("application/octet-stream");
        attachment.setSizeBytes(1024L);
        attachment.setStatus(MediaStatus.MEDIA_READY);
        attachment.setScanStatus(MediaScanStatus.SCAN_PASSED);
        return attachment;
    }
}
