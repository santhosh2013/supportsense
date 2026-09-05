package io.github.santhosh2013.supportsense.ticket.persistence;

/**
 * The async pipeline state — deliberately orthogonal to {@link TicketStatus}. See ADR-0012:
 * merging this into the business-lifecycle enum would corrupt the BR-A09 transition machine.
 */
public enum IngestionState {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
