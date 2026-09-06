package com.hello.mediaprocessing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hello.mediaprocessing.dto.HealthResponse;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Verifies the public liveness endpoint mapping and payload.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class HealthControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    /**
     * GET /health returns 200 and status UP without requiring RabbitMQ or storage.
     */
    @Test
    void health_returnsUp() {
        HttpResponse<HealthResponse> response = client.toBlocking().exchange("/health", HealthResponse.class);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body()).isNotNull();
        assertThat(response.body().status()).isEqualTo("UP");
    }
}
