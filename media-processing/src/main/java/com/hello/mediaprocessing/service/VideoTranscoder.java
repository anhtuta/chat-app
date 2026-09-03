package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.VideoMetadata;
import com.hello.mediaprocessing.model.VideoTranscodeResult;
import java.nio.file.Path;

/**
 * Produces a local H.264 + AAC MP4 suitable for chat playback.
 */
public interface VideoTranscoder {

    /**
     * Reuses, remuxes, or re-encodes the source into a local playback file.
     *
     * @param sourceFile downloaded original video
     * @param outputFile workspace path for a derived MP4 when conversion is required
     * @param sourceMetadata probed metadata used to choose the conversion mode
     * @return local playback file and the mode used to produce it
     */
    VideoTranscodeResult transcode(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata);
}
