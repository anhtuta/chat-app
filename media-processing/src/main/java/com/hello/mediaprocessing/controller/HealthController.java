package com.hello.mediaprocessing.controller;

import com.hello.mediaprocessing.dto.HealthResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/**
 * Public liveness probe so orchestrators can tell the HTTP process is up.
 */
@Controller("/health")
public class HealthController {

    /**
     * Returns {@code UP} when this JVM is serving HTTP. Does not check storage or brokers.
     */
    @Get
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
