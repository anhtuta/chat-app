package com.hello.mediaprocessing.service;

/**
 * Categorizes the reasons that a worker could not prepare a source file for processing.
 */
public enum MediaProcessingFailureReason {
    SOURCE_MISSING,
    SOURCE_UNREADABLE,
    SOURCE_CORRUPTED,
    TEMP_FILE_PREPARATION_FAILED
}
