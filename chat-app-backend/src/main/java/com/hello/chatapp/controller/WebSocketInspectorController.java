package com.hello.chatapp.controller;

import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ws-inspector")
public class WebSocketInspectorController {

    private final SimpUserRegistry userRegistry;

    public WebSocketInspectorController(SimpUserRegistry userRegistry) {
        this.userRegistry = userRegistry;
    }

    @GetMapping("/connections")
    public Map<String, Object> inspectConnections() {
        List<Map<String, Object>> users = userRegistry.getUsers().stream()
                .map(user -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("user", user.getName());

                    List<Map<String, Object>> sessions = user.getSessions().stream()
                            .map(session -> {
                                Map<String, Object> s = new LinkedHashMap<>();
                                s.put("sessionId", session.getId());

                                List<String> destinations = session.getSubscriptions().stream()
                                        .map(SimpSubscription::getDestination)
                                        .collect(Collectors.toList());
                                s.put("subscriptions", destinations);
                                s.put("subscriptionCount", session.getSubscriptions().size());

                                // TODO: Measuring JVM memory per WebSocket session/connection is not provided
                                // by Spring; requires external tracking or instrumentation. Leaving placeholder.
                                s.put("estimatedMemoryBytes", null);

                                return s;
                            })
                            .collect(Collectors.toList());

                    u.put("sessions", sessions);
                    u.put("sessionsCount", user.getSessions().size());
                    return u;
                })
                .collect(Collectors.toList());

        Set<String> allDestinations = new HashSet<>();
        userRegistry.getUsers().forEach(u -> u.getSessions().forEach(s -> s.getSubscriptions()
                .forEach(sub -> allDestinations.add(sub.getDestination()))));

        int totalSubscriptions = userRegistry.getUsers().stream()
                .flatMap(u -> u.getSessions().stream())
                .mapToInt(s -> s.getSubscriptions().size())
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", userRegistry.getUsers().size());
        result.put("totalSubscriptions", totalSubscriptions);
        result.put("destinations", allDestinations);
        result.put("users", users);

        return result;
    }

    @GetMapping("/subscriptions")
    public Map<String, Object> inspectSubscriptions() {
        Map<String, Map<String, Object>> destinations = new LinkedHashMap<>();

        userRegistry.getUsers().forEach(u -> u.getSessions().forEach(s -> s.getSubscriptions()
                .forEach(sub -> {
                    String dest = sub.getDestination();
                    destinations.computeIfAbsent(dest, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("destination", k);
                        m.put("subscriberCount", 0);
                        m.put("subscribers", new ArrayList<Map<String, String>>());
                        return m;
                    });

                    Map<String, Object> entry = destinations.get(dest);
                    int count = (int) entry.get("subscriberCount") + 1;
                    entry.put("subscriberCount", count);

                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> subs = (List<Map<String, String>>) entry.get("subscribers");
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("user", u.getName());
                    info.put("sessionId", s.getId());
                    info.put("subscriptionId", sub.getId());
                    subs.add(info);
                })));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDestinations", destinations.size());
        result.put("destinations", destinations.values());
        return result;
    }
}
