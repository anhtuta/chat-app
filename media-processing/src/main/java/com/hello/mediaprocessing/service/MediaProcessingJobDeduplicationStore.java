package com.hello.mediaprocessing.service;

public interface MediaProcessingJobDeduplicationStore {

    boolean markIfFirstSeen(String jobId);
}
