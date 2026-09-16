package com.hello.chatapp.service;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for post-commit group-profile realtime fan-out behavior.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupProfileRealtimePublisherTest {

    @Mock
    private RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    @Mock
    private GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;

    private GroupProfileRealtimePublisher publisher;
    private Group group;
    private Message systemMessage;

    @BeforeEach
    void setUp() {
        publisher = new GroupProfileRealtimePublisher(
                realtimeMessageDeliveryService,
                groupSummaryUpdatePublisher);

        User actor = new User();
        actor.setId(1L);
        actor.setUsername("alice");

        group = new Group();
        group.setId(100L);
        group.setName("Backend Team");
        group.setDescription("Core backend work");

        systemMessage = new Message();
        systemMessage.setId(901L);
        systemMessage.setGroup(group);
        systemMessage.setUser(actor);
        systemMessage.setUpdatedBy(actor);
        systemMessage.setMessageType(MessageType.SYSTEM);
        systemMessage.setContent(SystemEventType.GROUP_NAME_UPDATED.name());
        systemMessage.setTimestamp(LocalDateTime.of(2026, 8, 1, 12, 0));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    /**
     * Outside a transaction, the group chat line and metadata refresh should publish immediately.
     */
    @Test
    void publishGroupProfileChange_withoutActiveTransaction_publishesImmediately() {
        publisher.publishGroupProfileChange(group, systemMessage, "Group name updated");

        verify(realtimeMessageDeliveryService).publishToGroup(eq(100L), any(MessageResponse.class));

        ArgumentCaptor<GroupSummaryUpdate> summaryCaptor = ArgumentCaptor.forClass(GroupSummaryUpdate.class);
        verify(groupSummaryUpdatePublisher).publishToGroupMembers(eq(100L), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getName()).isEqualTo("Backend Team");
        assertThat(summaryCaptor.getValue().getDescription()).isEqualTo("Core backend work");
        assertThat(summaryCaptor.getValue().getLatestMessage()).isEqualTo("Group name updated");
        assertThat(summaryCaptor.getValue().getLatestMessageSender()).isEqualTo("System");
    }

    /**
     * With an active transaction, publishing should wait until the commit hook fires.
     */
    @Test
    void publishGroupProfileChange_withActiveTransaction_waitsUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishGroupProfileChange(group, systemMessage, "Group description updated");

            verify(realtimeMessageDeliveryService, never()).publishToGroup(any(), any());
            verify(groupSummaryUpdatePublisher, never()).publishToGroupMembers(any(), any());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(realtimeMessageDeliveryService).publishToGroup(eq(100L), any(MessageResponse.class));
            verify(groupSummaryUpdatePublisher).publishToGroupMembers(eq(100L), any(GroupSummaryUpdate.class));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    /**
     * Only structured SYSTEM messages may travel through this publisher.
     */
    @Test
    void publishGroupProfileChange_withNonSystemMessage_throwsIllegalArgumentException() {
        systemMessage.setMessageType(MessageType.TEXT);

        assertThatThrownBy(() -> publisher.publishGroupProfileChange(group, systemMessage, "Group archived"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("systemMessage must be a SYSTEM message");
    }
}
