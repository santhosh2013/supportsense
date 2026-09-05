package io.github.santhosh2013.supportsense.auth.app;

import io.github.santhosh2013.supportsense.auth.domain.RefreshTokenPolicy;
import io.github.santhosh2013.supportsense.auth.domain.TokenHasher;
import io.github.santhosh2013.supportsense.auth.persistence.RefreshToken;
import io.github.santhosh2013.supportsense.auth.persistence.RefreshTokenRepository;
import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh tokens are hashed at rest, rotated on every use, and grouped into families so
 * replay of an already-rotated token can revoke every descendant token in one step.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final SupportSenseProperties.Security securityProperties;
    private final TimeSource timeSource;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            SupportSenseProperties properties,
            TimeSource timeSource,
            PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityProperties = properties.security();
        this.timeSource = timeSource;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public String issueNewFamily(User user) {
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public RotationResult rotate(String presentedRawToken) {
        String presentedHash = TokenHasher.sha256(presentedRawToken);
        RefreshToken token = refreshTokenRepository
                .findByTokenHash(presentedHash)
                .orElse(null);

        if (token == null) {
            return RotationResult.rejected();
        }

        Instant now = timeSource.now();
        RefreshTokenPolicy.Outcome outcome = RefreshTokenPolicy.evaluate(
                token.getRevokedAt() != null,
                token.getRotatedAt() != null,
                token.getExpiresAt().isBefore(now));

        return switch (outcome) {
            case ROTATE -> {
                token.markRotated(now);
                String newRawToken = issue(token.getUser(), token.getFamilyId());
                yield RotationResult.rotated(newRawToken, token.getUser());
            }
            case REUSE_DETECTED -> {
                log.warn(
                        "Refresh token reuse detected for family {} — revoking family", token.getFamilyId());
                revokeFamily(token.getFamilyId(), now);
                yield RotationResult.rejected();
            }
            case REVOKED, EXPIRED -> RotationResult.rejected();
        };
    }

    private void revokeFamily(UUID familyId, Instant now) {
        // REQUIRES_NEW: the caller (AuthService.refresh) throws BadCredentialsException in
        // the same call, and the default rollback rule would otherwise undo this write —
        // silently discarding the one security-critical side effect the 401 exists to
        // trigger. Committing independently means the revocation survives regardless of
        // what the caller's transaction does next.
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            List<RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
            family.forEach(t -> t.revoke(now));
            refreshTokenRepository.saveAll(family);
        });
    }

    private String issue(User user, UUID familyId) {
        Instant now = timeSource.now();
        String rawToken = generateRawToken();
        RefreshToken entity = new RefreshToken(
                user,
                TokenHasher.sha256(rawToken),
                familyId,
                now,
                now.plus(securityProperties.refreshTokenTtl()));
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RotationResult(boolean accepted, String newRawToken, User user) {

        static RotationResult rotated(String newRawToken, User user) {
            return new RotationResult(true, newRawToken, user);
        }

        static RotationResult rejected() {
            return new RotationResult(false, null, null);
        }
    }
}
