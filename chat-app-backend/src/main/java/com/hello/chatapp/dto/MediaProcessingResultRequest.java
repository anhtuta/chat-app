package com.hello.chatapp.dto;

import com.hello.chatapp.constant.MediaProcessingJobStatus;
import com.hello.chatapp.constant.ProcessingTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;

/**
 * Internal callback body sent by media-processing-service after a processing attempt.
 *
 * @param jobId worker job identifier
 * @param messageId parent message identifier
 * @param mediaId attachment identifier
 * @param status resulting worker state
 * @param videoMetadata extracted metadata, if available
 * @param completedTargets completed outputs
 * @param pendingTargets outputs still pending
 * @param originalObjectKey object key the worker processed
 * @param thumbnailObjectKey generated poster object key, if available
 * @param transcodedObjectKey canonical playback object key, if transcode succeeded
 * @param canonicalObjectSize size of the canonical playback object
 * @param reusedOriginalObject whether the original object is already canonical
 * @param videoRenditions secondary playback renditions produced by the worker
 */
public record MediaProcessingResultRequest(
        @NotBlank String jobId,
        @NotNull Long messageId,
        @NotNull Long mediaId,
        @NotNull MediaProcessingJobStatus status,
        @Valid MediaProcessingVideoMetadataRequest videoMetadata,
        @NotNull Set<ProcessingTarget> completedTargets,
        @NotNull Set<ProcessingTarget> pendingTargets,
        @NotBlank String originalObjectKey,
        String thumbnailObjectKey,
        String transcodedObjectKey,
        Long canonicalObjectSize,
        boolean reusedOriginalObject,
        @NotNull List<@Valid MediaProcessingVideoRenditionRequest> videoRenditions) {

    /**
     * Micronaut Serde omits empty collections by default, so a finished job can arrive without
     * {@code pendingTargets}. Treat missing collections as empty instead of failing {@code @NotNull}.
     */
    public MediaProcessingResultRequest {
        completedTargets = completedTargets == null ? Set.of() : Set.copyOf(completedTargets);
        pendingTargets = pendingTargets == null ? Set.of() : Set.copyOf(pendingTargets);
        videoRenditions = videoRenditions == null ? List.of() : List.copyOf(videoRenditions);
    }
}
