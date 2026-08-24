package com.hello.chatapp.service;

import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes structured {@code SYSTEM} messages for membership and group-profile events.
 */
@Service
public class SystemMessageService {

    private final MessageService messageService;

    public SystemMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Persists a structured {@code SYSTEM} message for a group membership or profile event.
     *
     * <p>Two user params are required because the person the event is about is not always
     * the person who performed the action. When the subject acts on themselves (leave,
     * self-join via token, rename group), pass the same user for both.
     *
     * <p>Call-site patterns:
     * <ul>
     *   <li>Kick / ban / unban / promote / demote / add-member: {@code (group, target, actor, ...)}</li>
     *   <li>Leadership transfer: {@code (group, newLeader, currentLeader, ...)}</li>
     *   <li>Leave / self-join / group name|description update / archive: {@code (group, actor, actor, ...)}</li>
     * </ul>
     *
     * @param group the group where the event occurred
     * @param subjectUser the user the event is about (stored as {@code messages.user_id});
     *        e.g. the member who was kicked, banned, promoted, or joined
     * @param actor the user who performed the action (stored as {@code messages.updated_by},
     *        exposed to clients as {@code systemEventActor}); e.g. the moderator who kicked someone
     * @param eventType stable system event enum stored in {@code messages.content}
     * @return the persisted system message
     */
    @Transactional
    public Message recordGroupEvent(Group group, User subjectUser, User actor, SystemEventType eventType) {
        return recordGroupEvent(group, subjectUser, actor, eventType, null);
    }

    /**
     * Same as {@link #recordGroupEvent(Group, User, User, SystemEventType)} with optional extra
     * subject display names stored on {@code system_event_payload.subjectNames}.
     */
    @Transactional
    public Message recordGroupEvent(
            Group group,
            User subjectUser,
            User actor,
            SystemEventType eventType,
            List<String> subjectNames) {
        return messageService.saveGroupSystemMessage(
                group,
                subjectUser,
                actor,
                eventType,
                subjectNames);
    }
}
