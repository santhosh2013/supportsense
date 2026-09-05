package io.github.santhosh2013.supportsense.ticket.web;

import io.github.santhosh2013.supportsense.ticket.persistence.TicketEventType;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketStatus;
import java.time.Instant;

public record TicketEventResponse(
        Long id,
        TicketEventType eventType,
        TicketStatus fromStatus,
        TicketStatus toStatus,
        Long actorUserId,
        Instant occurredAt) {}
