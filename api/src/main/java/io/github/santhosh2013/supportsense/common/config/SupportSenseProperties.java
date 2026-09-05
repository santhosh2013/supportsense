package io.github.santhosh2013.supportsense.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "supportsense")
public record SupportSenseProperties(Security security, Ingestion ingestion, PreScreen preScreen) {

    public record Security(
            String jwtSecret,
            String adminPassword,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {}

    public record Ingestion(
            @Min(1) int corePoolSize,
            @Min(1) int maxPoolSize,
            @Min(1) int queueCapacity,
            Duration staleClaimThreshold,
            @Min(1) int maxAttempts,
            Duration untriagedOrphanThreshold,
            String fallbackTeamSlug) {}

    /**
     * Terms are matched case-insensitively with word boundaries, never as substrings —
     * substring matching flags "sue" inside "issue".
     */
    public record PreScreen(@NotEmpty List<String> terms) {}
}
