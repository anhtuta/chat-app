package com.hello.chatapp.service;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.MessageEditHistoryRepository;
import com.hello.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessageModerationService} edit/delete, group lock-before-auth, and optimistic-lock propagation.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class MessageModerationServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageEditHistoryRepository messageEditHistoryRepository;

    @Mock
    private GroupAuthorizationService groupAuthorizationService;

    @Mock
    private GroupMembershipService groupMembershipService;

    @Mock
    private MessageResponseMapper messageResponseMapper;

    @Mock
    private MessageService messageService;

    @InjectMocks
    private MessageModerationService messageModerationService;

    private User actor;
    private Group group;
    private Message message;

    @BeforeEach
    void setUp() {
        actor = new User();
        actor.setId(1L);
        actor.setUsername("alice");

        group = new Group();
        group.setId(100L);

        message = new Message();
        message.setId(10L);
        message.setUser(actor);
        message.setGroup(group);
        message.setContent("hello");
        message.setTimestamp(LocalDateTime.now().minusMinutes(1));

        lenient().when(groupMembershipService.lockActiveGroup(100L)).thenReturn(group);
    }

    /**
     * Saves history and content, then refreshes the group latest-message preview when needed.
     */
    @Test
    void editMessage_savesHistoryAndRefreshesGroupSummary() {
        MessageResponse response = MessageResponse.builder().id(10L).content("updated").build();

        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);
        when(messageResponseMapper.toResponse(message)).thenReturn(response);

        MessageResponse result = messageModerationService.editMessage(actor, 10L, " updated ");

        ArgumentCaptor<com.hello.chatapp.entity.MessageEditHistory> historyCaptor =
                ArgumentCaptor.forClass(com.hello.chatapp.entity.MessageEditHistory.class);
        verify(messageEditHistoryRepository).save(historyCaptor.capture());

        assertThat(historyCaptor.getValue().getMessage()).isSameAs(message);
        assertThat(historyCaptor.getValue().getOldContent()).isEqualTo("hello");
        assertThat(historyCaptor.getValue().getUpdatedBy()).isSameAs(actor);
        assertThat(message.getContent()).isEqualTo("updated");
        assertThat(message.getUpdatedBy()).isSameAs(actor);
        assertThat(message.getUpdatedAt()).isNotNull();
        verify(messageService).refreshGroupLatestMessage(100L, 10L);
        assertThat(result).isSameAs(response);
        var inOrder = inOrder(groupMembershipService, groupAuthorizationService);
        inOrder.verify(groupMembershipService).lockActiveGroup(100L);
        inOrder.verify(groupAuthorizationService).requireCanEditMessage(actor, message);
    }

    /**
     * Stale {@code @Version} on save must propagate so {@link com.hello.chatapp.exception.GlobalExceptionHandler}
     * can map it to HTTP 409 (the whole TX including edit history rolls back).
     */
    @Test
    void editMessage_propagatesOptimisticLockFailure() {
        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message))
                .thenThrow(new ObjectOptimisticLockingFailureException(Message.class, 10L));

        assertThatThrownBy(() -> messageModerationService.editMessage(actor, 10L, "updated"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * Concurrent delete of a stale snapshot must fail the same way as a stale edit.
     */
    @Test
    void deleteMessage_propagatesOptimisticLockFailure() {
        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message))
                .thenThrow(new ObjectOptimisticLockingFailureException(Message.class, 10L));

        assertThatThrownBy(() -> messageModerationService.deleteMessage(actor, 10L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * Soft-delete sets deleted metadata and refreshes the group preview.
     */
    @Test
    void deleteMessage_setsDeletedMetadataAndRefreshesGroupSummary() {
        MessageResponse response = MessageResponse.builder().id(10L).deletedAt(LocalDateTime.now()).build();

        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);
        when(messageResponseMapper.toResponse(message)).thenReturn(response);

        MessageResponse result = messageModerationService.deleteMessage(actor, 10L);

        assertThat(message.getDeletedBy()).isSameAs(actor);
        assertThat(message.getDeletedAt()).isNotNull();
        verify(messageService).refreshGroupLatestMessage(100L, 10L);
        assertThat(result).isSameAs(response);
        var inOrder = inOrder(groupMembershipService, groupAuthorizationService);
        inOrder.verify(groupMembershipService).lockActiveGroup(100L);
        inOrder.verify(groupAuthorizationService).requireCanDeleteMessage(actor, message);
    }

    /**
     * Public messages have no group row — do not take the membership lifecycle lock.
     */
    @Test
    void editMessage_publicMessage_skipsGroupLock() {
        message.setGroup(null);
        MessageResponse response = MessageResponse.builder().id(10L).content("updated").build();

        when(messageRepository.findWithMediaById(10L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);
        when(messageResponseMapper.toResponse(message)).thenReturn(response);

        messageModerationService.editMessage(actor, 10L, "updated");

        verify(groupMembershipService, never()).lockActiveGroup(anyLong());
        verify(groupAuthorizationService).requireCanEditMessage(actor, message);
    }
}
