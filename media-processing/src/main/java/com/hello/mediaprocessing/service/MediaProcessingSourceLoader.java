package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.job.MediaProcessingJobMessage;

public interface MediaProcessingSourceLoader {

    LoadedMediaSource load(MediaProcessingJobMessage job);
}
