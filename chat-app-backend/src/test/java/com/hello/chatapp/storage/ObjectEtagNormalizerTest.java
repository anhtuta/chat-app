package com.hello.chatapp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectEtagNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "'\"abc123\"', '\"abc123\"', true",
            "'\"abc123\"', 'abc123', true",
            "'abc123', '\"ABC123\"', true",
            "'\"abc123\"', '\"def456\"', false",
            "'etag-unavailable', '\"abc123\"', false"
    })
    void matches_normalizesQuotedValues(String clientEtag, String storageEtag, boolean expected) {
        assertThat(ObjectEtagNormalizer.matches(clientEtag, storageEtag)).isEqualTo(expected);
    }

    @Test
    void normalize_stripsSurroundingQuotes() {
        assertThat(ObjectEtagNormalizer.normalize("\"076305e376930b4f254e1ecdcdc08db0\""))
                .isEqualTo("076305e376930b4f254e1ecdcdc08db0");
    }
}
