package com.hello.chatapp.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PageableUtil {

    private PageableUtil() {}

    /**
     * Builds a {@link Pageable} with a non-negative page index and a clamped page size.
     *
     * @param page zero-based page index; negative values become {@code 0}
     * @param size requested page size; {@code <= 0} falls back to {@code defaultSize}
     * @param defaultSize size used when {@code size} is missing/invalid; must be {@code > 0}
     * @param maxSize upper bound for {@code size}; values above this are capped
     */
    public static Pageable of(int page, int size, int defaultSize, int maxSize) {
        if (defaultSize <= 0) {
            throw new IllegalArgumentException("defaultSize must be > 0");
        }
        if (maxSize < defaultSize) {
            throw new IllegalArgumentException("maxSize must be >= defaultSize");
        }
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? defaultSize : Math.min(size, maxSize);
        return PageRequest.of(safePage, safeSize);
    }
}
