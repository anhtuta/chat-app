package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
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

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MessageService messageService;

    private Group group;
    private Group lockedGroup;
    private User user;
    private User managedUser;
    private Group managedGroup;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setId(10L);

        managedGroup = new Group();
        managedGroup.setId(10L);

        lockedGroup = new Group();
        lockedGroup.setId(10L);

        user = new User();
        user.setId(20L);
        user.setUsername("alice");

        managedUser = new User();
        managedUser.setId(20L);
        managedUser.setUsername("alice");
    }

    @Test
    void saveGroupMessage_updatesLatestFieldsFromSavedMessageWhenItRemainsNewest() {
        Message savedMessage = buildMessage(100L, managedUser, lockedGroup, "hello world", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());
        Long userId = Objects.requireNonNull(user.getId());

        when(groupRepository.findByIdForLatestMessageUpdate(groupId)).thenReturn(Optional.of(lockedGroup));
        when(userRepository.getReferenceById(userId)).thenReturn(managedUser);
        when(messageRepository.saveAndFlush(notNull())).thenReturn(savedMessage);
        when(messageRepository.findLatestGroupMessages(lockedGroup, PageRequest.of(0, 1)))
                .thenReturn(List.of(savedMessage));

        Message result = messageService.saveGroupMessage(group, user, "hello world");

        assertThat(result).isSameAs(savedMessage);
        assertThat(lockedGroup.getLatestMessage()).isEqualTo("hello world");
        assertThat(lockedGroup.getLatestMessageSender()).isEqualTo("alice");
        assertThat(lockedGroup.getLatestMessageAt()).isEqualTo(savedMessage.getTimestamp());
    }

    @Test
    void saveGroupMessage_usesActualLatestMessageWhenAnotherWriteWinsRace() {
        Message savedMessage = buildMessage(100L, managedUser, lockedGroup, "older", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());
        Long userId = Objects.requireNonNull(user.getId());
        User newerUser = new User();
        newerUser.setId(21L);
        newerUser.setUsername("bob");
        Message newerMessage = buildMessage(101L, newerUser, lockedGroup, "newer", savedMessage.getTimestamp().plusNanos(1));

        when(groupRepository.findByIdForLatestMessageUpdate(groupId)).thenReturn(Optional.of(lockedGroup));
        when(userRepository.getReferenceById(userId)).thenReturn(managedUser);
        when(messageRepository.saveAndFlush(notNull())).thenReturn(savedMessage);
        when(messageRepository.findLatestGroupMessages(lockedGroup, PageRequest.of(0, 1)))
                .thenReturn(List.of(newerMessage));

        Message result = messageService.saveGroupMessage(group, user, "older");

        assertThat(result).isSameAs(savedMessage);
        assertThat(lockedGroup.getLatestMessage()).isEqualTo("newer");
        assertThat(lockedGroup.getLatestMessageSender()).isEqualTo("bob");
        assertThat(lockedGroup.getLatestMessageAt()).isEqualTo(newerMessage.getTimestamp());
    }

    @Test
    void savePublicMessage_persistsMessageWithNullGroup() {
        Long userId = Objects.requireNonNull(user.getId());
        Message savedMessage = buildMessage(200L, managedUser, null, "public", LocalDateTime.now());

        when(userRepository.getReferenceById(userId)).thenReturn(managedUser);
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
