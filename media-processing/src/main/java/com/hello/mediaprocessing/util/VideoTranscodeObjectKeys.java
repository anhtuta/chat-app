package com.hello.mediaprocessing.util;

/**
 * Derives object-storage keys for transcoded playback assets.
 */
public final class VideoTranscodeObjectKeys {

    private VideoTranscodeObjectKeys() {
    }

    /**
     * Builds a sibling object key for the normalized MP4 next to the original upload.
     *
     * @param sourceObjectKey original object key from the processing job
     * @return object key ending in {@code .transcoded.mp4}
     */
    public static String derive(String sourceObjectKey) {
        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
            return "playback.transcoded.mp4";
        }
        int slash = sourceObjectKey.lastIndexOf('/');
        String directory = slash < 0 ? "" : sourceObjectKey.substring(0, slash + 1);
        String fileName = slash < 0 ? sourceObjectKey : sourceObjectKey.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        if (stem.isBlank()) {
            stem = "playback";
        }
        return directory + stem + ".transcoded.mp4";
    }
}
