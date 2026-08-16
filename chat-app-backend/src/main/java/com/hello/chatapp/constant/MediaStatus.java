package com.hello.chatapp.constant;

/**
 * Lifecycle of a {@code message_media} attachment for processing and delivery
 * ({@code message_media.status}). Not the malware column — that is
 * {@link MediaScanStatus} on {@code scan_status}.
 */
public enum MediaStatus {
    UPLOAD_COMPLETED,
    SCAN_PENDING,
    SCAN_PASSED,
    PROCESSING_PENDING,
    PROCESSING_IN_PROGRESS,
    MEDIA_READY,
    PROCESSING_FAILED,
    SCAN_BLOCKED,
    HARD_DELETED
}
