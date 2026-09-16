package com.hello.mediaprocessing.util;

/**
 * Derives object-storage keys for secondary MP4 renditions.
 */
public final class VideoRenditionObjectKeys {

    private VideoRenditionObjectKeys() {
    }

    /**
     * Builds a sibling object key for a height-labeled MP4 rendition next to the original upload.
     *
     * @param sourceObjectKey original object key from the processing job
     * @param heightLabel vertical resolution label such as {@code 480}
     * @return object key ending in {@code .<height>p.mp4}
     */
    public static String deriveHeight(String sourceObjectKey, int heightLabel) {
        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
            return "playback." + heightLabel + "p.mp4";
        }
        int slash = sourceObjectKey.lastIndexOf('/');
        String directory = slash < 0 ? "" : sourceObjectKey.substring(0, slash + 1);
        String fileName = slash < 0 ? sourceObjectKey : sourceObjectKey.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        if (stem.isBlank()) {
            stem = "playback";
        }
        return directory + stem + "." + heightLabel + "p.mp4";
    }
}
