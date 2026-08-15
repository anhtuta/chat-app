package com.hello.mediaprocessing.model;

import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.ProcessingTarget;

import java.util.Set;

/**
 * Represents the normalized worker output that a later integration phase can send back to the backend.
 *
 * @param jobId processing job identifier
 * @param messageId parent chat message identifier
 * @param mediaId media row identifier
 * @param status resulting worker status after the current processing step
 * @param videoMetadata extracted video metadata for the processed source
 * @param completedTargets targets completed during the current worker execution
 * @param pendingTargets targets still waiting on later phases
 */
public record MediaProcessingResult(
        String jobId,
        Long messageId,
        Long mediaId,
        MediaProcessingJobStatus status,
        VideoMetadata videoMetadata,
        Set<ProcessingTarget> completedTargets,
        Set<ProcessingTarget> pendingTargets) {
}
