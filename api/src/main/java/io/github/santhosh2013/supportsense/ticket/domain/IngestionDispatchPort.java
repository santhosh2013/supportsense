package io.github.santhosh2013.supportsense.ticket.domain;

/**
 * The seam between "a ticket needs classification" and "how that work actually runs".
 * Kept as an interface so the pure domain never depends on Spring's executor types.
 */
public interface IngestionDispatchPort {

    void dispatch(Long ticketId);
}
