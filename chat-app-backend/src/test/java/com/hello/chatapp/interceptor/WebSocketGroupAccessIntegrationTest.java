package com.hello.chatapp.interceptor;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.controller.WebSocketController;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.MessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.service.GroupAuthorizationService;
import com.hello.chatapp.service.GroupMembershipRealtimePublisher;
import com.hello.chatapp.service.GroupMembershipService;
import com.hello.chatapp.service.GroupProfileRealtimePublisher;
import com.hello.chatapp.service.GroupService;
import com.hello.chatapp.service.GroupSummaryUpdatePublisher;
import com.hello.chatapp.service.MessageService;
import com.hello.chatapp.service.RealtimeMessageDeliveryService;
import com.hello.chatapp.service.SystemMessageService;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Database-backed smoke coverage for inbound STOMP subscribe/send authorization gates.
 */
@SuppressWarnings("null")
@DataJpaTest
@Import({
        GroupService.class,
        GroupMembershipService.class,
        GroupAuthorizationService.class,
        MessageService.class,
        SystemMessageService.class,
        WebSocketSecurityChannelInterceptor.class,
        WebSocketController.class,
        WebSocketGroupAccessIntegrationTest.BrokerStubConfig.class
})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebSocketGroupAccessIntegrationTest {

    private static final String BANNED_FROM_GROUP = "You are banned from this group";
    private static final String NOT_A_MEMBER = "You are not a member of this group";
    private static final String OWN_USER_TOPIC = "You can only subscribe to your own user topic";

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, WebSocketGroupAccessIntegrationTest.class);
    }

    @Autowired
    private WebSocketSecurityChannelInterceptor securityChannelInterceptor;

    @Autowired
    private WebSocketController webSocketController;

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RealtimeMessageDeliveryService realtimeMessageDeliveryService;

    private final MessageChannel inboundChannel = mock(MessageChannel.class);

    /**
     * Group members may subscribe to {@code /topic/group.{id}}.
     */
    @Test
    void subscribe_member_isAllowed() {
        User leader = persistUser("sub-leader");
        User member = persistUser("sub-member");
        GroupResponse group = createGroup(leader, member);

        Message<byte[]> subscribe = subscribeFrame(member, "/topic/group." + group.getId());

        assertThat(securityChannelInterceptor.preSend(subscribe, inboundChannel)).isSameAs(subscribe);
    }

    /**
     * Outsiders and banned users cannot subscribe to the group topic.
     */
    @Test
    void subscribe_nonMemberAndBanned_isRejected() {
        User leader = persistUser("deny-leader");
        User member = persistUser("deny-member");
        User outsider = persistUser("deny-outsider");
        GroupResponse group = createGroup(leader, member);
        String destination = "/topic/group." + group.getId();

        groupMembershipService.banMember(leader, group.getId(), member.getId(), "spam");

        assertThatThrownBy(() -> securityChannelInterceptor.preSend(subscribeFrame(outsider, destination), inboundChannel))
                .isInstanceOf(SecurityException.class)
                .hasMessage(NOT_A_MEMBER);
        assertThatThrownBy(() -> securityChannelInterceptor.preSend(subscribeFrame(member, destination), inboundChannel))
                .isInstanceOf(SecurityException.class)
                .hasMessage(BANNED_FROM_GROUP);
    }

    /**
     * Active members may send group chat through the STOMP {@code /group.send} mapping.
     */
    @Test
    void send_member_isAllowed() {
        User leader = persistUser("send-leader");
        User member = persistUser("send-member");
        GroupResponse group = createGroup(leader, member);

        MessageResponse response = webSocketController.sendGroupMessage(
                new MessageRequest("hello from member", group.getId()),
                sessionAccessor(member));

        assertThat(response.getContent()).isEqualTo("hello from member");
        assertThat(response.getGroupId()).isEqualTo(group.getId());
        assertThat(messageRepository.count()).isGreaterThan(0);
        verify(realtimeMessageDeliveryService).publish("/topic/group." + group.getId(), response);
    }

    /**
     * Kicked and banned users cannot send group chat even if they still hold a STOMP session.
     */
    @Test
    void send_afterKickAndBan_isRejected() {
        User leader = persistUser("rev-leader");
        User kicked = persistUser("rev-kicked");
        User banned = persistUser("rev-banned");
        GroupResponse group = createGroup(leader, kicked, banned);

        groupMembershipService.kickMember(leader, group.getId(), kicked.getId());
        groupMembershipService.banMember(leader, group.getId(), banned.getId(), "spam");

        assertThatThrownBy(() -> webSocketController.sendGroupMessage(
                new MessageRequest("after kick", group.getId()),
                sessionAccessor(kicked)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(NOT_A_MEMBER);
        assertThatThrownBy(() -> webSocketController.sendGroupMessage(
                new MessageRequest("after ban", group.getId()),
                sessionAccessor(banned)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BANNED_FROM_GROUP);
    }

    /**
     * Personal group-update topics are only valid for the authenticated username.
     */
    @Test
    void subscribe_personalTopic_allowsOwnUsernameAndRejectsOthers() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");

        Message<byte[]> ownTopic = subscribeFrame(alice, "/topic/user." + alice.getUsername() + ".group-updates");
        assertThat(securityChannelInterceptor.preSend(ownTopic, inboundChannel)).isSameAs(ownTopic);

        assertThatThrownBy(() -> securityChannelInterceptor.preSend(
                subscribeFrame(alice, "/topic/user." + bob.getUsername() + ".group-updates"),
                inboundChannel))
                .isInstanceOf(SecurityException.class)
                .hasMessage(OWN_USER_TOPIC);
    }

    /** Persists a unique user for this integration suite. */
    private User persistUser(String suffix) {
        return userRepository.saveAndFlush(new User(
                "ws-" + suffix + "-" + UUID.randomUUID(),
                "secret",
                "Ws " + suffix));
    }

    /** Unique group names avoid collisions across dirty runs. */
    private String uniqueName(String suffix) {
        return "ws-" + suffix + "-" + UUID.randomUUID();
    }

    /** Creates a group with the given extra members invited at create time. */
    private GroupResponse createGroup(User leader, User... members) {
        List<Long> memberIds = java.util.Arrays.stream(members).map(User::getId).toList();
        return groupService.createGroup(uniqueName("group"), null, leader, memberIds, null);
    }

    /** Builds an inbound STOMP SUBSCRIBE frame with the user stored in session attributes. */
    private Message<byte[]> subscribeFrame(User user, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(sessionAttributes(user));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** Session accessor used by {@code @MessageMapping} group send. */
    private SimpMessageHeaderAccessor sessionAccessor(User user) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionAttributes(sessionAttributes(user));
        return accessor;
    }

    /** Handshake-style session map used by the inbound interceptor and controller. */
    private Map<String, Object> sessionAttributes(User user) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("user", user);
        return attributes;
    }

    /**
     * Stubs broker and realtime collaborators so these smoke tests do not need STOMP or RabbitMQ.
     */
    @TestConfiguration
    static class BrokerStubConfig {

        /** No-op membership publisher for kick/ban side effects. */
        @Bean
        GroupMembershipRealtimePublisher groupMembershipRealtimePublisher() {
            return mock(GroupMembershipRealtimePublisher.class);
        }

        /** No-op profile publisher for group-service wiring. */
        @Bean
        GroupProfileRealtimePublisher groupProfileRealtimePublisher() {
            return mock(GroupProfileRealtimePublisher.class);
        }

        /** CONNECT join notices are unused in this suite. */
        @Bean
        SimpMessageSendingOperations messagingTemplate() {
            return mock(SimpMessageSendingOperations.class);
        }

        /** RabbitMQ fan-out is unused in this suite. */
        @Bean
        CustomRabbitMQBrokerHandler customRabbitMQBrokerHandler() {
            return mock(CustomRabbitMQBrokerHandler.class);
        }

        /** Dual local+Rabbit publish used by group send. */
        @Bean
        RealtimeMessageDeliveryService realtimeMessageDeliveryService() {
            return mock(RealtimeMessageDeliveryService.class);
        }

        /** Sidebar fan-out after a successful group send. */
        @Bean
        GroupSummaryUpdatePublisher groupSummaryUpdatePublisher() {
            return mock(GroupSummaryUpdatePublisher.class);
        }
    }
}
