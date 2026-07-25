package com.hello.chatapp.service;

import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemMessageService {

    private final MessageService messageService;

    public SystemMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    @Transactional
    public Message recordGroupEvent(Group group, User subjectUser, User actor, SystemEventType eventType) {
        return messageService.saveGroupSystemMessage(
                group,
                subjectUser,
                actor,
                eventType,
                buildLatestPreview(eventType));
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
}
