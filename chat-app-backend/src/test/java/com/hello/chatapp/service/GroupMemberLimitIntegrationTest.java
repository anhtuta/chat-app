package com.hello.chatapp.service;

import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.ForbiddenException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Database-backed coverage of group member-limit create/update, capacity inserts, and concurrent joins.
 */
@DataJpaTest
@Import({
        GroupService.class,
        GroupMembershipService.class,
        GroupAuthorizationService.class,
        MessageService.class,
        SystemMessageService.class,
        GroupMemberLimitIntegrationTest.RealtimeStubConfig.class
})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupMemberLimitIntegrationTest {

    private static final String GROUP_MEMBER_LIMIT_REACHED = "Group member limit has been reached";

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupMemberLimitIntegrationTest.class);
    }

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    /**
     * Create with {@code maxMembers} null or {@code 0} stores unlimited and allows inserts past any small roster.
     */
    @Test
    void createGroup_nullAndZeroMaxMembersRemainUnlimited() {
        User nullCreator = persistUser("null-creator");
        User extraForNull = persistUser("null-extra");
        GroupResponse unlimitedNull = groupService.createGroup(
                uniqueName("null-cap"),
                null,
                nullCreator,
                List.of(),
                null);
        groupMembershipService.addMembers(nullCreator, unlimitedNull.getId(), List.of(extraForNull.getId()));

        assertThat(unlimitedNull.getMaxMembers()).isNull();
        assertThat(groupParticipantRepository.countByGroupId(unlimitedNull.getId())).isEqualTo(2);

        User zeroCreator = persistUser("zero-creator");
        User extraForZero = persistUser("zero-extra");
        GroupResponse unlimitedZero = groupService.createGroup(
                uniqueName("zero-cap"),
                null,
                zeroCreator,
                List.of(),
                0);
        groupMembershipService.addMembers(zeroCreator, unlimitedZero.getId(), List.of(extraForZero.getId()));

        assertThat(unlimitedZero.getMaxMembers()).isZero();
        assertThat(groupParticipantRepository.countByGroupId(unlimitedZero.getId())).isEqualTo(2);
    }

    /**
     * Create and update reject {@code maxMembers < 0} before persisting a negative cap.
     */
    @Test
    void createAndUpdate_rejectNegativeMaxMembersBeforePersist() {
        User creator = persistUser("neg-creator");
        String rejectedName = uniqueName("neg-create");

        assertThatThrownBy(() -> groupService.createGroup(rejectedName, null, creator, List.of(), -1))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("maxMembers must not be negative");
        assertThat(groupRepository.findAll()).noneMatch(group -> rejectedName.equals(group.getName()));

        GroupResponse group = groupService.createGroup(uniqueName("neg-update"), null, creator, List.of(), 4);
        assertThatThrownBy(() -> groupService.updateGroupDetails(creator, group.getId(), null, null, -2, true))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("maxMembers must not be negative");
        assertThat(groupRepository.findById(group.getId()).orElseThrow().getMaxMembers()).isEqualTo(4);
    }

    /**
     * Create rejects when distinct creator plus invitees exceed the cap, with no group or participant rows.
     */
    @Test
    void createGroup_initialMembershipAboveLimit_insertsNoPartialRows() {
        User creator = persistUser("over-creator");
        User inviteeA = persistUser("over-a");
        User inviteeB = persistUser("over-b");
        String groupName = uniqueName("over-limit");
        long groupsBefore = groupRepository.count();
        long participantsBefore = groupParticipantRepository.count();

        assertThatThrownBy(() -> groupService.createGroup(
                groupName,
                null,
                creator,
                List.of(inviteeA.getId(), inviteeB.getId()),
                2))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Initial membership exceeds the group member limit");

        assertThat(groupRepository.findAll()).noneMatch(group -> groupName.equals(group.getName()));
        assertThat(groupRepository.count()).isEqualTo(groupsBefore);
        assertThat(groupParticipantRepository.count()).isEqualTo(participantsBefore);
    }

    /**
     * Leader and co-leader can PATCH the cap; a regular member cannot.
     */
    @Test
    void updateGroupDetails_leaderAndCoLeaderSucceed_memberFails() {
        User leader = persistUser("role-leader");
        User coLeader = persistUser("role-co");
        User member = persistUser("role-member");
        GroupResponse group = groupService.createGroup(
                uniqueName("roles"),
                null,
                leader,
                List.of(coLeader.getId(), member.getId()),
                10);

        GroupResponse leaderUpdate = groupService.updateGroupDetails(leader, group.getId(), null, null, 9, true);
        assertThat(leaderUpdate.getMaxMembers()).isEqualTo(9);

        groupMembershipService.updateMemberRole(leader, group.getId(), coLeader.getId(), GroupRole.CO_LEADER);
        GroupResponse coLeaderUpdate = groupService.updateGroupDetails(coLeader, group.getId(), null, null, 8, true);
        assertThat(coLeaderUpdate.getMaxMembers()).isEqualTo(8);

        assertThatThrownBy(() -> groupService.updateGroupDetails(member, group.getId(), null, null, 7, true))
                .isInstanceOf(ForbiddenException.class);
        assertThat(groupRepository.findById(group.getId()).orElseThrow().getMaxMembers()).isEqualTo(8);
    }

    /**
     * PATCH omitted leaves the cap; explicit null and 0 store unlimited; a positive value sets the cap.
     */
    @Test
    void updateGroupDetails_patchPresenceSemantics() {
        User leader = persistUser("patch-leader");
        GroupResponse group = groupService.createGroup(uniqueName("patch"), null, leader, List.of(), 10);

        groupService.updateGroupDetails(leader, group.getId(), "Renamed " + group.getName(), null, null, false);
        assertThat(groupRepository.findById(group.getId()).orElseThrow().getMaxMembers()).isEqualTo(10);

        GroupResponse setPositive = groupService.updateGroupDetails(leader, group.getId(), null, null, 6, true);
        assertThat(setPositive.getMaxMembers()).isEqualTo(6);

        GroupResponse setNull = groupService.updateGroupDetails(leader, group.getId(), null, null, null, true);
        assertThat(setNull.getMaxMembers()).isNull();

        groupService.updateGroupDetails(leader, group.getId(), null, null, 3, true);
        GroupResponse setZero = groupService.updateGroupDetails(leader, group.getId(), null, null, 0, true);
        assertThat(setZero.getMaxMembers()).isZero();
    }

    /**
     * Lowering the cap below the current count keeps members and blocks new inserts until {@code count < maxMembers}.
     */
    @Test
    void loweringMaxMembersBelowCount_keepsMembersAndBlocksInsertsUntilBelowCap() {
        User leader = persistUser("lower-leader");
        User memberA = persistUser("lower-a");
        User memberB = persistUser("lower-b");
        User waiting = persistUser("lower-wait");
        GroupResponse group = groupService.createGroup(
                uniqueName("lower"),
                null,
                leader,
                List.of(memberA.getId(), memberB.getId()),
                10);

        groupService.updateGroupDetails(leader, group.getId(), null, null, 2, true);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(3);

        assertThatThrownBy(() -> groupMembershipService.addMembers(leader, group.getId(), List.of(waiting.getId())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(GROUP_MEMBER_LIMIT_REACHED);

        groupMembershipService.kickMember(leader, group.getId(), memberA.getId());
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
        assertThatThrownBy(() -> groupMembershipService.addMembers(leader, group.getId(), List.of(waiting.getId())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(GROUP_MEMBER_LIMIT_REACHED);

        groupMembershipService.kickMember(leader, group.getId(), memberB.getId());
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(1);
        groupMembershipService.addMembers(leader, group.getId(), List.of(waiting.getId()));
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * Direct add succeeds while {@code count < maxMembers} and fails once the group is full.
     */
    @Test
    void addMembers_succeedsBelowLimitAndFailsAtLimit() {
        User leader = persistUser("add-leader");
        User first = persistUser("add-first");
        User blocked = persistUser("add-blocked");
        GroupResponse group = groupService.createGroup(uniqueName("add"), null, leader, List.of(), 2);

        groupMembershipService.addMembers(leader, group.getId(), List.of(first.getId()));
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);

        assertThatThrownBy(() -> groupMembershipService.addMembers(leader, group.getId(), List.of(blocked.getId())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(GROUP_MEMBER_LIMIT_REACHED);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * Join-by-token succeeds while {@code count < maxMembers} and fails once the group is full.
     */
    @Test
    void joinByToken_succeedsBelowLimitAndFailsAtLimit() {
        User leader = persistUser("join-leader");
        User joiner = persistUser("join-ok");
        User blocked = persistUser("join-blocked");
        GroupResponse group = groupService.createGroup(uniqueName("join"), null, leader, List.of(), 2);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();

        groupMembershipService.joinByToken(joiner, token);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);

        assertThatThrownBy(() -> groupMembershipService.joinByToken(blocked, token))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(GROUP_MEMBER_LIMIT_REACHED);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * An existing member can reuse the join link when the group is full or over-limit.
     */
    @Test
    void joinByToken_existingMemberSucceedsWhenFullOrOverLimit() {
        User leader = persistUser("idem-leader");
        User member = persistUser("idem-member");
        GroupResponse group = groupService.createGroup(
                uniqueName("idem"),
                null,
                leader,
                List.of(member.getId()),
                2);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();

        groupMembershipService.joinByToken(member, token);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);

        groupService.updateGroupDetails(leader, group.getId(), null, null, 1, true);
        groupMembershipService.joinByToken(member, token);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(2);
    }

    /**
     * Parallel join-link requests never persist more than {@code maxMembers} participants.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void joinByToken_concurrentJoinsNeverExceedMaxMembers() throws Exception {
        User leader = persistUser("cj-leader");
        GroupResponse group = groupService.createGroup(uniqueName("cj"), null, leader, List.of(), 5);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();
        List<User> joiners = persistUsers("cj-join", 10);

        int successes = runConcurrentAttempts(joiners.size(), index -> {
            User joiner = userRepository.findById(joiners.get(index).getId()).orElseThrow();
            return tryJoin(joiner, token);
        });

        assertThat(successes).isEqualTo(4);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(5);
    }

    /**
     * Mixed parallel adds and join-link joins serialize on the group lock and never exceed the cap.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mixedAddAndJoin_concurrentInsertsNeverExceedMaxMembers() throws Exception {
        User leader = persistUser("mix-leader");
        GroupResponse group = groupService.createGroup(uniqueName("mix"), null, leader, List.of(), 4);
        String token = groupMembershipService.createJoinLink(leader, group.getId(), null).getToken();
        List<User> addTargets = persistUsers("mix-add", 4);
        List<User> joiners = persistUsers("mix-join", 4);
        Long leaderId = leader.getId();
        Long groupId = group.getId();

        int workerCount = addTargets.size() + joiners.size();
        int successes = runConcurrentAttempts(workerCount, index -> {
            if (index < addTargets.size()) {
                User actor = userRepository.findById(leaderId).orElseThrow();
                Long targetId = addTargets.get(index).getId();
                return tryAdd(actor, groupId, targetId);
            }
            User joiner = userRepository.findById(joiners.get(index - addTargets.size()).getId()).orElseThrow();
            return tryJoin(joiner, token);
        });

        assertThat(successes).isEqualTo(3);
        assertThat(groupParticipantRepository.countByGroupId(group.getId())).isEqualTo(4);
    }

    /** Persists a unique user for this test class. */
    private User persistUser(String suffix) {
        return userRepository.saveAndFlush(new User("limit-" + suffix + "-" + UUID.randomUUID(), "secret", "Limit " + suffix));
    }

    /** Persists {@code count} unique users with a shared name prefix. */
    private List<User> persistUsers(String suffix, int count) {
        List<User> users = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            users.add(persistUser(suffix + "-" + index));
        }
        return users;
    }

    /** Unique group name so leftover committed rows from concurrent tests do not collide. */
    private String uniqueName(String suffix) {
        return "limit-" + suffix + "-" + UUID.randomUUID();
    }

    /** Joins via token; full-group rejection is a failed attempt, any other error fails the test. */
    private boolean tryJoin(User user, String token) {
        try {
            groupMembershipService.joinByToken(user, token);
            return true;
        } catch (BadRequestException exception) {
            assertThat(exception.getMessage()).isEqualTo(GROUP_MEMBER_LIMIT_REACHED);
            return false;
        }
    }

    /** Adds one member; full-group rejection is a failed attempt, any other error fails the test. */
    private boolean tryAdd(User actor, Long groupId, Long userId) {
        try {
            groupMembershipService.addMembers(actor, groupId, List.of(userId));
            return true;
        } catch (BadRequestException exception) {
            assertThat(exception.getMessage()).isEqualTo(GROUP_MEMBER_LIMIT_REACHED);
            return false;
        }
    }

    /** Runs {@code workerCount} attempts after a shared start latch and returns how many succeeded. */
    private int runConcurrentAttempts(int workerCount, AttemptFactory attemptFactory) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(workerCount);
        try {
            for (int index = 0; index < workerCount; index++) {
                final int workerIndex = index;
                futures.add(executorService.submit(attemptTask(ready, start, () -> attemptFactory.run(workerIndex))));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AtomicInteger successes = new AtomicInteger();
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                    successes.incrementAndGet();
                }
            }
            return successes.get();
        } finally {
            executorService.shutdown();
            assertThat(executorService.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Waits for all workers, then runs one membership attempt. */
    private Callable<Boolean> attemptTask(CountDownLatch ready, CountDownLatch start, Attempt attempt) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return attempt.run();
        };
    }

    /**
     * Stubs STOMP fan-out so membership ITs do not need a realtime broker.
     */
    @TestConfiguration
    static class RealtimeStubConfig {

        /** No-op publisher so AfterCommit membership events do not require STOMP beans. */
        @Bean
        GroupMembershipRealtimePublisher groupMembershipRealtimePublisher() {
            return mock(GroupMembershipRealtimePublisher.class);
        }

        /** No-op profile publisher so group-service tests do not require STOMP beans. */
        @Bean
        GroupProfileRealtimePublisher groupProfileRealtimePublisher() {
            return mock(GroupProfileRealtimePublisher.class);
        }
    }

    /**
     * One concurrent membership attempt.
     */
    @FunctionalInterface
    private interface Attempt {
        boolean run() throws Exception;
    }

    /**
     * Builds a membership attempt for a worker index.
     */
    @FunctionalInterface
    private interface AttemptFactory {
        boolean run(int index) throws Exception;
    }
}
