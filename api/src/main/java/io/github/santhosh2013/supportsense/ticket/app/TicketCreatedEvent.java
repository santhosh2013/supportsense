package io.github.santhosh2013.supportsense.ticket.app;

/** Published inside the insert transaction and delivered only after it commits. */
public record TicketCreatedEvent(Long ticketId) {}
