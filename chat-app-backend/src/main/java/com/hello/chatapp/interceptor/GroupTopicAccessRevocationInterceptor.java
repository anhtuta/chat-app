package com.hello.chatapp.interceptor;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.exception.NotFoundException;
import com.hello.chatapp.service.GroupAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Re-checks group membership on outbound group-topic deliveries so removed or banned users stop
 * receiving useful realtime messages even if their old STOMP subscription is still open.
 */
@Component
public class GroupTopicAccessRevocationInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(GroupTopicAccessRevocationInterceptor.class);
    private static final String GROUP_TOPIC_PREFIX = "/topic/group.";

    private final GroupAuthorizationService groupAuthorizationService;

    public GroupTopicAccessRevocationInterceptor(GroupAuthorizationService groupAuthorizationService) {
        this.groupAuthorizationService = groupAuthorizationService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.MESSAGE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(GROUP_TOPIC_PREFIX)) {
            return message;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return message;
        }

        User user = (User) sessionAttributes.get("user");
        if (user == null) {
            return message;
        }

        try {
            Long groupId = Long.parseLong(destination.substring(GROUP_TOPIC_PREFIX.length()));
            groupAuthorizationService.requirePermission(user, groupId, GroupPermission.READ_MESSAGES);
            return message;
        } catch (NumberFormatException e) {
            logger.warn("Skipping outbound group access validation for malformed destination={}", destination, e);
            return message;
        } catch (ForbiddenException | NotFoundException e) {
            logger.info("Dropping outbound group message for revoked user={}, destination={}, reason={}",
                    user.getUsername(), destination, e.getMessage());
            return null;
        }
    }
}
