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
