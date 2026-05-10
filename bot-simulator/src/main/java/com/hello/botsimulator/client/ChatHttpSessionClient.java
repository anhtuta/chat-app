package com.hello.botsimulator.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.botsimulator.model.BotHttpSession;
import com.hello.botsimulator.model.GroupSummary;
import com.hello.botsimulator.model.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ChatHttpSessionClient {

    private static final Logger logger = LoggerFactory.getLogger(ChatHttpSessionClient.class);

    private final ObjectMapper objectMapper;

    public ChatHttpSessionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BotHttpSession login(String baseUrl, String username, String password, Duration timeout)
            throws IOException, InterruptedException {
        BotHttpSession session = createSession();
        postJson(session, baseUrl, "/api/auth/login", Map.of("username", username, "password", password), timeout);
        ensureSessionCookie(session, username);
        return session;
    }

    public BotHttpSession loginOrRegister(String baseUrl,
            String username,
            String password,
            boolean registerIfMissing,
            Duration timeout) throws IOException, InterruptedException {
        BotHttpSession session = createSession();

        if (registerIfMissing) {
            HttpResponse<String> registerResponse = postJson(session,
                    baseUrl,
                    "/api/auth/register",
                    Map.of("username", username, "password", password),
                    timeout);
            int statusCode = registerResponse.statusCode();
            if (statusCode >= 400 && statusCode != HttpStatus.BAD_REQUEST.value()) {
                logger.debug("Register response for user {} returned status {}", username, statusCode);
            }
        }

        postJson(session, baseUrl, "/api/auth/login", Map.of("username", username, "password", password), timeout);
        ensureSessionCookie(session, username);
        return session;
    }

    public List<UserSummary> fetchUsers(BotHttpSession session, String baseUrl, Duration timeout)
            throws IOException, InterruptedException {
        HttpResponse<String> response = get(session, baseUrl, "/api/groups/users", timeout);
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to fetch users. Status=" + response.statusCode() + " body=" + response.body());
        }

        List<Map<String, Object>> rows = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return rows.stream()
                .map(row -> new UserSummary(asLong(row.get("id")), (String) row.get("username")))
                .collect(Collectors.toList());
    }

    public GroupSummary createGroup(BotHttpSession session,
            String baseUrl,
            String groupName,
            List<Long> participantIds,
            Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", groupName);
        payload.put("participantIds", participantIds);

        HttpResponse<String> response = postJson(session, baseUrl, "/api/groups", payload, timeout);
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to create group. Status=" + response.statusCode() + " body=" + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return new GroupSummary(root.path("id").asLong(), root.path("name").asText());
    }

    private BotHttpSession createSession() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();

        return new BotHttpSession(httpClient, cookieManager);
    }

    private HttpResponse<String> get(BotHttpSession session,
            String baseUrl,
            String path,
            Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        return session.httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(BotHttpSession session,
            String baseUrl,
            String path,
            Map<String, ?> body,
            Duration timeout) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return session.httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void ensureSessionCookie(BotHttpSession session, String username) {
        if (session.cookieHeader().isBlank()) {
            throw new IllegalStateException("Missing session cookie after login for user " + username);
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
