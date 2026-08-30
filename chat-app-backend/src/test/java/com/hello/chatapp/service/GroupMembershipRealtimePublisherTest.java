package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.constant.GroupRole;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for post-commit membership realtime fan-out behavior.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupMembershipRealtimePublisherTest {

    @Mock
    private RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    @Mock
    private GroupSummaryUpdatePublisher groupSummaryUpdatePublisher;

    private GroupMembershipRealtimePublisher publisher;
    private Group group;
    private Message systemMessage;

    @BeforeEach
    void setUp() {
        publisher = new GroupMembershipRealtimePublisher(
                realtimeMessageDeliveryService,
                groupSummaryUpdatePublisher);

        User subject = new User();
        subject.setId(2L);
        subject.setUsername("bob");

        User actor = new User();
        actor.setId(1L);
        actor.setUsername("alice");

        group = new Group();
        group.setId(100L);
        group.setName("Backend Team");

        systemMessage = new Message();
        systemMessage.setId(900L);
        systemMessage.setGroup(group);
        systemMessage.setUser(subject);
        systemMessage.setUpdatedBy(actor);
        systemMessage.setMessageType(MessageType.SYSTEM);
        systemMessage.setContent(SystemEventType.USER_KICKED.name());
        systemMessage.setTimestamp(LocalDateTime.of(2026, 8, 1, 12, 0));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    /**
     * Outside a transaction, the group topic and personal removal update should publish immediately.
     */
    @Test
    void publishMembershipChange_withoutActiveTransaction_publishesImmediately() {
        publisher.publishMembershipChange(group, systemMessage, "Member removed", "bob");

        verify(realtimeMessageDeliveryService).publishToGroup(eq(100L), any(MessageResponse.class));

        ArgumentCaptor<GroupSummaryUpdate> summaryCaptor = ArgumentCaptor.forClass(GroupSummaryUpdate.class);
        verify(groupSummaryUpdatePublisher).publishToGroupMembers(eq(100L), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getName()).isEqualTo("Backend Team");
        assertThat(summaryCaptor.getValue().getLatestMessage()).isEqualTo("Member removed");
        assertThat(summaryCaptor.getValue().getLatestMessageSender()).isEqualTo("System");
        assertThat(summaryCaptor.getValue().isRemoved()).isFalse();

        verify(groupSummaryUpdatePublisher).publishToUser("bob", GroupSummaryUpdate.removed(100L));
    }

    /**
     * Personal role-refresh updates should bypass the buffered member fan-out.
     */
    @Test
    void publishMembershipChange_withPersonalUpdates_publishesImmediateAccessRefresh() {
        GroupSummaryUpdate accessUpdate = GroupSummaryUpdate.forSystemEventWithAccess(
                100L,
                "Backend Team",
                "Member promoted",
                systemMessage.getTimestamp(),
                GroupRole.CO_LEADER,
                List.of(GroupPermission.MANAGE_ROLES));

        publisher.publishMembershipChange(
                group,
                systemMessage,
                "Member promoted",
                null,
                Map.of("bob", accessUpdate));

        verify(groupSummaryUpdatePublisher).publishToUser("bob", accessUpdate);
    }

    /**
     * With an active transaction, publishing should wait until the commit hook fires.
     */
    @Test
    void publishMembershipChange_withActiveTransaction_waitsUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishMembershipChange(group, systemMessage, "Member joined", null);

            verify(realtimeMessageDeliveryService, never()).publishToGroup(any(), any());
            verify(groupSummaryUpdatePublisher, never()).publishToGroupMembers(any(), any());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(realtimeMessageDeliveryService).publishToGroup(eq(100L), any(MessageResponse.class));
            verify(groupSummaryUpdatePublisher).publishToGroupMembers(eq(100L), any(GroupSummaryUpdate.class));
            verify(groupSummaryUpdatePublisher, never()).publishToUser(any(), any());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    /**
     * Only structured SYSTEM messages may travel through this publisher.
     */
    @Test
    void publishMembershipChange_withNonSystemMessage_throwsIllegalArgumentException() {
        systemMessage.setMessageType(MessageType.TEXT);
        assertThatThrownBy(() -> publisher.publishMembershipChange(group, systemMessage, "Sample preview", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("systemMessage must be a SYSTEM message");
    }
}
