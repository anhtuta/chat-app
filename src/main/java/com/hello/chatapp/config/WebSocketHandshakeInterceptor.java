package com.hello.chatapp.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Interceptor that extracts username from HTTP session during WebSocket
 * handshake and stores it in WebSocket session attributes.
 */
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    @SuppressWarnings({"OverlyComplexMethod", "D"})
    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession httpSession = servletRequest.getServletRequest().getSession(false);

            if (httpSession != null) {
                // Try to get username from HTTP session (stored during login)
                String username = (String) httpSession.getAttribute("username");

                // If not in session, try to get from SecurityContext
                if (username == null) {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                        username = auth.getName();
                    }
                }

                // Store username in WebSocket session attributes
                // This will be used to validate messages and prevent spoofing
                if (username != null) {
                    attributes.put("username", username);
                }
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
