package io.github.santhosh2013.supportsense.auth.domain;

/**
 * Pure decision logic for refresh-token rotation — zero Spring, zero JPA. Kept separate from
 * {@code RefreshTokenService} so the theft-detection rule is unit-testable without a
 * database or a Spring context.
 */
public final class RefreshTokenPolicy {

    private RefreshTokenPolicy() {}

    public enum Outcome {
        /** The token is the current, unrotated, unexpired member of its family. Rotate it. */
        ROTATE,
        /** The token has already been rotated once — this is a replay. Revoke the family. */
        REUSE_DETECTED,
        /** The token or family has already been revoked. */
        REVOKED,
        /** The token is expired but was never rotated or revoked. */
        EXPIRED
    }

    public static Outcome evaluate(boolean revoked, boolean alreadyRotated, boolean expired) {
        if (revoked) {
            return Outcome.REVOKED;
        }
        if (alreadyRotated) {
            return Outcome.REUSE_DETECTED;
        }
        if (expired) {
            return Outcome.EXPIRED;
        }
        return Outcome.ROTATE;
    }
}
