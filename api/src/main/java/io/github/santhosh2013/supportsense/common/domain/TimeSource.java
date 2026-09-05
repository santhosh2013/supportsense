package io.github.santhosh2013.supportsense.common.domain;

import java.time.Instant;

/**
 * Injected everywhere a timestamp is needed so SLA deadlines, the reaper's staleness
 * threshold and the 48h false-deflection window are deterministically testable.
 * ArchUnit forbids inline {@code Instant.now()} in production code.
 */
@FunctionalInterface
public interface TimeSource {

    Instant now();
}
