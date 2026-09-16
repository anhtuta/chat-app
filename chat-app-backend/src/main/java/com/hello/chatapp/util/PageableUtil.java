package com.hello.chatapp.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PageableUtil {

    private PageableUtil() {}

    /**
     * Builds a {@link Pageable} from a validated page index and size.
     *
     * @param page zero-based page index; must be {@code >= 0}
     * @param size page size; must be {@code > 0}
     * @throws IllegalArgumentException if {@code page} or {@code size} is invalid
     */
    public static Pageable of(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        return PageRequest.of(page, size);
    }
}
