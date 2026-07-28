package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
public class SystemMessageService {

    private final MessageService messageService;
    private final MessageResponseMapper messageResponseMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;
    private final GroupRealtimeUpdateService groupRealtimeUpdateService;

    public SystemMessageService(
            MessageService messageService,
            MessageResponseMapper messageResponseMapper,
            SimpMessagingTemplate messagingTemplate,
            CustomRabbitMQBrokerHandler rabbitMQBrokerHandler,
            GroupRealtimeUpdateService groupRealtimeUpdateService) {
        this.messageService = messageService;
        this.messageResponseMapper = messageResponseMapper;
        this.messagingTemplate = messagingTemplate;
        this.rabbitMQBrokerHandler = rabbitMQBrokerHandler;
        this.groupRealtimeUpdateService = groupRealtimeUpdateService;
    }

    @Transactional
    public Message recordGroupEvent(Group group, User subjectUser, User actor, SystemEventType eventType) {
        Message savedMessage = messageService.saveGroupSystemMessage(
                group,
                subjectUser,
                actor,
                eventType,
                buildLatestPreview(eventType));
        scheduleRealtimePublish(savedMessage, subjectUser, eventType);
        return savedMessage;
    }

    private String buildLatestPreview(SystemEventType eventType) {
        return switch (eventType) {
            case USER_JOINED -> "Member joined";
            case USER_LEFT -> "Member left";
            case USER_KICKED -> "Member removed";
            case USER_BANNED -> "Member banned";
            case USER_UNBANNED -> "Member unbanned";
            case USER_PROMOTED -> "Member promoted";
            case USER_DEMOTED -> "Member demoted";
            case LEADERSHIP_TRANSFERRED -> "Leadership transferred";
            case GROUP_NAME_UPDATED -> "Group name updated";
            case GROUP_DESCRIPTION_UPDATED -> "Group description updated";
            case GROUP_ARCHIVED -> "Group archived";
        };
    }

    private void scheduleRealtimePublish(Message savedMessage, User subjectUser, SystemEventType eventType) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishRealtime(savedMessage, subjectUser, eventType);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishRealtime(savedMessage, subjectUser, eventType);
            }
        });
    }

    private void publishRealtime(Message savedMessage, User subjectUser, SystemEventType eventType) {
        MessageResponse response = Objects.requireNonNull(messageResponseMapper.toResponse(savedMessage));
        Long groupId = Objects.requireNonNull(savedMessage.getGroup().getId());
        String destination = "/topic/group." + groupId;
        messagingTemplate.convertAndSend(destination, response);
        rabbitMQBrokerHandler.publishToRabbitMQ(destination, response);
        groupRealtimeUpdateService.publishCurrentMembersSnapshot(groupId);

        if (eventType == SystemEventType.USER_KICKED
                || eventType == SystemEventType.USER_BANNED
                || eventType == SystemEventType.USER_LEFT) {
            groupRealtimeUpdateService.publishRemovedGroup(groupId, subjectUser.getUsername());
        }
    }
}
