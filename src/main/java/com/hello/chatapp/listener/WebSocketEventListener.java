package com.hello.chatapp.listener;

import com.hello.chatapp.config.SessionRegistry;
import com.hello.chatapp.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @Autowired
    private SessionRegistry sessionRegistry;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();

        if (sessionId != null && sessionAttributes != null) {
            // Register the session
            sessionRegistry.addSession(sessionId, sessionAttributes);
        }

        logger.info("Received a new web socket connection. Session ID: {}", sessionId);

        // Print all active connections
        printAllConnections();
    }

    private void printAllConnections() {
        Map<String, Map<String, Object>> allSessions = sessionRegistry.getAllSessions();
        int totalSessions = sessionRegistry.getSessionCount();

        logger.info("========== Active WebSocket Connections ==========");
        logger.info("Total active sessions: {}", totalSessions);

        if (allSessions.isEmpty()) {
            logger.info("No active connections");
        } else {
            for (Map.Entry<String, Map<String, Object>> entry : allSessions.entrySet()) {
                String sessionId = entry.getKey();
                Map<String, Object> attributes = entry.getValue();
                String username = (String) attributes.get("username");

                logger.info("Session ID: {} | Username: {}",
                        sessionId,
                        username != null ? username : "N/A");
            }
        }
        logger.info("==================================================");
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        // Remove session from registry
        if (sessionId != null) {
            Map<String, Object> attributes = sessionRegistry.getSessionAttributes(sessionId);
            String username = attributes != null ? (String) attributes.get("username") : null;

            sessionRegistry.removeSession(sessionId);

            if (username != null) {
                logger.info("User Disconnected : {}", username);
                Message chatMessage = new Message(username, username + " has left the chat");
                messagingTemplate.convertAndSend("/topic/public", chatMessage);
            }
        }
    }
}
