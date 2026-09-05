package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Dispatches only after TicketInsertAttempt's REQUIRES_NEW transaction commits. */
@Component
public class TicketDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(TicketDispatchListener.class);

    private final IngestionDispatchPort dispatchPort;
    private final IngestionMetrics metrics;

    public TicketDispatchListener(IngestionDispatchPort dispatchPort, IngestionMetrics metrics) {
        this.dispatchPort = dispatchPort;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketCreated(TicketCreatedEvent event) {
        try {
            dispatchPort.dispatch(event.ticketId());
        } catch (RejectedExecutionException e) {
            // The ticket is committed PENDING, so it is not lost; the sweep redispatches it.
            metrics.incrementQueueRejected();
            log.info("Ingestion executor saturated for ticket {}; sweep will recover it", event.ticketId());
        }
    }
}