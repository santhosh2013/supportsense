package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * BR-A01/BR-A02: the ticket row is committed BEFORE the executor is touched — the row IS the
 * work item, per ADR-0011. {@link TicketInsertAttempt} publishes an event inside its own
 * transaction; {@link TicketDispatchListener} receives it only after that transaction
 * commits, so the worker never starts before the row is visible to another connection.
 *
 * <p>Per ADR-0015, a rejected dispatch is NOT data loss: the row is already durably
 * persisted with {@code ingestion_state=PENDING}, so the response is still 202 and the
 * scheduled sweep collects it — never a 503.
 */
@Service
public class TicketIngestionService {

    private final TicketRepository ticketRepository;
    private final IngestionMetrics metrics;
    private final TicketInsertAttempt insertAttempt;

    public TicketIngestionService(
            TicketRepository ticketRepository,
            IngestionMetrics metrics,
            TicketInsertAttempt insertAttempt) {
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
        this.insertAttempt = insertAttempt;
    }

    public record IngestionResult(TicketResponse ticket, boolean wasDuplicate) {}

    /**
     * No @Transactional here: the insert itself runs in {@link TicketInsertAttempt}'s own
     * REQUIRES_NEW transaction, and the duplicate-path read below needs no transaction of
     * its own for a single findByExternalRef call.
     */
    public IngestionResult ingest(CreateTicketRequest request) {
        try {
            Ticket inserted = insertAttempt.tryInsert(request);
            metrics.incrementAccepted();
            return new IngestionResult(toResponse(inserted), false);
        } catch (DataIntegrityViolationException duplicate) {
            return resolveDuplicate(request);
        }
    }

    private IngestionResult resolveDuplicate(CreateTicketRequest request) {
        // TicketInsertAttempt's REQUIRES_NEW transaction has already rolled back before
        // control reaches this catch path, so this read uses a healthy persistence context.
        metrics.incrementDuplicate();
        Ticket existing = ticketRepository
                .findByExternalRef(request.externalRef())
                .orElseThrow(() -> new IllegalStateException(
                        "Insert failed as a duplicate but no existing row was found for externalRef "
                                + request.externalRef()));
        return new IngestionResult(toResponse(existing), true);
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
