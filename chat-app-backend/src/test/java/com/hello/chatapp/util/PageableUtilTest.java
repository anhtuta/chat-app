package com.hello.chatapp.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilTest {

    @Test
    void of_clampsNegativePageAndInvalidSizeToDefaults() {
        Pageable pageable = PageableUtil.of(-3, 0, 100, 100);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void of_capsSizeAtMax() {
        Pageable pageable = PageableUtil.of(2, 500, 100, 100);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void of_keepsValidPageAndSize() {
        Pageable pageable = PageableUtil.of(1, 50, 100, 100);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    void of_rejectsInvalidDefaultOrMaxSize() {
        assertThatThrownBy(() -> PageableUtil.of(0, 10, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultSize must be > 0");

        assertThatThrownBy(() -> PageableUtil.of(0, 10, 100, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSize must be >= defaultSize");
    }
}
