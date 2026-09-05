package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.app.IngestionSweepService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reaper correctness (design §2.4 / ADR-0011): only resets rows genuinely stuck in
 * PROCESSING past the staleness threshold — must never touch terminal states, PENDING rows,
 * or rows with a null claimed_at. Exhausted rows are routed to the fallback team, never left
 * as a dead-end FAILED-with-no-team (see the orphan-prevention decision).
 */
@Tag("integration")
@SpringBootTest
@Import(PostgresTestContainer.class)
class IngestionReaperIT {

    @Autowired
    private IngestionSweepService sweepService;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("a PROCESSING row stale beyond the threshold is reset to PENDING")
    void staleProcessingRowIsReset() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ref = "ext-reaper-stale-" + System.nanoTime();
        Instant staleClaim = Instant.now().minus(20, ChronoUnit.MINUTES);

        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 1)",
                ref,
                java.sql.Timestamp.from(staleClaim));

        sweepService.reap();

        String state = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, ref);
        assertThat(state).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a PROCESSING row within the staleness threshold is left untouched")
    void freshProcessingRowIsUntouched() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ref = "ext-reaper-fresh-" + System.nanoTime();
        Instant recentClaim = Instant.now().minus(2, ChronoUnit.MINUTES);

        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 1)",
                ref,
                java.sql.Timestamp.from(recentClaim));

        sweepService.reap();

        String state = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, ref);
        assertThat(state).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("PENDING and DONE rows are never touched by the reaper regardless of age")
    void terminalAndPendingStatesAreNeverTouchedByReaper() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String pendingRef = "ext-reaper-pending-" + System.nanoTime();
        String doneRef = "ext-reaper-done-" + System.nanoTime();
        Instant longAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, created_at) VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', "
                        + "'PENDING', ?)",
                pendingRef,
                java.sql.Timestamp.from(longAgo));
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at) VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', "
                        + "'DONE', ?)",
                doneRef,
                java.sql.Timestamp.from(longAgo));

        sweepService.reap();

        String pendingState = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, pendingRef);
        String doneState = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, doneRef);
        assertThat(pendingState).isEqualTo("PENDING");
        assertThat(doneState).isEqualTo("DONE");
    }

    @Test
    @DisplayName("a stale row with claimed_at NULL is never touched (defensive — should not occur in practice)")
    void staleRowWithNullClaimedAtIsUntouched() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ref = "ext-reaper-nullclaim-" + System.nanoTime();

        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, ingestion_state) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING')",
                ref);

        sweepService.reap();

        String state = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, ref);
        assertThat(state).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("one ticket's reaper failure does not roll back other tickets processed in the same sweep tick")
    void oneFailingTicketDoesNotRollBackUnrelatedTicketsInTheSameBatch() {
        // Regression test for a real bug found in code review: reap() originally ran the
        // entire batch loop in one @Transactional method, so a single failure (here:
        // routeToFallbackTeam throwing because the fallback team is temporarily missing)
        // would roll back the resets already applied to unrelated stale tickets earlier in
        // the same tick. This test fails on the pre-fix code and passes after switching to
        // per-ticket REQUIRES_NEW processing via ReaperItemProcessor.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant staleClaim = Instant.now().minus(20, ChronoUnit.MINUTES);

        String validRefBefore = "ext-reaper-mixed-valid-before-" + System.nanoTime();
        String invalidRef = "ext-reaper-mixed-invalid-" + System.nanoTime();
        String validRefAfter = "ext-reaper-mixed-valid-after-" + System.nanoTime();

        // Two ordinary stale rows (attempt_count below the cap — the reset branch).
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 1)",
                validRefBefore,
                java.sql.Timestamp.from(staleClaim));
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 1)",
                validRefAfter,
                java.sql.Timestamp.from(staleClaim));
        // One exhausted stale row — the exhausted branch, which will fail because the
        // fallback team is temporarily deleted below.
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 3)",
                invalidRef,
                java.sql.Timestamp.from(staleClaim));

        // Temporarily remove the fallback team so routeToFallbackTeam throws for invalidRef.
        Long fallbackTeamId =
                jdbc.queryForObject("SELECT id FROM teams WHERE slug = 'customer-success'", Long.class);
        String fallbackTeamSlug = jdbc.queryForObject(
                "SELECT slug FROM teams WHERE id = ?", String.class, fallbackTeamId);
        jdbc.update("UPDATE teams SET slug = 'customer-success-temporarily-renamed' WHERE id = ?", fallbackTeamId);

        try {
            sweepService.reap();
        } finally {
            jdbc.update("UPDATE teams SET slug = ? WHERE id = ?", fallbackTeamSlug, fallbackTeamId);
        }

        String stateBefore = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, validRefBefore);
        String stateAfter = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, validRefAfter);
        String invalidState = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, invalidRef);

        // The two valid tickets MUST be reset to PENDING despite the third ticket's failure —
        // proving the failure did not roll back their already-applied fix.
        assertThat(stateBefore).isEqualTo("PENDING");
        assertThat(stateAfter).isEqualTo("PENDING");
        // The failing ticket remains PROCESSING (its own REQUIRES_NEW transaction rolled
        // back), to be retried on the next tick once the fallback team is restored.
        assertThat(invalidState).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("a row that has exhausted the attempt cap is routed to the fallback team, not left as a dead-end FAILED")
    void exhaustedAttemptsRouteToFallbackTeam() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ref = "ext-reaper-exhausted-" + System.nanoTime();
        Instant staleClaim = Instant.now().minus(20, ChronoUnit.MINUTES);

        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, "
                        + "ingestion_state, claimed_at, attempt_count) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', 'PROCESSING', ?, 3)",
                ref,
                java.sql.Timestamp.from(staleClaim));

        sweepService.reap();

        Long fallbackTeamId = jdbc.queryForObject(
                "SELECT id FROM teams WHERE slug = 'customer-success'", Long.class);
        Long assignedTeamId = jdbc.queryForObject(
                "SELECT team_id FROM tickets WHERE external_ref = ?", Long.class, ref);
        String state = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, ref);

        // The ticket MUST have an owning team — a FAILED row with team_id NULL is invisible
        // to every human, exactly the outcome the orphan-prevention decision exists to stop.
        assertThat(assignedTeamId).isEqualTo(fallbackTeamId);
        assertThat(state).isEqualTo("FAILED");
    }
}
