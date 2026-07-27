package com.hello.chatapp.storage;

/**
 * Normalizes S3/MinIO ETag values for comparison.
 * Providers and browsers may include surrounding double quotes.
 */
public final class ObjectEtagNormalizer {

    private ObjectEtagNormalizer() {
    }

    public static String normalize(String etag) {
        if (etag == null) {
            return "";
        }

        String trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static boolean matches(String clientEtag, String storageEtag) {
        return normalize(clientEtag).equalsIgnoreCase(normalize(storageEtag));
    }
}
