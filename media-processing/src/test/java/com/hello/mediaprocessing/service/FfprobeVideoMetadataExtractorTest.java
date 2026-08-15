package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoMetadataProperties;
import com.hello.mediaprocessing.model.VideoMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies ffprobe JSON mapping for the Phase 4 metadata extractor.
 */
class FfprobeVideoMetadataExtractorTest {

    /**
     * Verifies that ffprobe JSON is converted into the normalized metadata model used by the worker.
     */
    @Test
    void parseProbeOutput_mapsVideoMetadata() {
        MediaProcessingVideoMetadataProperties properties = new MediaProcessingVideoMetadataProperties();
        FfprobeVideoMetadataExtractor extractor = new FfprobeVideoMetadataExtractor(properties);

        VideoMetadata metadata = extractor.parseProbeOutput(
                """
                {
                  "streams": [
                    {
                      "codec_type": "video",
                      "codec_name": "h264",
                      "width": 1920,
                      "height": 1080
                    },
                    {
                      "codec_type": "audio",
                      "codec_name": "aac"
                    }
                  ],
                  "format": {
                    "duration": "12.345",
                    "format_name": "mov,mp4,m4a,3gp,3g2,mj2"
                  }
                }
                """,
                "video/mp4");

        assertThat(metadata.durationMillis()).isEqualTo(12_345L);
        assertThat(metadata.width()).isEqualTo(1920);
        assertThat(metadata.height()).isEqualTo(1080);
        assertThat(metadata.detectedMimeType()).isEqualTo("video/mp4");
        assertThat(metadata.containerFormat()).isEqualTo("mov,mp4,m4a,3gp,3g2,mj2");
        assertThat(metadata.videoCodec()).isEqualTo("h264");
        assertThat(metadata.audioCodec()).isEqualTo("aac");
    }
}
