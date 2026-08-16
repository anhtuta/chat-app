package com.hello.mediaprocessing.constant;

/**
 * Categorizes the reasons that a worker could not prepare a source file for processing.
 */
public enum MediaProcessingFailureReason {
    STORAGE_PROVIDER_MISMATCH,
    SOURCE_MISSING,
    SOURCE_UNREADABLE,
    SOURCE_CORRUPTED,
    TEMP_FILE_PREPARATION_FAILED,
    METADATA_EXTRACTION_FAILED
}
