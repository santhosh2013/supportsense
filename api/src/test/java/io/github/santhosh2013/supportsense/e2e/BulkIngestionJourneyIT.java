package io.github.santhosh2013.supportsense.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.BulkIngestResponse;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Bulk ingestion partial-success journeys — HTTP boundary only. Uses the seeded HTTP-only
 * ADMIN account (there is no HTTP path to create/promote SERVICE/ADMIN in A1) to satisfy
 * the endpoint's {@code @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class BulkIngestionJourneyIT {

    private static final String SEEDED_ADMIN_EMAIL = "admin@supportsense.local";
    private static final String SEEDED_ADMIN_PASSWORD = "test-admin-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("a mixed bulk batch reports ACCEPTED/DUPLICATE/REJECTED per item; accepted items are fetchable, rejected are not")
    void mixedBatchReportsHonestPerItemOutcomes() {
        String adminToken = loginAsSeededAdmin();
        String duplicateRef = "ext-bulke2e-dup-" + System.nanoTime();

        // Pre-existing ticket so one bulk item genuinely collides as a duplicate.
        ResponseEntity<TicketResponse> preExisting = post(
                new CreateTicketRequest(duplicateRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                adminToken);
        assertThat(preExisting.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String acceptedRef = "ext-bulke2e-accepted-" + System.nanoTime();
        String rejectedRef = "ext-bulke2e-rejected-" + System.nanoTime();
        List<CreateTicketRequest> batch = List.of(
                new CreateTicketRequest(acceptedRef, "s", "b", TicketChannel.WEB, "a@example.com", null),
                new CreateTicketRequest(duplicateRef, "s", "b", TicketChannel.WEB, "a@example.com", null),
                // Blank subject violates @NotBlank -> BulkIngestionService's validator rejects it.
                new CreateTicketRequest(rejectedRef, "", "b", TicketChannel.WEB, "a@example.com", null));

        ResponseEntity<BulkIngestResponse> response = postBulk(batch, adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        BulkIngestResponse body = response.getBody();
        assertThat(body.accepted()).isEqualTo(1);
        assertThat(body.duplicates()).isEqualTo(1);
        assertThat(body.rejected()).isEqualTo(1);
        assertThat(body.items()).hasSize(3);

        BulkIngestResponse.BulkIngestItemResult acceptedItem = body.items().get(0);
        BulkIngestResponse.BulkIngestItemResult duplicateItem = body.items().get(1);
        BulkIngestResponse.BulkIngestItemResult rejectedItem = body.items().get(2);

        assertThat(acceptedItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.ACCEPTED);
        assertThat(acceptedItem.ticketId()).isNotNull();
        assertThat(acceptedItem.error()).isNull();

        assertThat(duplicateItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.DUPLICATE);
        assertThat(duplicateItem.ticketId()).isEqualTo(preExisting.getBody().id());

        assertThat(rejectedItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.REJECTED);
        assertThat(rejectedItem.ticketId()).isNull();
        assertThat(rejectedItem.error()).isNotNull();

        // Accepted item is independently fetchable via GET.
        ResponseEntity<TicketResponse> fetchedAccepted =
                getTicket(acceptedItem.ticketId(), adminToken);
        assertThat(fetchedAccepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetchedAccepted.getBody().externalRef()).isEqualTo(acceptedRef);

        // Rejected item produced no ticket: the externalRef is not gettable via the list
        // endpoint (no get-by-externalRef endpoint exists; the list is checked for absence
        // of the ref rather than asserting a specific 404-by-id, since no id was ever minted).
        ResponseEntity<String> listResponse = restTemplate.exchange(
                url("/api/tickets?size=100"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)),
                String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).doesNotContain(rejectedRef);
    }

    @Test
    @DisplayName("a 501-item batch is rejected with 400 and none of its externalRefs become tickets")
    void oversizedBatchIsRejectedAndNothingIsCreated() {
        String adminToken = loginAsSeededAdmin();
        String marker = "ext-bulke2e-oversize-" + System.nanoTime();
        List<CreateTicketRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(new CreateTicketRequest(
                    marker + "-" + i, "s", "b", TicketChannel.WEB, "a@example.com", null));
        }

        ResponseEntity<String> response = postBulkExpectingError(tooMany, adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Best externally-observable check without a teams/list-all endpoint: sample a
        // handful of the batch's externalRefs and confirm the list page (sorted by
        // creation, most-recent-first is NOT guaranteed, so this filters by content
        // instead) never mentions them. This does not prove ALL 501 are absent — only
        // the sampled subset — which is the acknowledged limitation the mission brief
        // anticipates for this journey.
        ResponseEntity<String> listResponse = restTemplate.exchange(
                url("/api/tickets?size=100"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)),
                String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).doesNotContain(marker + "-0");
        assertThat(listResponse.getBody()).doesNotContain(marker + "-250");
        assertThat(listResponse.getBody()).doesNotContain(marker + "-500");
    }

    private ResponseEntity<TicketResponse> post(CreateTicketRequest request, String accessToken) {
        return restTemplate.exchange(
                url("/api/tickets"), HttpMethod.POST, new HttpEntity<>(request, authHeaders(accessToken)), TicketResponse.class);
    }

    private ResponseEntity<BulkIngestResponse> postBulk(List<CreateTicketRequest> items, String accessToken) {
        return restTemplate.exchange(
                url("/api/tickets/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(items, authHeaders(accessToken)),
                BulkIngestResponse.class);
    }

    private ResponseEntity<String> postBulkExpectingError(List<CreateTicketRequest> items, String accessToken) {
        return restTemplate.exchange(
                url("/api/tickets/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(items, authHeaders(accessToken)),
                String.class);
    }

    private ResponseEntity<TicketResponse> getTicket(long ticketId, String accessToken) {
        return restTemplate.exchange(
                url("/api/tickets/" + ticketId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                TicketResponse.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** Only the seeded bootstrap ADMIN is reachable over HTTP — no promotion endpoint exists in A1. */
    private String loginAsSeededAdmin() {
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                url("/api/auth/login"),
                new LoginRequest(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD),
                AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
