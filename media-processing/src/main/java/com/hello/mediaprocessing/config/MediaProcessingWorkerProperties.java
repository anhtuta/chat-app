package com.hello.mediaprocessing.config;

import com.hello.mediaprocessing.job.MediaProcessingHandoffMode;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties("media-processing.worker")
@Introspected
@Getter
@Setter
public class MediaProcessingWorkerProperties {

    private boolean enabled = false;

    @NotNull
    private MediaProcessingHandoffMode handoff = MediaProcessingHandoffMode.RABBITMQ;

    @NotBlank
    private String queue = "media.processing.jobs";

    @Min(1)
    private int consumerConcurrency = 1;

    @Min(0)
    private int maxRetries = 3;

    @Valid
    @NotNull
    private FeatureFlags featureFlags = new FeatureFlags();

    @Introspected
    @Getter
    @Setter
    public static class FeatureFlags {

        private boolean videoMetadata = true;

        private boolean videoPoster = true;

        private boolean videoTranscode = false;

        private boolean videoOcr = false;

        private boolean speechToText = false;

        private boolean imageProcessing = false;

        private boolean imageOcr = false;
    }
}
