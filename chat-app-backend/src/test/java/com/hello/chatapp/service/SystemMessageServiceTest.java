package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class SystemMessageServiceTest {

    @Mock
    private MessageService messageService;

    @Mock
    private MessageResponseMapper messageResponseMapper;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    @Mock
    private GroupRealtimeUpdateService groupRealtimeUpdateService;

    @InjectMocks
    private SystemMessageService systemMessageService;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void recordGroupEvent_publishesRealtimeOnlyAfterCommit() {
        Group group = new Group();
        group.setId(5L);
        User actor = user(7L, "alice");
        User subject = user(8L, "bob");
        Message savedMessage = new Message(actor, "[SYSTEM] USER_KICKED");
        savedMessage.setId(12L);
        savedMessage.setGroup(group);
        savedMessage.setTimestamp(LocalDateTime.now());

        when(messageService.saveGroupSystemMessage(group, subject, actor, SystemEventType.USER_KICKED, "Member removed"))
                .thenReturn(savedMessage);
        MessageResponse response = MessageResponse.builder().id(savedMessage.getId()).groupId(group.getId()).build();
        when(messageResponseMapper.toResponse(savedMessage)).thenReturn(response);

        systemMessageService.recordGroupEvent(group, subject, actor, SystemEventType.USER_KICKED);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(MessageResponse.class));
        triggerAfterCommit();

        verify(messagingTemplate).convertAndSend(eq("/topic/group.5"), eq(response));
        verify(rabbitMQBrokerHandler).publishToRabbitMQ(eq("/topic/group.5"), eq(response));
        verify(groupRealtimeUpdateService).publishCurrentMembersSnapshot(5L);
        verify(groupRealtimeUpdateService).publishRemovedGroup(5L, "bob");
    }

    @Test
    void recordGroupEvent_leaveGroupSchedulesRemovalForActor() {
        Group group = new Group();
        group.setId(9L);
        User actor = user(3L, "charlie");
        Message savedMessage = new Message(actor, "[SYSTEM] USER_LEFT");
        savedMessage.setId(99L);
        savedMessage.setGroup(group);
        savedMessage.setTimestamp(LocalDateTime.now());

        when(messageService.saveGroupSystemMessage(group, actor, actor, SystemEventType.USER_LEFT, "Member left"))
                .thenReturn(savedMessage);
        when(messageResponseMapper.toResponse(savedMessage))
                .thenReturn(MessageResponse.builder().id(savedMessage.getId()).groupId(group.getId()).build());

        systemMessageService.recordGroupEvent(group, actor, actor, SystemEventType.USER_LEFT);
        triggerAfterCommit();

        verify(groupRealtimeUpdateService).publishRemovedGroup(9L, "charlie");
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private void triggerAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }
}
