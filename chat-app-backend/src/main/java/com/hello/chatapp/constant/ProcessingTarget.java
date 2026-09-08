package com.hello.chatapp.constant;

/**
 * Outputs that chat-app-backend may request from media-processing-service.
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
