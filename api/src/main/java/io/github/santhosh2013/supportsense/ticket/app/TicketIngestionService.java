package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * BR-A01/BR-A02: the ticket row is committed BEFORE the executor is touched — the row IS the
 * work item, per ADR-0011. {@link TicketInsertAttempt} commits its own transaction
 * independently, so dispatch below only ever runs after that commit — the worker never
 * starts before the row is visible to other transactions/connections.
 *
 * <p>Per ADR-0015, a rejected dispatch is NOT data loss: the row is already durably
 * persisted with {@code ingestion_state=PENDING}, so the response is still 202 and the
 * scheduled sweep collects it — never a 503.
 */
@Service
public class TicketIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TicketIngestionService.class);

    private final TicketRepository ticketRepository;
    private final IngestionMetrics metrics;
    private final IngestionDispatchPort dispatchPort;
    private final TicketInsertAttempt insertAttempt;

    public TicketIngestionService(
            TicketRepository ticketRepository,
            IngestionMetrics metrics,
            IngestionDispatchPort dispatchPort,
            TicketInsertAttempt insertAttempt) {
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
        this.dispatchPort = dispatchPort;
        this.insertAttempt = insertAttempt;
    }

    public record IngestionResult(TicketResponse ticket, boolean wasDuplicate) {}

    /**
     * No @Transactional here: the insert itself runs in {@link TicketInsertAttempt}'s own
     * REQUIRES_NEW transaction, and the duplicate-path read below needs no transaction of
     * its own for a single findByExternalRef call.
     */
    public IngestionResult ingest(CreateTicketRequest request) {
        Ticket inserted = insertAttempt.tryInsert(request);
        if (inserted != null) {
            metrics.incrementAccepted();
            // The insert's transaction already committed by the time tryInsert returns
            // (REQUIRES_NEW suspends and resumes independently), so publishing here is
            // effectively "after commit" already — but @TransactionalEventListener still
            // requires an active transaction to attach to, so this event is published
            // eagerly instead and dispatch is attempted immediately, with rejection
            // handled the same way as the AFTER_COMMIT path.
            dispatchWithRejectionHandling(inserted.getId());
            return new IngestionResult(toResponse(inserted), false);
        }

        // BR-A02: the unique index on external_ref is the actual guard — a read-then-write
        // check would be racy under concurrent retries of the same payload. This read runs
        // after TicketInsertAttempt's own transaction has already committed or rolled back.
        metrics.incrementDuplicate();
        Ticket existing = ticketRepository
                .findByExternalRef(request.externalRef())
                .orElseThrow(() -> new IllegalStateException(
                        "Insert failed as a duplicate but no existing row was found for externalRef "
                                + request.externalRef()));
        return new IngestionResult(toResponse(existing), true);
    }

    private void dispatchWithRejectionHandling(Long ticketId) {
        try {
            dispatchPort.dispatch(ticketId);
        } catch (RejectedExecutionException e) {
            // Not data loss: the row is already durably PENDING. The sweep recovers it
            // within its interval. Superseded 503 behaviour — see ADR-0015.
            metrics.incrementQueueRejected();
            log.info("Ingestion executor saturated for ticket {}; sweep will recover it", ticketId);
        }
    }

    private TicketResponse toResponse(Ticket ticket) {
        Long categoryId = ticket.getCategory() == null ? null : ticket.getCategory().getId();
        Long teamId = ticket.getTeam() == null ? null : ticket.getTeam().getId();
        Long assigneeId = ticket.getAssignee() == null ? null : ticket.getAssignee().getId();
        return new TicketResponse(
                ticket.getId(),
                ticket.getExternalRef(),
                ticket.getSubject(),
                ticket.getChannel().name(),
                ticket.getCustomerEmail(),
                ticket.getCustomerTier().name(),
                ticket.getStatus().name(),
                ticket.getPriority() == null ? null : ticket.getPriority().name(),
                categoryId,
                teamId,
                assigneeId,
                ticket.getCreatedAt());
    }
}
