package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actual async worker. In milestone A1 there is no real classification (that lands in
 * A2) — "processing" here is the claim/complete lifecycle only, so A2 has a working pipeline
 * to slot classification into rather than needing to build the plumbing too.
 *
 * <p>Must be a separate bean from {@link TicketIngestionService}: {@code @Async} is a
 * Spring AOP proxy, and self-invocation (calling the method on {@code this}) bypasses the
 * proxy entirely, silently running synchronously.
 */
@Component
public class TicketIngestionWorker implements IngestionDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(TicketIngestionWorker.class);

    private final TicketRepository ticketRepository;
    private final TimeSource timeSource;

    public TicketIngestionWorker(TicketRepository ticketRepository, TimeSource timeSource) {
        this.ticketRepository = ticketRepository;
        this.timeSource = timeSource;
    }

    @Override
    @Async("ingestionExecutor")
    public void dispatch(Long ticketId) {
        // claimForProcessing is @Transactional on the repository interface itself, so this
        // is a genuine, independently-committed conditional update — no self-invocation
        // proxy-bypass risk, since the repository is always accessed through its own proxy.
        int claimed = ticketRepository.claimForProcessing(ticketId, timeSource.now());
        if (claimed == 0) {
            log.debug("Ticket {} already claimed elsewhere; skipping", ticketId);
            return;
        }

        ticketRepository.findById(ticketId).ifPresent(ticket -> {
            ticket.setIngestionState(IngestionState.DONE);
            ticketRepository.save(ticket);
        });
    }

    /**
     * Called from {@link io.github.santhosh2013.supportsense.common.config.IngestionUncaughtExceptionHandler}
     * on an uncaught async failure. Kept here (not in common.config) so the ArchUnit rule
     * confining TicketRepository access to ticket.app/ticket.persistence still holds.
     */
    @Transactional
    public void recordFailure(Long ticketId, String errorMessage) {
        ticketRepository.findById(ticketId).ifPresent(ticket -> {
            ticket.setIngestionState(IngestionState.FAILED);
            ticket.setIngestionError(errorMessage);
            ticketRepository.save(ticket);
        });
    }
}
