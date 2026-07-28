package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hello.chatapp.support.IsolatedH2DataSourceSupport;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({GroupService.class, GroupAuthorizationService.class, MessageService.class})
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroupServiceIntegrationTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupServiceIntegrationTest.class);
    }

    @Autowired
    private GroupService groupService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupParticipantRepository groupParticipantRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private SystemMessageService systemMessageService;

    @Test
    void getUnreadCountByGroupId_andTotalUnread_areCorrect_andIncludeZeroForGroupsWithoutMessages() {
        User targetUser = userRepository.saveAndFlush(new User("target-user", "secret", "Target User"));
        User senderUser = userRepository.saveAndFlush(new User("sender-user", "secret", "Sender User"));

        Group groupA = groupRepository.saveAndFlush(new Group("Group A", senderUser));
        Group groupB = groupRepository.saveAndFlush(new Group("Group B", senderUser));
        Group groupC = groupRepository.saveAndFlush(new Group("Group C", senderUser));

        GroupParticipant participantA = groupParticipantRepository.saveAndFlush(new GroupParticipant(groupA, targetUser));
        GroupParticipant participantB = groupParticipantRepository.saveAndFlush(new GroupParticipant(groupB, targetUser));
        GroupParticipant participantC = groupParticipantRepository.saveAndFlush(new GroupParticipant(groupC, targetUser));

        Message a1 = new Message(senderUser, "A-1");
        a1.setGroup(groupA);
        a1 = messageRepository.saveAndFlush(a1);

        Message a2 = new Message(senderUser, "A-2");
        a2.setGroup(groupA);
        a2 = messageRepository.saveAndFlush(a2);

        Message a3 = new Message(senderUser, "A-3");
        a3.setGroup(groupA);
        a3 = messageRepository.saveAndFlush(a3);

        Message b1 = new Message(senderUser, "B-1");
        b1.setGroup(groupB);
        b1 = messageRepository.saveAndFlush(b1);

        Message b2 = new Message(senderUser, "B-2");
        b2.setGroup(groupB);
        b2 = messageRepository.saveAndFlush(b2);

        // Group A: 3 messages, last read at first => unread should be 2.
        participantA.setLastReadMessageId(a1.getId());
        groupParticipantRepository.saveAndFlush(participantA);

        // Group B: 2 messages, last read at latest => unread should be 0.
        participantB.setLastReadMessageId(b2.getId());
        groupParticipantRepository.saveAndFlush(participantB);

        // Group C: no messages => unread should be 0.
        participantC.setLastReadMessageId(null);
        groupParticipantRepository.saveAndFlush(participantC);

        Map<Long, Long> unreadByGroupId = groupService.getUnreadCountByGroupId(targetUser);
        long totalUnreadCount = groupService.getTotalUnreadCount(targetUser);

        assertThat(unreadByGroupId)
                .containsEntry(groupA.getId(), 2L)
                .containsEntry(groupB.getId(), 0L)
                .containsEntry(groupC.getId(), 0L);

        assertThat(totalUnreadCount).isEqualTo(2L);
        assertThat(totalUnreadCount).isEqualTo(unreadByGroupId.values().stream().mapToLong(count -> count).sum());
    }
}
