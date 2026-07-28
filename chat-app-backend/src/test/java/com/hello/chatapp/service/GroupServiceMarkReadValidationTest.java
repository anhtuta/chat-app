package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.GroupParticipant;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.repository.GroupParticipantRepository;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hello.chatapp.support.IsolatedH2DataSourceSupport;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({GroupService.class, GroupAuthorizationService.class, MessageService.class})
public class GroupServiceMarkReadValidationTest {

    @DynamicPropertySource
    static void registerIsolatedDataSource(DynamicPropertyRegistry registry) {
        IsolatedH2DataSourceSupport.register(registry, GroupServiceMarkReadValidationTest.class);
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
    public void markGroupAsRead_shouldRejectMessageFromDifferentGroup() {
        // given: two users, two groups, a message in groupA, and user1 is participant in groupB
        User user1 = new User();
        user1.setUsername("u_mark_read_test");
        user1.setPassword("x");
        user1.setFullname("u_mark_read_test_full");
        userRepository.save(user1);

        // groups require a creator; use user1 as creator for simplicity
        Group groupA = new Group("group-a", user1);
        groupRepository.save(groupA);

        Group groupB = new Group("group-b", user1);
        groupRepository.save(groupB);

        // create a user as message sender (messages require a user)
        User sender = new User();
        sender.setUsername("system_sender");
        sender.setPassword("x");
        sender.setFullname("system");
        userRepository.save(sender);

        Message msgInA = new Message(sender, "hello A");
        msgInA.setGroup(groupA);
        messageRepository.save(msgInA);

        GroupParticipant participantB = new GroupParticipant(groupB, user1);
        groupParticipantRepository.save(participantB);

        // when/then: attempt to mark groupB read using msgInA id should throw BadRequestException
        Long invalidMessageId = msgInA.getId();

        BadRequestException ex = Assertions.assertThrows(BadRequestException.class,
                () -> groupService.markGroupAsRead(user1, groupB.getId(), invalidMessageId));

        Assertions.assertTrue(ex.getMessage().contains("does not belong to this group"),
                "Expected validation message mentioning group mismatch");
    }
}
