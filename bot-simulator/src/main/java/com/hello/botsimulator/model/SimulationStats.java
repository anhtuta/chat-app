package com.hello.botsimulator.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class SimulationStats {

    private final LongAdder sentMessages = new LongAdder();
    private final LongAdder failedMessages = new LongAdder();
    private final LongAdder connectFailures = new LongAdder();
    private final AtomicInteger connectedBots = new AtomicInteger();

    public void incrementSentMessages() {
        sentMessages.increment();
    }

    public void incrementFailedMessages() {
        failedMessages.increment();
    }

    public void incrementConnectFailures() {
        connectFailures.increment();
    }

    public void incrementConnectedBots() {
        connectedBots.incrementAndGet();
    }

    public void decrementConnectedBots() {
        connectedBots.decrementAndGet();
    }

    public long sentMessages() {
        return sentMessages.sum();
    }

    public long failedMessages() {
        return failedMessages.sum();
    }

    public long connectFailures() {
        return connectFailures.sum();
    }

    public int connectedBots() {
        return connectedBots.get();
    }
}
