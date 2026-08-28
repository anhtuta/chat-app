package com.hello.chatapp.interceptor;

import com.hello.chatapp.constant.GroupPermission;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.service.GroupAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for outbound group-topic access revocation checks.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupTopicAccessRevocationInterceptorTest {

    @Mock
    private GroupAuthorizationService groupAuthorizationService;

    @Mock
    private MessageChannel channel;

    private GroupTopicAccessRevocationInterceptor interceptor;
    private User user;

    @BeforeEach
    void setUp() {
        interceptor = new GroupTopicAccessRevocationInterceptor(groupAuthorizationService);
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
    }

    /**
     * Valid group members should still receive outbound group-topic messages.
     */
    @Test
    void preSend_activeMember_keepsMessage() {
        Message<byte[]> message = buildMessage("/topic/group.100", user);

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
        verify(groupAuthorizationService).requirePermission(user, 100L, GroupPermission.READ_MESSAGES);
    }

    /**
     * Removed or banned users should stop receiving outbound group-topic messages immediately.
     */
    @Test
    void preSend_revokedMember_dropsMessage() {
        Message<byte[]> message = buildMessage("/topic/group.100", user);
        when(groupAuthorizationService.requirePermission(user, 100L, GroupPermission.READ_MESSAGES))
                .thenThrow(new ForbiddenException("You are not a member of this group"));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
        verify(groupAuthorizationService).requirePermission(user, 100L, GroupPermission.READ_MESSAGES);
    }

    /**
     * Non-group destinations should pass through untouched.
     */
    @Test
    void preSend_nonGroupDestination_keepsMessage() {
        Message<byte[]> message = buildMessage("/topic/public", user);

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
    }

    private Message<byte[]> buildMessage(String destination, User sessionUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setDestination(destination);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("user", sessionUser);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
