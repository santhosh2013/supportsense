package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.ticket.persistence.CustomerTier;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean (not a private method on {@link TicketIngestionService}) specifically so
 * {@code @Transactional(REQUIRES_NEW)} actually applies — self-invocation from within the
 * same class bypasses Spring's transactional proxy entirely.
 *
 * <p>The insert runs in its OWN transaction so a unique-constraint violation rolls back
 * only this attempt, not the caller's outer transaction. Catching
 * {@link DataIntegrityViolationException} inside the SAME transaction that threw it does
 * not work — Spring marks that transaction rollback-only the instant the exception is
 * thrown, so any subsequent read in it would fail or see a poisoned persistence context.
 */
@Service
public class TicketInsertAttempt {

    private final TicketRepository ticketRepository;

    public TicketInsertAttempt(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /** Returns the persisted ticket, or {@code null} if externalRef already existed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ticket tryInsert(CreateTicketRequest request) {
        CustomerTier tier = request.customerTier() == null ? CustomerTier.FREE : request.customerTier();
        Ticket ticket = new Ticket(
                request.externalRef(), request.subject(), request.body(), request.channel(),
                request.customerEmail(), tier);
        try {
            ticketRepository.saveAndFlush(ticket);
            return ticket;
        } catch (DataIntegrityViolationException e) {
            return null;
        }
    }
}
