package com.hello.chatapp.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AfterCommitTest {

    @BeforeEach
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void run_withoutActiveTransaction_runsImmediately() {
        AtomicInteger calls = new AtomicInteger();

        AfterCommit.run(calls::incrementAndGet);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    void run_withActiveTransaction_waitsUntilAfterCommit() {
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(calls::incrementAndGet);

        assertThat(calls.get()).isZero();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void run_whenActionThrows_doesNotPropagate() {
        assertThatCode(() -> AfterCommit.run(() -> {
            throw new IllegalStateException("boom");
        })).doesNotThrowAnyException();
    }
}
