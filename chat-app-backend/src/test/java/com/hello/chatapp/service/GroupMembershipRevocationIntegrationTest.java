package com.hello.chatapp.service;

import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.repository.GroupBanRepository;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Database-backed coverage for kick/ban/unban eligibility and lost edit/delete rights.
 */
@DataJpaTest
@Import({
        GroupService.class,
        GroupMembershipService.class,
        GroupAuthorizationService.class,
        MessageService.class,
        SystemMessageService.class,
        MessageModerationService.class,
        GroupMembershipRevocationIntegrationTest.RealtimeStubConfig.class
})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupMembershipRevocationIntegrationTest {

    private static final String BANNED_FROM_GROUP = "You are banned from this group";
    private static final String NOT_A_MEMBER = "You are not a member of this group";

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupMembershipRevocationIntegrationTest.class);
    }

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageModerationService messageModerationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    @Autowired
    private GroupBanRepository groupBanRepository;

    @Autowired
    private MessageRepository messageRepository;

    /**
     * A kicked member may be re-added by a leader and may rejoin through a still-valid join link.
     */
    @Test
    void kickMember_allowsReAddAndRejoinByValidLink() {
        User leader = persistUser("kick-leader");
        User kicked = persistUser("kicked");
        GroupResponse group = groupService.createGroup(
                uniqueName("kick"),
                null,
                leader,
                List.of(kicked.getId()),
                null);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();

        groupMembershipService.kickMember(leader, group.getId(), kicked.getId());
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), kicked.getId())).isEmpty();
        assertThat(groupBanRepository.existsByGroup_IdAndUser_Id(group.getId(), kicked.getId())).isFalse();

        groupMembershipService.addMembers(leader, group.getId(), List.of(kicked.getId()));
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), kicked.getId())).isPresent();

        groupMembershipService.kickMember(leader, group.getId(), kicked.getId());
        groupMembershipService.joinByToken(kicked, token);
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), kicked.getId())).isPresent();
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * A banned user cannot be re-added and cannot join through a still-valid join link.
     */
    @Test
    void banMember_blocksReAddAndJoinByLink() {
        User leader = persistUser("ban-leader");
        User bannedForAdd = persistUser("banned-add");
        User bannedForJoin = persistUser("banned-join");
        GroupResponse group = groupService.createGroup(
                uniqueName("ban"),
                null,
                leader,
                List.of(bannedForAdd.getId(), bannedForJoin.getId()),
                null);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();

        groupMembershipService.banMember(leader, group.getId(), bannedForAdd.getId(), "spam");
        groupMembershipService.banMember(leader, group.getId(), bannedForJoin.getId(), "abuse");

        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), bannedForAdd.getId())).isEmpty();
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), bannedForJoin.getId())).isEmpty();
        assertThat(groupBanRepository.existsByGroup_IdAndUser_Id(group.getId(), bannedForAdd.getId())).isTrue();
        assertThat(groupBanRepository.existsByGroup_IdAndUser_Id(group.getId(), bannedForJoin.getId())).isTrue();

        assertThatThrownBy(() -> groupMembershipService.addMembers(leader, group.getId(), List.of(bannedForAdd.getId())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BANNED_FROM_GROUP);
        assertThatThrownBy(() -> groupMembershipService.joinByToken(bannedForJoin, token))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BANNED_FROM_GROUP);

        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), bannedForAdd.getId())).isEmpty();
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), bannedForJoin.getId())).isEmpty();
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(1);
    }

    /**
     * Unban deletes the ban row so the user can be added again and can join by a valid link.
     */
    @Test
    void unbanMember_restoresAddAndJoinEligibility() {
        User leader = persistUser("unban-leader");
        User restoreAdd = persistUser("restore-add");
        User restoreJoin = persistUser("restore-join");
        GroupResponse group = groupService.createGroup(
                uniqueName("unban"),
                null,
                leader,
                List.of(restoreAdd.getId(), restoreJoin.getId()),
                null);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();

        groupMembershipService.banMember(leader, group.getId(), restoreAdd.getId(), "temp");
        groupMembershipService.banMember(leader, group.getId(), restoreJoin.getId(), "temp");
        groupMembershipService.unbanMember(leader, group.getId(), restoreAdd.getId());
        groupMembershipService.unbanMember(leader, group.getId(), restoreJoin.getId());

        assertThat(groupBanRepository.existsByGroup_IdAndUser_Id(group.getId(), restoreAdd.getId())).isFalse();
        assertThat(groupBanRepository.existsByGroup_IdAndUser_Id(group.getId(), restoreJoin.getId())).isFalse();

        groupMembershipService.addMembers(leader, group.getId(), List.of(restoreAdd.getId()));
        groupMembershipService.joinByToken(restoreJoin, token);

        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), restoreAdd.getId())).isPresent();
        assertThat(groupParticipantRepository.findByGroupIdAndUserId(group.getId(), restoreJoin.getId())).isPresent();
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(3);
    }

    /**
     * Kicked and banned authors cannot edit or delete messages they previously sent in the group.
     */
    @Test
    void kickedAndBannedUsers_cannotEditOrDeleteOwnGroupMessages() {
        User leader = persistUser("mod-leader");
        User kicked = persistUser("mod-kicked");
        User banned = persistUser("mod-banned");
        GroupResponse group = groupService.createGroup(
                uniqueName("moderation"),
                null,
                leader,
                List.of(kicked.getId(), banned.getId()),
                null);
        Group persistedGroup = groupRepository.findById(group.getId()).orElseThrow();

        Message kickedMessage = messageService.saveGroupMessage(persistedGroup, kicked, "kick-me");
        Message bannedMessage = messageService.saveGroupMessage(persistedGroup, banned, "ban-me");

        groupMembershipService.kickMember(leader, group.getId(), kicked.getId());
        groupMembershipService.banMember(leader, group.getId(), banned.getId(), "spam");

        assertThatThrownBy(() -> messageModerationService.editMessage(kicked, kickedMessage.getId(), "edited"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(NOT_A_MEMBER);
        assertThatThrownBy(() -> messageModerationService.deleteMessage(kicked, kickedMessage.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(NOT_A_MEMBER);
        assertThatThrownBy(() -> messageModerationService.editMessage(banned, bannedMessage.getId(), "edited"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(NOT_A_MEMBER);
        assertThatThrownBy(() -> messageModerationService.deleteMessage(banned, bannedMessage.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(NOT_A_MEMBER);

        Message unchangedKicked = messageRepository.findById(kickedMessage.getId()).orElseThrow();
        Message unchangedBanned = messageRepository.findById(bannedMessage.getId()).orElseThrow();
        assertThat(unchangedKicked.getContent()).isEqualTo("kick-me");
        assertThat(unchangedKicked.getUpdatedAt()).isNull();
        assertThat(unchangedKicked.getDeletedAt()).isNull();
        assertThat(unchangedBanned.getContent()).isEqualTo("ban-me");
        assertThat(unchangedBanned.getUpdatedAt()).isNull();
        assertThat(unchangedBanned.getDeletedAt()).isNull();
    }

    /** Persists a unique user for this integration suite. */
    private User persistUser(String suffix) {
        return userRepository.saveAndFlush(new User(
                "revoke-" + suffix + "-" + UUID.randomUUID(),
                "secret",
                "Revoke " + suffix));
    }

    /** Unique group names avoid collisions across dirty or concurrent runs. */
    private String uniqueName(String suffix) {
        return "revoke-" + suffix + "-" + UUID.randomUUID();
    }

    /**
     * Stubs realtime publishers and message mapping so revocation tests do not require STOMP or storage.
     */
    @TestConfiguration
    static class RealtimeStubConfig {

        /** No-op membership publisher for kick/ban/unban events. */
        @Bean
        GroupMembershipRealtimePublisher groupMembershipRealtimePublisher() {
            return mock(GroupMembershipRealtimePublisher.class);
        }

        /** No-op profile publisher for group-service wiring. */
        @Bean
        GroupProfileRealtimePublisher groupProfileRealtimePublisher() {
            return mock(GroupProfileRealtimePublisher.class);
        }

        /** Maps persisted messages without object-storage URLs. */
        @Bean
        MessageResponseMapper messageResponseMapper() {
            MessageResponseMapper mapper = mock(MessageResponseMapper.class);
            when(mapper.toResponse(any(Message.class)))
                    .thenAnswer(invocation -> MessageResponse.fromMessage(invocation.getArgument(0)));
            return mapper;
        }
    }
}
