package com.hello.chatapp.service;

import com.hello.chatapp.config.CustomRabbitMQBrokerHandler;
import com.hello.chatapp.dto.GroupSummaryUpdate;
import com.hello.chatapp.repository.GroupParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Illustrates how {@code ConcurrentHashMap} plus per-group {@code synchronized (pendingUpdate)}
 * prevents lost updates and duplicate flush scheduling under concurrent publishes.
 */
@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class GroupSummaryUpdatePublisherTest {

    private static final Long GROUP_ONE = 1L;
    private static final Long GROUP_TWO = 2L;

    @Mock
    private GroupParticipantRepository groupParticipantRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CustomRabbitMQBrokerHandler rabbitMQBrokerHandler;

    @Mock
    private GroupUpdatesSubscriptionRegistry groupUpdatesSubscriptionRegistry;

    @Mock
    private TaskScheduler groupSummaryUpdateScheduler;

    private GroupSummaryUpdatePublisher publisher;

    private final List<Runnable> scheduledFlushTasks = new CopyOnWriteArrayList<>();
    private final AtomicInteger scheduleInvocationCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        publisher = new GroupSummaryUpdatePublisher(
                groupParticipantRepository,
                messagingTemplate,
                rabbitMQBrokerHandler,
                groupUpdatesSubscriptionRegistry,
                groupSummaryUpdateScheduler);

        scheduledFlushTasks.clear();
        scheduleInvocationCount.set(0);

        when(groupSummaryUpdateScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    Runnable flushTask = invocation.getArgument(0);
                    scheduledFlushTasks.add(flushTask);
                    scheduleInvocationCount.incrementAndGet();
                    return mock(ScheduledFuture.class);
                });
    }

    /**
     * Sequential burst of 3 updates → only 1 flush scheduled, and flush delivers the last update ("third").
     * Shows debounce/coalescing.
     */
    @Test
    void publishToGroupMembers_buffersBurstAndSchedulesOnlyOneFlush() {
        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "first"));
        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "second"));
        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "third"));

        assertThat(scheduleInvocationCount.get()).isEqualTo(1);
        assertThat(scheduledFlushTasks).hasSize(1);

        stubFlushDelivery("alice");
        scheduledFlushTasks.getFirst().run();

        ArgumentCaptor<GroupSummaryUpdate> publishedUpdateCaptor =
                ArgumentCaptor.forClass(GroupSummaryUpdate.class);
        verify(rabbitMQBrokerHandler).publishToRabbitMQ(
                eq("/topic/user.alice.group-updates"),
                publishedUpdateCaptor.capture());
        assertThat(publishedUpdateCaptor.getValue().getLatestMessage()).isEqualTo("third");
    }

    /**
     * 32 threads publish simultaneously (ready/start latch pattern). Asserts:
     * 
     * - schedule() called once — without the lock, concurrent threads could each see scheduledFlush == null and
     * schedule duplicate flushes.
     * - After a final sequential publish, flush delivers "latest-after-burst" — buffered state wasn’t corrupted.
     */
    @Test
    void publishToGroupMembers_concurrentPublishesForSameGroup_scheduleOnceAndFlushLatestUpdate()
            throws Exception {
        int publishCount = 32;
        ExecutorService executorService = Executors.newFixedThreadPool(publishCount);
        CountDownLatch ready = new CountDownLatch(publishCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> publishFutures = new ArrayList<>();
        for (int index = 0; index < publishCount; index++) {
            final int messageIndex = index;
            publishFutures.add(executorService.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                publisher.publishToGroupMembers(
                        GROUP_ONE,
                        update(GROUP_ONE, "parallel-update-" + messageIndex));
                return null;
            }));
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (Future<?> publishFuture : publishFutures) {
            publishFuture.get(10, TimeUnit.SECONDS);
        }
        executorService.shutdown();
        assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Without per-group synchronization, concurrent threads could schedule multiple flushes.
        assertThat(scheduleInvocationCount.get()).isEqualTo(1);
        assertThat(scheduledFlushTasks).hasSize(1);

        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "latest-after-burst"));

        stubFlushDelivery("alice");
        scheduledFlushTasks.getFirst().run();

        ArgumentCaptor<GroupSummaryUpdate> publishedUpdateCaptor =
                ArgumentCaptor.forClass(GroupSummaryUpdate.class);
        verify(rabbitMQBrokerHandler).publishToRabbitMQ(
                eq("/topic/user.alice.group-updates"),
                publishedUpdateCaptor.capture());
        assertThat(publishedUpdateCaptor.getValue().getLatestMessage()).isEqualTo("latest-after-burst");
    }

    /**
     * 16 threads per group × 2 groups in parallel → 2 flushes scheduled (one per group).
     * Shows per-group locks don’t block each other
     */
    @Test
    void publishToGroupMembers_concurrentPublishesForDifferentGroups_scheduleOneFlushPerGroup()
            throws Exception {
        int publishesPerGroup = 16;
        int publishCount = publishesPerGroup * 2;
        ExecutorService executorService = Executors.newFixedThreadPool(publishCount);
        CountDownLatch ready = new CountDownLatch(publishCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> publishFutures = new ArrayList<>();
        for (int index = 0; index < publishesPerGroup; index++) {
            final int messageIndex = index;
            publishFutures.add(executorService.submit(publishTask(
                    GROUP_ONE, "group-one-" + messageIndex, ready, start)));
            publishFutures.add(executorService.submit(publishTask(
                    GROUP_TWO, "group-two-" + messageIndex, ready, start)));
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (Future<?> publishFuture : publishFutures) {
            publishFuture.get(10, TimeUnit.SECONDS);
        }
        executorService.shutdown();
        assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Different groups use different PendingGroupSummaryUpdate locks, so each group debounces independently.
        assertThat(scheduleInvocationCount.get()).isEqualTo(2);
        assertThat(scheduledFlushTasks).hasSize(2);
    }

    /**
     * Blocks flush during DB I/O, publishes a newer update while flush is outside the lock, then verifies a second flush is
     * scheduled and both messages are eventually published ("before-flush" then "during-flush").
     * Shows the finally block’s synchronized reschedule/cleanup path
     */
    @Test
    void flushGroupMembers_reschedulesWhenNewUpdateArrivesDuringFlush() throws Exception {
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch allowFlushToContinue = new CountDownLatch(1);

        when(groupParticipantRepository.findParticipantUsernamesByGroupId(GROUP_ONE))
                .thenAnswer(invocation -> {
                    flushEntered.countDown();
                    assertThat(allowFlushToContinue.await(5, TimeUnit.SECONDS)).isTrue();
                    return List.of("alice");
                });
        when(groupUpdatesSubscriptionRegistry.hasClusterSubscriber("alice")).thenReturn(true);
        when(rabbitMQBrokerHandler.hasLocalSubscribers(anyString())).thenReturn(false);

        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "before-flush"));
        Runnable firstFlush = scheduledFlushTasks.getFirst();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> flushFuture = executorService.submit(firstFlush);

        assertThat(flushEntered.await(5, TimeUnit.SECONDS)).isTrue();
        publisher.publishToGroupMembers(GROUP_ONE, update(GROUP_ONE, "during-flush"));

        allowFlushToContinue.countDown();
        flushFuture.get(10, TimeUnit.SECONDS);
        executorService.shutdown();
        assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // A newer update arrived while the first flush was doing I/O outside the lock.
        assertThat(scheduleInvocationCount.get()).isEqualTo(2);
        assertThat(scheduledFlushTasks).hasSize(2);

        scheduledFlushTasks.get(1).run();

        ArgumentCaptor<GroupSummaryUpdate> publishedUpdateCaptor =
                ArgumentCaptor.forClass(GroupSummaryUpdate.class);
        verify(rabbitMQBrokerHandler, times(2)).publishToRabbitMQ(
                eq("/topic/user.alice.group-updates"),
                publishedUpdateCaptor.capture());
        assertThat(publishedUpdateCaptor.getAllValues())
                .extracting(GroupSummaryUpdate::getLatestMessage)
                .containsExactly("before-flush", "during-flush");
    }

    private Callable<Void> publishTask(
            Long groupId,
            String latestMessage,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
            publisher.publishToGroupMembers(groupId, update(groupId, latestMessage));
            return null;
        };
    }

    private void stubFlushDelivery(String username) {
        when(groupParticipantRepository.findParticipantUsernamesByGroupId(GROUP_ONE))
                .thenReturn(List.of(username));
        when(groupUpdatesSubscriptionRegistry.hasClusterSubscriber(username)).thenReturn(true);
        when(rabbitMQBrokerHandler.hasLocalSubscribers(anyString())).thenReturn(false);
    }

    private static GroupSummaryUpdate update(Long groupId, String latestMessage) {
        return GroupSummaryUpdate.builder()
                .groupId(groupId)
                .latestMessage(latestMessage)
                .latestMessageSender("sender")
                .latestMessageAt(LocalDateTime.of(2026, 7, 3, 12, 0))
                .build();
    }
}
