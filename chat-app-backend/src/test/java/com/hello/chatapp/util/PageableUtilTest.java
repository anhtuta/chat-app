package com.hello.chatapp.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilTest {

    @Test
    void of_keepsValidPageAndSize() {
        Pageable pageable = PageableUtil.of(1, 50);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    void of_rejectsNegativePage() {
        assertThatThrownBy(() -> PageableUtil.of(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must be >= 0");
    }

    @Test
    void of_rejectsNonPositiveSize() {
        assertThatThrownBy(() -> PageableUtil.of(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0");

        assertThatThrownBy(() -> PageableUtil.of(0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0");
    }
}
