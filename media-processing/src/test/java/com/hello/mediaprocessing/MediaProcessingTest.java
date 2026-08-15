package com.hello.mediaprocessing;


import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;

/**
 * Verifies that the Micronaut application can boot in the test environment.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class MediaProcessingTest {

    @Inject
    EmbeddedApplication<?> application;

    /**
     * Ensures the embedded Micronaut application starts successfully.
     */
    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

}
