package com.hello.chatapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Cluster-wide refcount for active {@code /topic/user.{username}.group-updates} subscriptions.
 * <p>
 * Each app instance increments when its first local client subscribes and decrements when the
 * last local client unsubscribes, so multi-tab and multi-instance sessions are handled correctly.
 */
@Service
public class GroupUpdatesSubscriptionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GroupUpdatesSubscriptionRegistry.class);
    private static final String REDIS_COUNT_KEY_PREFIX = "ws:group-updates:count:";

    private final StringRedisTemplate redisTemplate;

    public GroupUpdatesSubscriptionRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void trackLocalSubscriptionOpened(String username) {
        try {
            redisTemplate.opsForValue().increment(redisKey(username));
        } catch (Exception e) {
            logger.warn("Failed to track group-updates subscription open for user={}", username, e);
        }
    }

    public void trackLocalSubscriptionClosed(String username) {
        try {
            Long remaining = redisTemplate.opsForValue().decrement(redisKey(username));
            if (remaining != null && remaining <= 0) {
                redisTemplate.delete(redisKey(username));
            }
        } catch (Exception e) {
            logger.warn("Failed to track group-updates subscription close for user={}", username, e);
        }
    }

    /**
     * Returns {@code true} when at least one connected client in the cluster is subscribed.
     * Fails open (returns {@code true}) if Redis is unavailable so sidebar updates are not dropped.
     */
    public boolean hasClusterSubscriber(String username) {
        try {
            String count = redisTemplate.opsForValue().get(redisKey(username));
            return count != null && Long.parseLong(count) > 0;
        } catch (Exception e) {
            logger.warn("Failed to read group-updates subscription state for user={}, assuming subscribed",
                    username, e);
            return true;
        }
    }

    private static String redisKey(String username) {
        return REDIS_COUNT_KEY_PREFIX + username;
    }
}
