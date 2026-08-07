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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    }

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
    }

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
    }
}
