package com.hello.chatapp.util;

public final class StringUtil {

    private StringUtil() {}

    /**
     * Normalizes a user-provided search term for SQL {@code LIKE}.
     * Returns {@code null} for blank input, otherwise escapes {@code \}, {@code %},
     * and {@code _} and wraps the value with {@code %} for substring matching.
     */
    public static String normalizeSqlLikeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Escape LIKE wildcards so user input is treated literally.
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
