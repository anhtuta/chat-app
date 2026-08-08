package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.MediaProcessingResult;

/**
 * Receives normalized worker results so later phases can forward them to the chat backend.
 */
public interface MediaProcessingResultSink {

    /**
     * Accepts a completed worker result for further publication or persistence.
     *
     * @param result normalized worker output for the processed job
     */
    void accept(MediaProcessingResult result);
}
