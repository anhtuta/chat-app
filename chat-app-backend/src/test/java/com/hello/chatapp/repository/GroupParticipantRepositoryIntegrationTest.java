package com.hello.chatapp.repository;

import com.hello.chatapp.constant.GroupRole;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.support.IsolatedH2DataSourceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for group participant queries and bulk member insert.
 */
@DataJpaTest
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupParticipantRepositoryIntegrationTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupParticipantRepositoryIntegrationTest.class);
    }

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Archived groups are omitted from a user's participant list.
     */
    @Test
    void findByUser_excludesArchivedGroups() {
        User creator = userRepository.saveAndFlush(new User("creator-user", "secret", "Creator User"));
        User member = userRepository.saveAndFlush(new User("member-user", "secret", "Member User"));

        Group activeGroup = groupRepository.saveAndFlush(new Group("Active Group", creator));
        Group archivedGroup = groupRepository.saveAndFlush(new Group("Archived Group", creator));
        archivedGroup.setArchivedAt(LocalDateTime.now());
        groupRepository.saveAndFlush(archivedGroup);

        groupParticipantRepository.saveAndFlush(new GroupParticipant(activeGroup, member));
        groupParticipantRepository.saveAndFlush(new GroupParticipant(archivedGroup, member));

        List<GroupParticipant> participants = groupParticipantRepository.findByUser(member);

        assertThat(participants)
                .extracting(participant -> participant.getGroup().getName())
                .containsExactly("Active Group");
    }

    /**
     * Multi-row insert creates every MEMBER in one statement and is readable afterward.
     */
    @Test
    void insertMembers_insertsAllUsersAsMembersInOneStatement() {
        User creator = userRepository.saveAndFlush(new User("creator-bulk", "secret", "Creator"));
        User bob = userRepository.saveAndFlush(new User("bob-bulk", "secret", "Bob"));
        User carol = userRepository.saveAndFlush(new User("carol-bulk", "secret", "Carol"));
        Group group = groupRepository.saveAndFlush(new Group("Bulk Group", creator));
        LocalDateTime joinedAt = LocalDateTime.now().minusMinutes(1);

        int inserted = groupParticipantRepository.insertMembers(
                group.getId(),
                List.of(bob.getId(), carol.getId()),
                joinedAt);

        assertThat(inserted).isEqualTo(2);
        List<GroupParticipant> saved = groupParticipantRepository.findByGroupIdAndUserIdIn(
                group.getId(),
                List.of(bob.getId(), carol.getId()));
        assertThat(saved).hasSize(2);
        assertThat(saved)
                .allMatch(participant -> participant.getRole() == GroupRole.MEMBER)
                .extracting(participant -> participant.getUser().getUsername())
                .containsExactlyInAnyOrder("bob-bulk", "carol-bulk");
    }
}
