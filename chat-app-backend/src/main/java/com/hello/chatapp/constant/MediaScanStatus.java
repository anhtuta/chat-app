package com.hello.chatapp.constant;

/**
 * Malware-scan result for a {@code message_media} row ({@code scan_status} only).
 * Distinct from {@link MediaStatus}, which tracks processing / ready / deleted.
 */
public enum MediaScanStatus {
    SCAN_PENDING,
    SCAN_PASSED,
    SCAN_BLOCKED,
    SCAN_FAILED
}
