package com.hello.chatapp.listener;

import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.entity.Message;
import com.hello.chatapp.entity.User;
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

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        // Join notification is handled in WebSocketSecurityChannelInterceptor when CONNECT command is received,
        // as session attributes are more reliably available there. At this point, we cannot get username from
        // session attributes (headerAccessor.getSessionAttributes = null)
        logger.debug("[handleWebSocketConnectListener] WebSocket session connected");
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null) {
            User user = (User) sessionAttributes.get("user");
            if (user != null) {
                logger.info("User Disconnected : {}", user.getUsername());
                // Create disconnect notification (not saved to DB)
                Message disconnectMessage = new Message(user, "[SYSTEM] " + user.getUsername() + " disconnected");
                MessageResponse response = MessageResponse.fromMessage(disconnectMessage);
                messagingTemplate.convertAndSend("/topic/public", response);
            }
        } else {
            logger.warn("User disconnected but session attributes not available");
        }
    }
}
