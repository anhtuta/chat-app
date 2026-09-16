package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers binding of worker feature flags from application properties onto the nested config object.
 */
@MicronautTest(startApplication = false)
@Property(name = "media-processing.worker.feature-flags.video-transcode", value = "true")
class MediaProcessingWorkerPropertiesTest {

    @Inject
    MediaProcessingWorkerProperties workerProperties;

    /**
     * Verifies that {@code video-transcode=true} in config overrides the Java field default of {@code false}.
     */
    @Test
    void bindsNestedVideoTranscodeFeatureFlag() {
        assertThat(workerProperties.getFeatureFlags().isVideoTranscode()).isTrue();
    }
}
