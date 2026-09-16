package com.hello.mediaprocessing.model;

import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import java.nio.file.Path;

/**
 * Reports the local playback file produced for a transcode target.
 *
 * @param mode strategy used to produce the playback file
 * @param outputFile local file that should be uploaded, or the original source when reused
 */
public record VideoTranscodeResult(VideoTranscodeMode mode, Path outputFile) {
}
