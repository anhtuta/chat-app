package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.dto.GroupMemberResponse;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Database-backed coverage for persisted group roles and leadership transfer invariants.
 */
@DataJpaTest
@Import({
        GroupService.class,
        GroupMembershipService.class,
        GroupAuthorizationService.class,
        MessageService.class,
        SystemMessageService.class,
        GroupRoleLifecycleIntegrationTest.RealtimeStubConfig.class
})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupRoleLifecycleIntegrationTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupRoleLifecycleIntegrationTest.class);
    }

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    /**
     * Group creation should persist the creator as leader and invited participants as members.
     */
    @Test
    void createGroup_persistsCreatorAsLeaderAndInviteesAsMembers() {
        User creator = persistUser("creator");
        User inviteeA = persistUser("invitee-a");
        User inviteeB = persistUser("invitee-b");

        GroupResponse group = groupService.createGroup(
                uniqueName("create"),
                null,
                creator,
                List.of(inviteeA.getId(), inviteeB.getId()),
                null);

        assertThat(group.getCurrentUserRole()).isEqualTo(GroupRole.LEADER);
        assertParticipantRole(group.getId(), creator.getId(), GroupRole.LEADER);
        assertParticipantRole(group.getId(), inviteeA.getId(), GroupRole.MEMBER);
        assertParticipantRole(group.getId(), inviteeB.getId(), GroupRole.MEMBER);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(3);
    }

    /**
     * A join-link joiner should persist as member and the returned DTO should expose that role.
     */
    @Test
    void joinByToken_persistsJoinerAsMember() {
        User leader = persistUser("join-leader");
        User joiner = persistUser("joiner");
        GroupResponse group = groupService.createGroup(uniqueName("join"), null, leader, List.of(), null);

        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();
        GroupMemberResponse joined = groupMembershipService.joinByToken(joiner, token);

        assertThat(joined.getRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(joined.getGroupId()).isEqualTo(group.getId());
        assertParticipantRole(group.getId(), joiner.getId(), GroupRole.MEMBER);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * Leadership can move to any current member role while preserving exactly one leader in the roster.
     */
    @Test
    void transferLeadership_toAnyCurrentMember_keepsExactlyOneLeader() {
        User originalLeader = persistUser("leader");
        User plainMember = persistUser("member");
        User elder = persistUser("elder");
        User coLeader = persistUser("co-leader");
        GroupResponse group = groupService.createGroup(
                uniqueName("transfer"),
                null,
                originalLeader,
                List.of(plainMember.getId(), elder.getId(), coLeader.getId()),
                null);

        groupMembershipService.updateMemberRole(originalLeader, group.getId(), elder.getId(), GroupRole.ELDER);
        groupMembershipService.updateMemberRole(originalLeader, group.getId(), coLeader.getId(), GroupRole.CO_LEADER);

        groupMembershipService.transferLeadership(originalLeader, group.getId(), plainMember.getId());
        assertSingleLeader(group.getId(), plainMember.getId());
        assertParticipantRole(group.getId(), originalLeader.getId(), GroupRole.MEMBER);

        groupMembershipService.transferLeadership(plainMember, group.getId(), elder.getId());
        assertSingleLeader(group.getId(), elder.getId());
        assertParticipantRole(group.getId(), plainMember.getId(), GroupRole.MEMBER);

        groupMembershipService.transferLeadership(elder, group.getId(), coLeader.getId());
        assertSingleLeader(group.getId(), coLeader.getId());
        assertParticipantRole(group.getId(), elder.getId(), GroupRole.MEMBER);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(4);
    }

    /** Persists a unique user for this integration suite. */
    private User persistUser(String suffix) {
        return userRepository.saveAndFlush(new User("roles-" + suffix + "-" + UUID.randomUUID(), "secret", "Roles " + suffix));
    }

    /** Unique group names avoid collisions across dirty or concurrent runs. */
    private String uniqueName(String suffix) {
        return "roles-" + suffix + "-" + UUID.randomUUID();
    }

    /** Loads one participant row and asserts its persisted role. */
    private void assertParticipantRole(Long groupId, Long userId, GroupRole expectedRole) {
        GroupParticipant participant = groupParticipantRepository.findByGroupIdAndUserId(groupId, userId).orElseThrow();
        assertThat(participant.getRole()).isEqualTo(expectedRole);
    }

    /** Confirms only one leader exists in the roster and it belongs to the expected user. */
    private void assertSingleLeader(Long groupId, Long expectedLeaderUserId) {
        List<GroupParticipant> participants = groupParticipantRepository.findByGroupIdWithUser(
                groupId,
                null,
                PageRequest.of(0, 100))
                .getContent();
        assertThat(participants).hasSize((int) groupParticipantRepository.countByGroupId(groupId));
        assertThat(participants)
                .filteredOn(participant -> participant.getRole() == GroupRole.LEADER)
                .singleElement()
                .satisfies(participant -> assertThat(participant.getUser().getId()).isEqualTo(expectedLeaderUserId));
    }

    /**
     * Stubs realtime publishers so role lifecycle integration tests do not require STOMP beans.
     */
    @TestConfiguration
    static class RealtimeStubConfig {

        /** No-op membership publisher for role/membership events. */
        @Bean
        GroupMembershipRealtimePublisher groupMembershipRealtimePublisher() {
            return mock(GroupMembershipRealtimePublisher.class);
        }

        /** No-op profile publisher for group-service profile updates. */
        @Bean
        GroupProfileRealtimePublisher groupProfileRealtimePublisher() {
            return mock(GroupProfileRealtimePublisher.class);
        }
    }
}
