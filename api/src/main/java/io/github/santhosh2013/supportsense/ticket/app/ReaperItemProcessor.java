package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes exactly one stale ticket per call, in its own {@code REQUIRES_NEW} transaction.
 * A separate bean from {@link IngestionSweepService} so the boundary is a genuine proxied
 * transaction, not a self-invoked one — see the earlier {@code TicketInsertAttempt} fix for
 * why self-invocation would silently bypass {@code REQUIRES_NEW}.
 *
 * <p>Isolating each ticket this way means one failure (e.g. a misconfigured fallback team)
 * cannot roll back the resets/routings already applied to unrelated tickets processed
 * earlier in the same sweep batch.
 */
@Service
public class ReaperItemProcessor {

    private final TicketRepository ticketRepository;
    private final OrphanTicketService orphanTicketService;
    private final SupportSenseProperties.Ingestion ingestionProperties;
    private final IngestionMetrics metrics;

    public ReaperItemProcessor(
            TicketRepository ticketRepository,
            OrphanTicketService orphanTicketService,
            SupportSenseProperties properties,
            IngestionMetrics metrics) {
        this.ticketRepository = ticketRepository;
        this.orphanTicketService = orphanTicketService;
        this.ingestionProperties = properties.ingestion();
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processStaleTicket(Long ticketId) {
        ticketRepository.findById(ticketId).ifPresent(ticket -> {
            if (ticket.getAttemptCount() >= ingestionProperties.maxAttempts()) {
                orphanTicketService.routeToFallbackTeam(ticket);
                metrics.incrementReaperExhausted();
            } else {
                ticket.setIngestionState(IngestionState.PENDING);
                ticketRepository.save(ticket);
                metrics.incrementReaperReset();
            }
        });
    }
}
