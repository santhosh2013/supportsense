package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-A10 at the HTTP boundary: cross-team access is 404, never 403 — see ADR-0006. Ticket
 * rows are inserted directly via JDBC since the ingestion endpoint lands in Batch 5.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketApiSecurityIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("an agent requesting another team's ticket gets 404, not 403")
    void crossTeamAccessReturns404NotFound() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long platformTeamId = teamId(jdbc, "platform-support");

        String agentEmail = "agent-" + System.nanoTime() + "@example.com";
        String accessToken = registerAndGetAccessToken(agentEmail, "billing-ops");

        long otherTeamTicketId = insertTicket(jdbc, "ext-cross-" + System.nanoTime(), platformTeamId);

        ResponseEntity<String> response = getTicket(otherTeamTicketId, accessToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an agent requesting an untriaged ticket gets 404")
    void untriagedTicketAccessReturns404() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String agentEmail = "agent-" + System.nanoTime() + "@example.com";
        String accessToken = registerAndGetAccessToken(agentEmail, "billing-ops");

        long untriagedTicketId = insertTicket(jdbc, "ext-untriaged-api-" + System.nanoTime(), null);

        ResponseEntity<String> response = getTicket(untriagedTicketId, accessToken, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an agent requesting their own team's ticket gets 200")
    void ownTeamAccessReturns200() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long billingTeamId = teamId(jdbc, "billing-ops");

        String agentEmail = "agent-" + System.nanoTime() + "@example.com";
        String accessToken = registerAndGetAccessToken(agentEmail, "billing-ops");

        long ownTeamTicketId = insertTicket(jdbc, "ext-own-api-" + System.nanoTime(), billingTeamId);

        ResponseEntity<TicketResponse> response = getTicket(ownTeamTicketId, accessToken, TicketResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(ownTeamTicketId);
    }

    @Test
    @DisplayName("an agent's ticket list excludes untriaged tickets and other teams' tickets")
    void listExcludesInvisibleTickets() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long billingTeamId = teamId(jdbc, "billing-ops");
        long platformTeamId = teamId(jdbc, "platform-support");

        String agentEmail = "agent-list-" + System.nanoTime() + "@example.com";
        String accessToken = registerAndGetAccessToken(agentEmail, "billing-ops");

        long visibleTicketId = insertTicket(jdbc, "ext-list-visible-" + System.nanoTime(), billingTeamId);
        insertTicket(jdbc, "ext-list-other-team-" + System.nanoTime(), platformTeamId);
        insertTicket(jdbc, "ext-list-untriaged-" + System.nanoTime(), null);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets?size=100"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":" + visibleTicketId);
        // Total element count in the page must reflect only the one visible ticket among
        // the three inserted — an agent must not even be able to infer the others exist
        // via an inflated count.
        assertThat(response.getBody()).contains("\"totalElements\":1");
    }

    private String registerAndGetAccessToken(String email, String teamSlug) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long teamId = teamId(jdbc, teamSlug);

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Test Agent", teamId),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private <T> ResponseEntity<T> getTicket(long ticketId, String accessToken, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(
                url("/api/tickets/" + ticketId), HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    private long teamId(JdbcTemplate jdbc, String slug) {
        return jdbc.queryForObject("SELECT id FROM teams WHERE slug = ?", Long.class, slug);
    }

    private long insertTicket(JdbcTemplate jdbc, String externalRef, Long teamId) {
        jdbc.update(
                "INSERT INTO tickets (external_ref, subject, body, channel, customer_email, team_id) "
                        + "VALUES (?, 'subject', 'body', 'WEB', 'customer@example.com', ?)",
                externalRef,
                teamId);
        return jdbc.queryForObject("SELECT id FROM tickets WHERE external_ref = ?", Long.class, externalRef);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
