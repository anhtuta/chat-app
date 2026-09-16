package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.MediaProcessingJobMessage;

/**
 * Resolves a processing job into a locally accessible media source file.
 */
public interface MediaProcessingSourceLoader {

    /**
     * Loads the source media for a job into the worker's local workspace.
     *
     * @param job processing job that identifies the source object
     * @return handle for the downloaded local source file
     */
    LoadedMediaSource load(MediaProcessingJobMessage job);
}
