package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.VideoMetadata;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Generates an additional mobile-friendly MP4 rendition from the canonical playback file when warranted.
 */
public interface VideoMobileRenditionGenerator {

    /**
     * Produces a smaller secondary MP4 rendition or skips when the source is already small enough.
     *
     * @param canonicalInputFile local canonical playback MP4
     * @param outputFile destination path for the smaller MP4
     * @param sourceMetadata original metadata used for skip decisions
     * @param canonicalObjectSize size of the canonical playback object in bytes
     * @return generated local file path, or empty when the rendition should be skipped
     */
    Optional<Path> generate(
            Path canonicalInputFile,
            Path outputFile,
            VideoMetadata sourceMetadata,
            long canonicalObjectSize);
}
