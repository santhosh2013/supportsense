package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final ReaperItemProcessor reaperItemProcessor;
    private final OrphanTicketService orphanTicketService;

    public IngestionSweepService(
            TicketRepository ticketRepository,
            TimeSource timeSource,
            IngestionDispatchPort dispatchPort,
            IngestionMetrics metrics,
            SupportSenseProperties properties,
            ReaperItemProcessor reaperItemProcessor,
            OrphanTicketService orphanTicketService) {
        this.ticketRepository = ticketRepository;
        this.timeSource = timeSource;
        this.dispatchPort = dispatchPort;
        this.metrics = metrics;
        this.ingestionProperties = properties.ingestion();
        this.reaperItemProcessor = reaperItemProcessor;
        this.orphanTicketService = orphanTicketService;
    }

    /**
     * Redispatches a bounded batch of PENDING rows. The worker owns the conditional claim:
     * claiming before executor submission creates a stranded PROCESSING row if submission
     * is rejected. Keeping the row PENDING until the worker begins means rejection is
     * recoverable on the next sweep, as ADR-0015 requires.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void sweep() {
        List<Long> pendingIds = ticketRepository.findPendingIdsOrderByCreatedAt(PageRequest.of(0, SWEEP_BATCH_SIZE));

        for (Long ticketId : pendingIds) {
            try {
                dispatchPort.dispatch(ticketId);
                metrics.incrementSweepRedispatched();
            } catch (RejectedExecutionException e) {
                // Leave the row PENDING. It was never claimed, so the next sweep can retry.
                metrics.incrementQueueRejected();
                log.info("Ingestion executor saturated while sweeping ticket {}; will retry", ticketId);
            }
        }
    }

    /**
     * Resets rows stuck in PROCESSING past the staleness threshold (a pod died mid-work)
     * back to PENDING for the sweep to retry, or routes them to the fallback team once the
     * attempt cap is exhausted — never leaves them as FAILED with no owning team, which
     * would be invisible to every human (see the orphan-prevention decision).
     *
     * <p>Each ticket is processed via {@link ReaperItemProcessor} in its OWN REQUIRES_NEW
     * transaction, wrapped in its own try/catch — one ticket's failure neither rolls back
     * nor halts processing of the rest of the batch (a real bug found in code review:
     * the previous single-@Transactional-method-per-batch design meant one failure would
     * silently undo every other ticket's already-applied fix in the same sweep tick).
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void reap() {
        Instant staleBefore = timeSource.now().minus(ingestionProperties.staleClaimThreshold());
        List<Ticket> staleTickets = ticketRepository.findStaleProcessing(staleBefore);

        for (Ticket ticket : staleTickets) {
            try {
                reaperItemProcessor.processStaleTicket(ticket.getId());
            } catch (RuntimeException e) {
                log.error("Reaper failed to process stale ticket {}; will retry next tick", ticket.getId(), e);
            }
        }
    }

    /**
     * Routes tickets that were never claimed at all and have remained NEW/PENDING beyond
     * the configured orphan threshold. This is intentionally disjoint from reap(): these
     * rows have claimed_at NULL and state PENDING; reap() handles claimed PROCESSING rows.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void routeNeverClaimedOrphans() {
        Instant createdBefore = timeSource.now().minus(ingestionProperties.untriagedOrphanThreshold());
        List<Long> orphanIds = ticketRepository.findNeverClaimedOrphanIds(
                createdBefore, PageRequest.of(0, SWEEP_BATCH_SIZE));

        for (Long ticketId : orphanIds) {
            try {
                orphanTicketService.routeNeverClaimedOrphan(ticketId);
            } catch (RuntimeException e) {
                log.error("Failed to route never-claimed orphan ticket {}; will retry next tick", ticketId, e);
            }
        }
    }
}
