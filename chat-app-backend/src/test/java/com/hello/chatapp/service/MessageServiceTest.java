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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private User user;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setId(10L);

        user = new User();
        user.setId(20L);
        user.setUsername("alice");
    }

    @Test
    void saveGroupMessage_updatesLatestFieldsFromSavedMessage() {
        Message savedMessage = buildMessage(100L, user, group, "hello world", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(messageRepository.saveAndFlush(notNull())).thenReturn(savedMessage);
        when(groupRepository.updateLatestMessageIfNewer(anyLong(), anyString(), anyString(), any(LocalDateTime.class), anyLong()))
                .thenReturn(1);

        Message result = messageService.saveGroupMessage(group, user, "hello world");

        assertThat(result).isSameAs(savedMessage);

        verify(groupRepository).updateLatestMessageIfNewer(
                eq(groupId),
                eq("hello world"),
                eq("alice"),
                eq(savedMessage.getTimestamp()),
                eq(100L));
    }

    @Test
    void savePublicMessage_persistsMessageWithNullGroup() {
        Message savedMessage = buildMessage(200L, user, null, "public", LocalDateTime.now());

        when(messageRepository.save(notNull())).thenReturn(savedMessage);

        Message result = messageService.savePublicMessage(user, "public");

        assertThat(result).isSameAs(savedMessage);
        assertThat(result.getGroup()).isNull();
    }

    @Test
    void refreshGroupLatestMessage_usesDeletedPreviewForDeletedLatestMessage() {
        Message deletedMessage = buildMessage(300L, user, group, "secret", LocalDateTime.now());
        deletedMessage.setDeletedAt(LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.existsById(groupId)).thenReturn(true);
        when(messageRepository.findTopByGroup_IdOrderByTimestampDescIdDesc(groupId)).thenReturn(Optional.of(deletedMessage));
        when(groupRepository.updateLatestMessageIfNotStale(
                eq(groupId),
                eq("Message deleted"),
                eq("alice"),
                eq(deletedMessage.getTimestamp()),
                eq(300L))).thenReturn(1);

        messageService.refreshGroupLatestMessage(groupId, 300L);

        verify(groupRepository).updateLatestMessageIfNotStale(
                eq(groupId),
                eq("Message deleted"),
                eq("alice"),
                eq(deletedMessage.getTimestamp()),
                eq(300L));
    }

    @Test
    void refreshGroupLatestMessage_skipsWhenModeratedMessageIsNotLatest() {
        Message latest = buildMessage(400L, user, group, "newest", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.existsById(groupId)).thenReturn(true);
        when(messageRepository.findTopByGroup_IdOrderByTimestampDescIdDesc(groupId)).thenReturn(Optional.of(latest));

        messageService.refreshGroupLatestMessage(groupId, 300L);

        verify(groupRepository, never()).updateLatestMessageIfNotStale(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class), anyLong());
        verify(groupRepository, never()).clearLatestMessageIfEmpty(anyLong());
    }

    @Test
    void refreshGroupLatestMessage_skipsWhenConcurrentNewerSummaryExists() {
        Message latest = buildMessage(300L, user, group, "old", LocalDateTime.now());
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.existsById(groupId)).thenReturn(true);
        when(messageRepository.findTopByGroup_IdOrderByTimestampDescIdDesc(groupId)).thenReturn(Optional.of(latest));
        when(groupRepository.updateLatestMessageIfNotStale(
                anyLong(), anyString(), anyString(), any(LocalDateTime.class), anyLong())).thenReturn(0);

        messageService.refreshGroupLatestMessage(groupId, 300L);

        verify(groupRepository).updateLatestMessageIfNotStale(
                eq(groupId),
                eq("old"),
                eq("alice"),
                eq(latest.getTimestamp()),
                eq(300L));
    }

    @Test
    void refreshGroupLatestMessage_clearsSummaryOnlyWhenGroupHasNoMessages() {
        Long groupId = Objects.requireNonNull(group.getId());

        when(groupRepository.existsById(groupId)).thenReturn(true);
        when(messageRepository.findTopByGroup_IdOrderByTimestampDescIdDesc(groupId)).thenReturn(Optional.empty());
        when(groupRepository.clearLatestMessageIfEmpty(groupId)).thenReturn(1);

        messageService.refreshGroupLatestMessage(groupId, 300L);

        verify(groupRepository).clearLatestMessageIfEmpty(groupId);
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
