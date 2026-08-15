package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.VideoMetadata;
import java.nio.file.Path;

/**
 * Extracts structured metadata from a local video file.
 */
public interface VideoMetadataExtractor {

    /**
     * Probes a local video file and returns normalized metadata for downstream processing stages.
     *
     * @param localFile local video file path inside the worker workspace
     * @param fallbackMimeType MIME type captured earlier in the upload flow
     * @return normalized video metadata
     */
    VideoMetadata extract(Path localFile, String fallbackMimeType);
}
