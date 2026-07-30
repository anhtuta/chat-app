package com.hello.chatapp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilTest {

    @Test
    void normalizeSqlLikeSearch_returnsNullForNullInput() {
        assertThat(StringUtil.normalizeSqlLikeSearch(null)).isNull();
    }

    @Test
    void normalizeSqlLikeSearch_returnsNullForBlankInput() {
        assertThat(StringUtil.normalizeSqlLikeSearch("")).isNull();
        assertThat(StringUtil.normalizeSqlLikeSearch("   ")).isNull();
        assertThat(StringUtil.normalizeSqlLikeSearch("\t\n")).isNull();
    }

    @Test
    void normalizeSqlLikeSearch_trimsAndWrapsWithPercentWildcards() {
        assertThat(StringUtil.normalizeSqlLikeSearch("  bob  ")).isEqualTo("%bob%");
        assertThat(StringUtil.normalizeSqlLikeSearch("Alice")).isEqualTo("%Alice%");
    }

    @Test
    void normalizeSqlLikeSearch_escapesLikeWildcardsAndBackslash() {
        assertThat(StringUtil.normalizeSqlLikeSearch("100%")).isEqualTo("%100\\%%");
        assertThat(StringUtil.normalizeSqlLikeSearch("a_b")).isEqualTo("%a\\_b%");
        assertThat(StringUtil.normalizeSqlLikeSearch("path\\name")).isEqualTo("%path\\\\name%");
        assertThat(StringUtil.normalizeSqlLikeSearch("%_\\")).isEqualTo("%\\%\\_\\\\%");
    }
}
