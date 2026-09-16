package com.hello.mediaprocessing.dto;

import io.micronaut.serde.annotation.Serdeable;

/**
 * JSON body for the public liveness endpoint.
 *
 * @param status {@code UP} when this JVM is serving HTTP
 */
@Serdeable
public record HealthResponse(String status) {
}
