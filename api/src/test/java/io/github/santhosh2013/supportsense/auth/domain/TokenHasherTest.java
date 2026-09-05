package io.github.santhosh2013.supportsense.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    void sameInputProducesSameHash() {
        assertThat(TokenHasher.sha256("raw-token")).isEqualTo(TokenHasher.sha256("raw-token"));
    }

    @Test
    void differentInputProducesDifferentHash() {
        assertThat(TokenHasher.sha256("raw-token-a")).isNotEqualTo(TokenHasher.sha256("raw-token-b"));
    }

    @Test
    void hashIsNeverTheRawTokenItself() {
        String raw = "some-refresh-token-value";
        assertThat(TokenHasher.sha256(raw)).isNotEqualTo(raw);
    }

    @Test
    void hashIsA64CharacterHexString() {
        assertThat(TokenHasher.sha256("anything")).hasSize(64).matches("[0-9a-f]{64}");
    }
}
