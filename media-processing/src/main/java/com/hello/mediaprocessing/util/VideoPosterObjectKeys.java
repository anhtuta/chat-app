package com.hello.mediaprocessing.util;

/**
 * Derives object-storage keys for video poster thumbnails.
 */
public final class VideoPosterObjectKeys {

    private VideoPosterObjectKeys() {
    }

    /**
     * Builds a sibling object key for the derived poster image next to the original upload.
     *
     * @param sourceObjectKey original object key from the processing job
     * @return object key ending in {@code .thumbnail.jpg}
     */
    public static String derive(String sourceObjectKey) {
        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
            return "poster.thumbnail.jpg";
        }
        int slash = sourceObjectKey.lastIndexOf('/');
        String directory = slash < 0 ? "" : sourceObjectKey.substring(0, slash + 1);
        String fileName = slash < 0 ? sourceObjectKey : sourceObjectKey.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        if (stem.isBlank()) {
            stem = "poster";
        }
        return directory + stem + ".thumbnail.jpg";
    }
}
