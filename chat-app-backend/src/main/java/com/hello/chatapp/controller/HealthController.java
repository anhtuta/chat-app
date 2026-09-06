package com.hello.chatapp.controller;

import com.hello.chatapp.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness probe so orchestrators can tell the HTTP process is up.
 */
@RestController
public class HealthController {

    /**
     * Returns {@code UP} when this JVM is serving HTTP. Does not check database or brokers.
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.builder().status("UP").build();
    }
}
