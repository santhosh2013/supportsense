package io.github.santhosh2013.supportsense.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.domain.RefreshTokenPolicy.Outcome;
import org.junit.jupiter.api.Test;

/** Pure unit test — no Spring, no JPA. Encodes the refresh-token theft-detection rule. */
class RefreshTokenPolicyTest {

    @Test
    void rotatesAFreshValidToken() {
        assertThat(RefreshTokenPolicy.evaluate(false, false, false)).isEqualTo(Outcome.ROTATE);
    }

    @Test
    void detectsReuseOfAnAlreadyRotatedToken() {
        assertThat(RefreshTokenPolicy.evaluate(false, true, false)).isEqualTo(Outcome.REUSE_DETECTED);
    }

    @Test
    void reuseTakesPrecedenceOverExpiry() {
        // A rotated-and-now-expired token is still a reuse signal, not merely "expired".
        assertThat(RefreshTokenPolicy.evaluate(false, true, true)).isEqualTo(Outcome.REUSE_DETECTED);
    }

    @Test
    void revokedTokenIsRejectedRegardlessOfOtherState() {
        assertThat(RefreshTokenPolicy.evaluate(true, true, true)).isEqualTo(Outcome.REVOKED);
        assertThat(RefreshTokenPolicy.evaluate(true, false, false)).isEqualTo(Outcome.REVOKED);
    }

    @Test
    void expiredButNeverRotatedIsSimplyExpired() {
        assertThat(RefreshTokenPolicy.evaluate(false, false, true)).isEqualTo(Outcome.EXPIRED);
    }
}
