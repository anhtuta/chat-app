package com.hello.mediaprocessing.constant;

/**
 * Categorizes the reasons that a worker could not complete a processing job.
 */
public enum MediaProcessingFailureReason {
    STORAGE_PROVIDER_MISMATCH,
    SOURCE_MISSING,
    SOURCE_UNREADABLE,
    SOURCE_CORRUPTED,
    TEMP_FILE_PREPARATION_FAILED,
    METADATA_EXTRACTION_FAILED,
    POSTER_GENERATION_FAILED,
    POSTER_UPLOAD_FAILED,
    UNSUPPORTED_VIDEO_FORMAT,
    TRANSCODE_FAILED,
    TRANSCODE_UPLOAD_FAILED,
    RENDITION_GENERATION_FAILED,
    RENDITION_UPLOAD_FAILED
}
