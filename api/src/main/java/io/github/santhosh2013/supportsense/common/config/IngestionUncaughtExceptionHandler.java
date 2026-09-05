package io.github.santhosh2013.supportsense.common.config;

import io.github.santhosh2013.supportsense.ticket.app.TicketIngestionWorker;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.ApplicationContext;

/**
 * A silent async failure that leaves a ticket stuck in PROCESSING forever is the classic bug
 * this design guards against — see ADR-0011. Persists FAILED with the error message rather
 * than losing the failure entirely. The reaper is the primary safety net for stuck rows;
 * this handles the case where the exception is thrown but the row was already claimed.
 *
 * <p>Not a Spring bean — constructed manually in {@link AsyncConfig}, so dependencies are
 * passed via constructor rather than {@code @Autowired}. Delegates the actual persistence
 * to {@link TicketIngestionWorker#recordFailure}, which lives in {@code ticket.app} — this
 * class stays in {@code common.config} but must not touch {@code TicketRepository}
 * directly, per the ArchUnit rule confining repository access to that package.
 */
public class IngestionUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionUncaughtExceptionHandler.class);

    private final ApplicationContext applicationContext;

    public IngestionUncaughtExceptionHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void handleUncaughtException(Throwable throwable, Method method, Object... params) {
        log.error("Uncaught exception in async ingestion method {}", method.getName(), throwable);

        if (params.length > 0 && params[0] instanceof Long ticketId) {
            persistFailure(ticketId, throwable);
        }
    }

    private void persistFailure(Long ticketId, Throwable throwable) {
        try {
            applicationContext.getBean(TicketIngestionWorker.class).recordFailure(ticketId, throwable.getMessage());
        } catch (Exception e) {
            log.error("Failed to persist ingestion failure for ticket {}", ticketId, e);
        }
    }
}
