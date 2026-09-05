package io.github.santhosh2013.supportsense.auth.app;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {

    private final SecretKey signingKey;
    private final SupportSenseProperties.Security securityProperties;
    private final TimeSource timeSource;

    public JwtTokenService(SupportSenseProperties properties, TimeSource timeSource) {
        this.securityProperties = properties.security();
        this.timeSource = timeSource;
        this.signingKey = Keys.hmacShaKeyFor(
                securityProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        Instant now = timeSource.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(securityProperties.accessTokenTtl())))
                // Explicitly pinned rather than left to signWith's key-type inference —
                // code-review Should-fix #4: the prior code was very likely already safe
                // (verifyWith(SecretKey) structurally requires an HMAC signature and JJWT's
                // typed API cannot be tricked into accepting alg:none), but that was an
                // untested claim about library semantics. See JwtAlgorithmSecurityTest for
                // the forged-token proof.
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
