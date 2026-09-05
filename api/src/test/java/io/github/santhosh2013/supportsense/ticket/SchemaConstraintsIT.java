package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the database-level invariants from ADR-0004 actually reject bad data — not merely
 * that good data can be inserted. These bypass JPA entirely and go straight at the schema,
 * because the guarantee these constraints provide holds for ANY caller, not just the ORM.
 */
@Tag("integration")
@SpringBootTest
@Import(PostgresTestContainer.class)
class SchemaConstraintsIT {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private long insertBaselineTicket(JdbcTemplate jdbc, String externalRef) {
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com')",
                externalRef);
        return jdbc.queryForObject(
                "SELECT id FROM tickets WHERE external_ref = ?", Long.class, externalRef);
    }

    // --- BR-A02: external_ref uniqueness -----------------------------------------------

    @Test
    @DisplayName("duplicate external_ref is rejected at the database level")
    void duplicateExternalRefRejected() {
        JdbcTemplate jdbc = jdbc();
        String ref = "ext-" + System.nanoTime();
        insertBaselineTicket(jdbc, ref);

        assertThatThrownBy(() -> insertBaselineTicket(jdbc, ref))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- triage_results.category_confidence: numeric(4,3) + CHECK(0..1) ----------------

    @Test
    @DisplayName("confidence above 1 is rejected")
    void confidenceAboveOneRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-" + System.nanoTime());

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, 1.001)",
                        ticketId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("confidence below 0 is rejected")
    void confidenceBelowZeroRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-" + System.nanoTime());

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, -0.001)",
                        ticketId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("confidence at the boundaries 0.000 and 1.000 is accepted")
    void confidenceAtBoundariesAccepted() {
        JdbcTemplate jdbc = jdbc();
        long lowTicket = insertBaselineTicket(jdbc, "ext-low-" + System.nanoTime());
        long highTicket = insertBaselineTicket(jdbc, "ext-high-" + System.nanoTime());

        jdbc.update(
                "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, 0.000)", lowTicket);
        jdbc.update(
                "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, 1.000)", highTicket);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM triage_results WHERE ticket_id IN (?, ?)",
                Integer.class,
                lowTicket,
                highTicket);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("a mid-range value like 0.847 round-trips exactly")
    void midRangeConfidenceRoundTrips() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-mid-" + System.nanoTime());

        jdbc.update(
                "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, 0.847)", ticketId);

        BigDecimal stored = jdbc.queryForObject(
                "SELECT category_confidence FROM triage_results WHERE ticket_id = ?", BigDecimal.class, ticketId);
        assertThat(stored).isEqualByComparingTo(new BigDecimal("0.847"));
    }

    @Test
    @DisplayName("numeric(4,3) rounds 0.8475 to 0.848 rather than truncating")
    void confidenceRoundsRatherThanTruncates() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-round-" + System.nanoTime());

        jdbc.update(
                "INSERT INTO triage_results (ticket_id, category_confidence) VALUES (?, 0.8475)", ticketId);

        BigDecimal stored = jdbc.queryForObject(
                "SELECT category_confidence FROM triage_results WHERE ticket_id = ?", BigDecimal.class, ticketId);
        assertThat(stored).isEqualByComparingTo(new BigDecimal("0.848"));
    }

    // --- duplicate_links: canonical ordering + uniqueness -------------------------------

    @Test
    @DisplayName("ticket_a_id > ticket_b_id is rejected by the canonical-order CHECK")
    void reversedPairRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketA = insertBaselineTicket(jdbc, "ext-a-" + System.nanoTime());
        long ticketB = insertBaselineTicket(jdbc, "ext-b-" + System.nanoTime());
        long smaller = Math.min(ticketA, ticketB);
        long larger = Math.max(ticketA, ticketB);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.9)",
                        larger,
                        smaller))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a self-link (a = b) is rejected by the canonical-order CHECK")
    void selfLinkRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-self-" + System.nanoTime());

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.9)",
                        ticketId,
                        ticketId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("inserting the same canonical pair twice is rejected by the unique index")
    void duplicatePairRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketA = insertBaselineTicket(jdbc, "ext-dupa-" + System.nanoTime());
        long ticketB = insertBaselineTicket(jdbc, "ext-dupb-" + System.nanoTime());
        long smaller = Math.min(ticketA, ticketB);
        long larger = Math.max(ticketA, ticketB);

        jdbc.update(
                "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.9)",
                smaller,
                larger);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.95)",
                        smaller,
                        larger))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the reversed pair (B,A) is rejected — proving the canonical rule is actually enforced")
    void reversedInsertOfAnAlreadyStoredPairRejected() {
        JdbcTemplate jdbc = jdbc();
        long ticketA = insertBaselineTicket(jdbc, "ext-revA-" + System.nanoTime());
        long ticketB = insertBaselineTicket(jdbc, "ext-revB-" + System.nanoTime());
        long smaller = Math.min(ticketA, ticketB);
        long larger = Math.max(ticketA, ticketB);

        jdbc.update(
                "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.9)",
                smaller,
                larger);

        // (B, A) has ticket_a_id > ticket_b_id — this is rejected by the CHECK itself,
        // which is a stronger guarantee than uniqueness alone: it is structurally
        // impossible to store the reversed pair, not merely rejected as a duplicate.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO duplicate_links (ticket_a_id, ticket_b_id, similarity) VALUES (?, ?, 0.9)",
                        larger,
                        smaller))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- ticket_events: reopen partial index correctness --------------------------------

    @Test
    @DisplayName("RESOLVED -> CLOSED is a forward closure, not a reopen, per the partial index predicate")
    void resolvedToClosedIsNotCountedAsReopen() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-close-" + System.nanoTime());

        jdbc.update(
                "INSERT INTO ticket_events (ticket_id, event_type, from_status, to_status) "
                        + "VALUES (?, 'STATUS_CHANGED', 'RESOLVED', 'CLOSED')",
                ticketId);

        Integer matchingReopenIndexPredicate = jdbc.queryForObject(
                "SELECT count(*) FROM ticket_events "
                        + "WHERE ticket_id = ? AND from_status IN ('RESOLVED','CLOSED') "
                        + "AND to_status NOT IN ('RESOLVED','CLOSED')",
                Integer.class,
                ticketId);

        assertThat(matchingReopenIndexPredicate).isZero();
    }

    @Test
    @DisplayName("CLOSED -> TRIAGED is a genuine reopen and matches the partial index predicate")
    void closedToTriagedIsCountedAsReopen() {
        JdbcTemplate jdbc = jdbc();
        long ticketId = insertBaselineTicket(jdbc, "ext-reopen-" + System.nanoTime());

        jdbc.update(
                "INSERT INTO ticket_events (ticket_id, event_type, from_status, to_status) "
                        + "VALUES (?, 'STATUS_CHANGED', 'CLOSED', 'TRIAGED')",
                ticketId);

        Integer matchingReopenIndexPredicate = jdbc.queryForObject(
                "SELECT count(*) FROM ticket_events "
                        + "WHERE ticket_id = ? AND from_status IN ('RESOLVED','CLOSED') "
                        + "AND to_status NOT IN ('RESOLVED','CLOSED')",
                Integer.class,
                ticketId);

        assertThat(matchingReopenIndexPredicate).isEqualTo(1);
    }

    @Test
    @DisplayName("the partial reopen index exists on ticket_events")
    void reopenIndexExists() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_event_reopen'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
