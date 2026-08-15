package com.hello.mediaprocessing.constant;

/**
 * Enumerates the concrete processing outputs that a worker may produce for a media object.
 */
public enum ProcessingTarget {
    THUMBNAIL,
    PREVIEW,
    TRANSCODE,
    METADATA,
    IMAGE_OCR,
    VIDEO_OCR,
    SPEECH_TO_TEXT
}
