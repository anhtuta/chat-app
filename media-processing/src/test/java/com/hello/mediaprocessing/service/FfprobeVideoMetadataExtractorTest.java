package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoMetadataProperties;
import com.hello.mediaprocessing.model.VideoMetadata;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(12_345L, metadata.durationMillis());
        assertEquals(1920, metadata.width());
        assertEquals(1080, metadata.height());
        assertEquals("video/mp4", metadata.detectedMimeType());
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", metadata.containerFormat());
        assertEquals("h264", metadata.videoCodec());
        assertEquals("aac", metadata.audioCodec());
    }
}
