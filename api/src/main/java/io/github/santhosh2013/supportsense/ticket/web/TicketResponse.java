package io.github.santhosh2013.supportsense.ticket.web;

import java.time.Instant;

public record TicketResponse(
        Long id,
        String externalRef,
        String subject,
        String channel,
        String customerEmail,
        String customerTier,
        String status,
        String priority,
        Long categoryId,
        Long teamId,
        Long assigneeId,
        // Read-only. POST returns 202, so a client needs some way to observe whether the
        // accepted work finished. Deliberately excludes attemptCount/claimedAt/ingestionError.
        String ingestionState,
        Instant createdAt) {}
