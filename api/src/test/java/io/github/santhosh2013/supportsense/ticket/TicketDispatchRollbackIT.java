package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.app.TicketInsertAttempt;
import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Verifies that TicketDispatchListener receives events only after a successful commit. */
@Tag("integration")
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@Import(PostgresTestContainer.class)
class TicketDispatchRollbackIT {

    @Autowired
    private TicketInsertAttempt insertAttempt;

        @MockBean
    private IngestionDispatchPort dispatchPort;

    @Test
    void rolledBackDuplicateInsertDispatchesNothing() {
        String externalRef = "ext-after-rollback-" + System.nanoTime();
        CreateTicketRequest request = new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);

        insertAttempt.tryInsert(request);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(dispatchPort).dispatch(org.mockito.ArgumentMatchers.anyLong()));
        reset(dispatchPort);

        assertThatThrownBy(() -> insertAttempt.tryInsert(request))
                .isInstanceOf(DataIntegrityViolationException.class);

        // If the REQUIRES_NEW transaction rolls back, TransactionPhase.AFTER_COMMIT means
        // TicketDispatchListener must not submit any work at all.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).untilAsserted(
                () -> verifyNoInteractions(dispatchPort));
    }
}