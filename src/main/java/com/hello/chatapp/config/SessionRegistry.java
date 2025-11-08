package com.hello.chatapp.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom session registry to track all active WebSocket connections.
 * This maintains a map of session IDs to their attributes (like username).
 */
@Component
public class SessionRegistry {

    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    public void addSession(String sessionId, Map<String, Object> attributes) {
        // Store reference to the actual session attributes map (Spring uses
        // ConcurrentHashMap internally)
        // This allows updates to session attributes to be reflected in our registry
        sessions.put(sessionId, attributes);
    }

    public void updateSession(String sessionId, Map<String, Object> attributes) {
        // Update existing session attributes
        if (sessions.containsKey(sessionId)) {
            sessions.put(sessionId, attributes);
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public Map<String, Map<String, Object>> getAllSessions() {
        return new ConcurrentHashMap<>(sessions);
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public Map<String, Object> getSessionAttributes(String sessionId) {
        return sessions.get(sessionId);
    }
}
