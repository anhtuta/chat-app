package com.hello.chatapp.config;

import com.hello.chatapp.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Interceptor that extracts user object from HTTP session during WebSocket
 * handshake and stores it in WebSocket session attributes.
 */
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession httpSession = servletRequest.getServletRequest().getSession(false);

            if (httpSession != null) {
                // Get user object from HTTP session (stored during login)
                User user = (User) httpSession.getAttribute("user");

                if (user != null) {
                    // Store user object in WebSocket session attributes
                    // Username and userId can be accessed from user object
                    attributes.put("user", user);
                }
                // If user is null, we don't store anything - authentication will fail later
                // This ensures only properly authenticated users can use WebSocket
            }
        }

        return true; // Allow the handshake to proceed
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @org.springframework.lang.Nullable Exception exception) {
        // Nothing to do after handshake
    }
}
