package com.hello.chatapp.service;

import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.repository.GroupRepository;
import com.hello.chatapp.repository.MessageRepository;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(MessageService.class)
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class MessageServiceIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void saveGroupMessage_keepsGroupSummaryOnNewestMessageUnderParallelWrites() throws Exception {
        User creator = userRepository.saveAndFlush(new User("creator", "secret", "Creator"));
        User otherUser = userRepository.saveAndFlush(new User("other", "secret", "Other"));

        Group group = new Group("Backend", creator);
        group = groupRepository.saveAndFlush(group);

        ExecutorService executorService = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Message>> futures = new ArrayList<>();

        for (int index = 0; index < 6; index++) {
            final int messageIndex = index;
            final Long senderId = index % 2 == 0 ? creator.getId() : otherUser.getId();
            futures.add(executorService.submit(sendMessageTask(group.getId(), senderId, ready, start, messageIndex)));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Message> persistedMessages = new ArrayList<>();
        for (Future<Message> future : futures) {
            persistedMessages.add(future.get(10, TimeUnit.SECONDS));
        }

        executorService.shutdown();
        assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Group persistedGroup = groupRepository.findById(Objects.requireNonNull(group.getId())).orElseThrow();
        Message actualLatestMessage = messageRepository.findLatestGroupMessages(persistedGroup, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElseThrow();

        assertThat(persistedMessages).hasSize(6);
        assertThat(persistedGroup.getLatestMessage()).isEqualTo(actualLatestMessage.getContent());
        assertThat(persistedGroup.getLatestMessageSender()).isEqualTo(actualLatestMessage.getUser().getUsername());
        assertThat(persistedGroup.getLatestMessageAt()).isEqualTo(actualLatestMessage.getTimestamp());
    }

    private Callable<Message> sendMessageTask(
            Long groupId,
            Long userId,
            CountDownLatch ready,
            CountDownLatch start,
            int messageIndex) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();

            Group taskGroup = groupRepository.findById(Objects.requireNonNull(groupId)).orElseThrow();
            User taskUser = userRepository.findById(Objects.requireNonNull(userId)).orElseThrow();

            return messageService.saveGroupMessage(taskGroup, taskUser, "parallel-message-" + messageIndex);
        };
    }
}
