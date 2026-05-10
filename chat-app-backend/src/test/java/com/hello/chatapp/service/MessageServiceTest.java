package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private GroupRepository groupRepository;

    @InjectMocks
    private MessageService messageService;

    private Group group;
    private Group lockedGroup;
    private User user;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setId(10L);

        lockedGroup = new Group();
        lockedGroup.setId(10L);

        user = new User();
        user.setId(20L);
        user.setUsername("alice");
    }

    @Test
    void saveGroupMessage_updatesLatestFieldsFromSavedMessage() {
        Message savedMessage = buildMessage(100L, user, lockedGroup, "hello world", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.findByIdForLatestMessageUpdate(groupId)).thenReturn(Optional.of(lockedGroup));
        when(messageRepository.saveAndFlush(notNull())).thenReturn(savedMessage);

        Message result = messageService.saveGroupMessage(group, user, "hello world");

        assertThat(result).isSameAs(savedMessage);
        assertThat(lockedGroup.getLatestMessage()).isEqualTo("hello world");
        assertThat(lockedGroup.getLatestMessageSender()).isEqualTo("alice");
        assertThat(lockedGroup.getLatestMessageAt()).isEqualTo(savedMessage.getTimestamp());
    }

    @Test
    void savePublicMessage_persistsMessageWithNullGroup() {
        Message savedMessage = buildMessage(200L, user, null, "public", LocalDateTime.now());

        when(messageRepository.save(notNull())).thenReturn(savedMessage);

        Message result = messageService.savePublicMessage(user, "public");

        assertThat(result).isSameAs(savedMessage);
        assertThat(result.getGroup()).isNull();
    }

    private Message buildMessage(Long id, User messageUser, Group messageGroup, String content, LocalDateTime timestamp) {
        Message message = new Message();
        message.setId(id);
        message.setUser(messageUser);
        message.setGroup(messageGroup);
        message.setContent(content);
        message.setTimestamp(timestamp);
        return message;
    }
}
