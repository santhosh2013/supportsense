package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The single most important test in Batch 5: multiple Cloud Run instances run the sweep
 * concurrently by design, so the conditional claim MUST be provably exclusive — not merely
 * "no exception was thrown", but "exactly one caller's UPDATE affected a row".
 */
@Tag("integration")
@SpringBootTest
@Import(PostgresTestContainer.class)
class IngestionClaimConcurrencyIT {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("exactly one of 20 concurrent claims on the same PENDING row succeeds")
    void exactlyOneConcurrentClaimSucceeds() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com')",
                "ext-claim-race-" + System.nanoTime());
        long ticketId = jdbc.queryForObject(
                "SELECT id FROM tickets ORDER BY id DESC LIMIT 1", Long.class);

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger totalRowsClaimed = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    int claimed = ticketRepository.claimForProcessing(ticketId, Instant.now());
                    totalRowsClaimed.addAndGet(claimed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).isTrue();
        // The sum of "rows affected" across all 20 concurrent UPDATE attempts must be
        // exactly 1 — this is the actual concurrency guarantee, not an absence-of-exception
        // proxy for it.
        assertThat(totalRowsClaimed.get()).isEqualTo(1);

        Integer attemptCount = jdbc.queryForObject(
                "SELECT attempt_count FROM tickets WHERE id = ?", Integer.class, ticketId);
        assertThat(attemptCount).isEqualTo(1);
    }
}
