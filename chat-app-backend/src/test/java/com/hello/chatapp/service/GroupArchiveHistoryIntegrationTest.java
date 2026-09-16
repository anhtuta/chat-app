package com.hello.chatapp.service;

import com.hello.chatapp.config.MediaStorageConfig;
import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.SystemEventType;
import com.hello.chatapp.dto.GroupResponse;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.MessageResponseMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.UserRepository;
import com.hello.chatapp.storage.ObjectStorageProviderRegistry;
import com.hello.chatapp.storage.S3ObjectStorageProvider;
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
import static org.mockito.Mockito.mock;

/**
 * Database-backed coverage for last-member archive and persisted group history.
 */
@DataJpaTest
@Import({
        GroupService.class,
        GroupMembershipService.class,
        GroupAuthorizationService.class,
        MessageService.class,
        SystemMessageService.class,
        MessageHistoryService.class,
        MessageResponseMapper.class,
        MediaStorageConfig.class,
        ObjectStorageProviderRegistry.class,
        S3ObjectStorageProvider.class,
        GroupArchiveHistoryIntegrationTest.RealtimeStubConfig.class
})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupArchiveHistoryIntegrationTest {

    private static final String LAST_MEMBER_LEFT = "LAST_MEMBER_LEFT";

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupArchiveHistoryIntegrationTest.class);
    }

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageHistoryService messageHistoryService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    /**
     * When the last remaining member leaves, the group is archived and the sidebar no longer lists it.
     */
    @Test
    @SuppressWarnings("null")
    void leaveGroup_lastMemberArchivesGroup() {
        User leader = persistUser("solo-leader");
        GroupResponse created = groupService.createGroup(uniqueName("solo"), null, leader, List.of(), null);

        groupMembershipService.leaveGroup(leader, created.getId());

        Group archived = groupRepository.findById(created.getId()).orElseThrow();
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getArchivedBy().getId()).isEqualTo(leader.getId());
        assertThat(archived.getArchiveReason()).isEqualTo(LAST_MEMBER_LEFT);
        assertThat(groupParticipantRepository.countByGroupId(created.getId())).isZero();
        assertThat(groupService.getUserGroups(leader)).isEmpty();
    }

    /**
     * After archive, prior chat and structured SYSTEM events remain queryable in history.
     */
    @Test
    @SuppressWarnings("null")
    void getGroupMessages_afterArchive_keepsChatAndStructuredSystemEvents() {
        User originalLeader = persistUser("hist-leader");
        User member = persistUser("hist-member");
        GroupResponse created = groupService.createGroup(uniqueName("hist"), null, originalLeader, List.of(), null);

        groupMembershipService.addMembers(originalLeader, created.getId(), List.of(member.getId()));
        groupMembershipService.updateMemberRole(originalLeader, created.getId(), member.getId(), GroupRole.ELDER);
        groupService.updateGroupDetails(originalLeader, created.getId(), uniqueName("renamed"), "Updated bio", null, false);

        Group group = groupRepository.findById(created.getId()).orElseThrow();
        messageService.saveGroupMessage(group, originalLeader, "keep-me");

        groupMembershipService.transferLeadership(originalLeader, created.getId(), member.getId());
        groupMembershipService.leaveGroup(originalLeader, created.getId());
        groupMembershipService.leaveGroup(member, created.getId());

        Group archived = groupRepository.findById(created.getId()).orElseThrow();
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getArchiveReason()).isEqualTo(LAST_MEMBER_LEFT);

        List<MessageResponse> history = messageHistoryService.getGroupMessages(archived, null, null, 100);
        assertThat(history)
                .extracting(MessageResponse::getContent)
                .contains("keep-me");
        assertThat(history)
                .filteredOn(response -> response.getMessageType() == MessageType.SYSTEM)
                .extracting(MessageResponse::getSystemEventType)
                .contains(
                        SystemEventType.USER_JOINED,
                        SystemEventType.USER_PROMOTED,
                        SystemEventType.LEADERSHIP_TRANSFERRED,
                        SystemEventType.GROUP_NAME_UPDATED,
                        SystemEventType.GROUP_DESCRIPTION_UPDATED,
                        SystemEventType.USER_LEFT,
                        SystemEventType.GROUP_ARCHIVED);
        assertThat(history)
                .filteredOn(response -> response.getSystemEventType() == SystemEventType.GROUP_ARCHIVED)
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.getUser().getId()).isEqualTo(member.getId());
                    assertThat(response.getSystemEventActor().getId()).isEqualTo(member.getId());
                });
    }

    /** Persists a unique user for this integration suite. */
    private User persistUser(String suffix) {
        return userRepository.saveAndFlush(new User(
                "archive-" + suffix + "-" + UUID.randomUUID(),
                "secret",
                "Archive " + suffix));
    }

    /** Unique group names avoid collisions across dirty or concurrent runs. */
    private String uniqueName(String suffix) {
        return "archive-" + suffix + "-" + UUID.randomUUID();
    }

    /**
     * Stubs realtime publishers so archive/history tests do not require STOMP beans.
     */
    @TestConfiguration
    static class RealtimeStubConfig {

        /** No-op membership publisher for leave/archive events. */
        @Bean
        GroupMembershipRealtimePublisher groupMembershipRealtimePublisher() {
            return mock(GroupMembershipRealtimePublisher.class);
        }

        /** No-op profile publisher for name/description updates. */
        @Bean
        GroupProfileRealtimePublisher groupProfileRealtimePublisher() {
            return mock(GroupProfileRealtimePublisher.class);
        }
    }
}
