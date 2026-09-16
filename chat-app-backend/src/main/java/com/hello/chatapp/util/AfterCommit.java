package com.hello.chatapp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Defers side effects until after the surrounding DB transaction commits.
 * <p>
 * Prefer this over calling {@link TransactionSynchronizationManager} directly so callers share
 * the same “run now if no tx sync” and error-handling behavior.
 */
public final class AfterCommit {

    private static final Logger logger = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    /**
     * Runs {@code action} after the current transaction commits.
     * If no transaction synchronization is active, runs {@code action} immediately.
     */
    public static void run(Runnable action) {
        run(action, "Failed to run after-commit action");
    }

    /**
     * Same as {@link #run(Runnable)}, with a custom log message if {@code action} throws.
     */
    public static void run(Runnable action, String failureMessage) {
        Runnable safeAction = Objects.requireNonNull(action, "action must not be null");
        String safeFailureMessage = Objects.requireNonNull(failureMessage, "failureMessage must not be null");

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(safeAction, safeFailureMessage);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(safeAction, safeFailureMessage);
            }
        });
    }

    private static void runSafely(Runnable action, String failureMessage) {
        try {
            action.run();
        } catch (Exception e) {
            logger.error(failureMessage, e);
        }
    }
}
