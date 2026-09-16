package com.hello.mediaprocessing.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers derived object-key naming for transcoded playback files.
 */
class VideoTranscodeObjectKeysTest {

    /**
     * Verifies that a source key keeps its directory and gains a {@code .transcoded.mp4} suffix.
     */
    @Test
    void derive_replacesExtensionWithTranscodedMp4() {
        assertThat(VideoTranscodeObjectKeys.derive("media/7/video/demo.mov"))
                .isEqualTo("media/7/video/demo.transcoded.mp4");
    }

    /**
     * Verifies that keys without a directory still receive the transcoded suffix.
     */
    @Test
    void derive_keyWithoutDirectory_usesStem() {
        assertThat(VideoTranscodeObjectKeys.derive("clip.webm")).isEqualTo("clip.transcoded.mp4");
    }
}
