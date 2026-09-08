package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.VideoMetadata;
import java.nio.file.Path;

/**
 * Produces a still-image poster for a video source on the local worker filesystem.
 */
public interface VideoPosterGenerator {

    /**
     * Extracts a poster image from the given source video.
     *
     * @param sourceFile local source video file
     * @param outputFile destination poster path
     * @param sourceMetadata video metadata used to choose a capture point
     * @return generated poster file path
     */
    Path generate(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata);
}
