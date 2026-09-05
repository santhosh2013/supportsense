package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovers ingestion work a pod restart or an executor rejection would otherwise strand —
 * see ADR-0011. Runs at startup (via {@code initialDelay=0}) and every 60s thereafter.
 *
 * <p>The sweep's batch size is bounded (100) — an unbounded scan of every PENDING row would
 * itself become a bottleneck as the table grows.
 */
@Service
public class IngestionSweepService {

    private static final Logger log = LoggerFactory.getLogger(IngestionSweepService.class);
    private static final int SWEEP_BATCH_SIZE = 100;

    private final TicketRepository ticketRepository;
    private final TimeSource timeSource;
    private final IngestionDispatchPort dispatchPort;
    private final IngestionMetrics metrics;
    private final SupportSenseProperties.Ingestion ingestionProperties;
    private final OrphanTicketService orphanTicketService;

    public IngestionSweepService(
            TicketRepository ticketRepository,
            TimeSource timeSource,
            IngestionDispatchPort dispatchPort,
            IngestionMetrics metrics,
            SupportSenseProperties properties,
            OrphanTicketService orphanTicketService) {
        this.ticketRepository = ticketRepository;
        this.timeSource = timeSource;
        this.dispatchPort = dispatchPort;
        this.metrics = metrics;
        this.ingestionProperties = properties.ingestion();
        this.orphanTicketService = orphanTicketService;
    }

    /**
     * Claims and redispatches PENDING rows — recovers rows dropped by a restart or an
     * executor rejection. Claim and dispatch are deliberately separate steps: the claim
     * commits on its own (see {@link TicketRepository#claimForProcessing}) before dispatch
     * is even attempted, so a slow dispatch never holds the claim's transaction open.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void sweep() {
        List<Long> pendingIds = ticketRepository.findPendingIdsOrderByCreatedAt(PageRequest.of(0, SWEEP_BATCH_SIZE));

        for (Long ticketId : pendingIds) {
            int claimed = ticketRepository.claimForProcessing(ticketId, timeSource.now());
            if (claimed == 1) {
                metrics.incrementSweepRedispatched();
                dispatchPort.dispatch(ticketId);
            }
        }
    }

    /**
     * Resets rows stuck in PROCESSING past the staleness threshold (a pod died mid-work)
     * back to PENDING for the sweep to retry, or routes them to the fallback team once the
     * attempt cap is exhausted — never leaves them as FAILED with no owning team, which
     * would be invisible to every human (see the orphan-prevention decision).
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    @Transactional
    public void reap() {
        Instant staleBefore = timeSource.now().minus(ingestionProperties.staleClaimThreshold());
        List<Ticket> staleTickets = ticketRepository.findStaleProcessing(staleBefore);

        for (Ticket ticket : staleTickets) {
            if (ticket.getAttemptCount() >= ingestionProperties.maxAttempts()) {
                orphanTicketService.routeToFallbackTeam(ticket);
                metrics.incrementReaperExhausted();
            } else {
                ticket.setIngestionState(IngestionState.PENDING);
                ticketRepository.save(ticket);
                metrics.incrementReaperReset();
            }
        }
    }
}
