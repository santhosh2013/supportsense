package io.github.santhosh2013.supportsense.e2ealt;

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

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class BulkIngestionJourneyAltIT {

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
    @DisplayName("mixed bulk batch reports ACCEPTED, DUPLICATE, and REJECTED item outcomes")
    void mixedBatchReportsHonestPerItemOutcomes() {
        String adminToken = loginAsSeededAdmin();
        String duplicateRef = "ext-alt-bulk-dup-" + System.nanoTime();

        ResponseEntity<TicketResponse> preExisting = post(
                new CreateTicketRequest(duplicateRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                adminToken);
        assertThat(preExisting.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String acceptedRef = "ext-alt-bulk-accepted-" + System.nanoTime();
        String rejectedRef = "ext-alt-bulk-rejected-" + System.nanoTime();
        List<CreateTicketRequest> batch = List.of(
                new CreateTicketRequest(acceptedRef, "ok-subject", "ok-body", TicketChannel.WEB, "ok@example.com", null),
                new CreateTicketRequest(duplicateRef, "ok-subject", "ok-body", TicketChannel.WEB, "ok@example.com", null),
                new CreateTicketRequest(rejectedRef, "", "ok-body", TicketChannel.WEB, "ok@example.com", null));

        ResponseEntity<BulkIngestResponse> response = postBulk(batch, adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        BulkIngestResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accepted()).isEqualTo(1);
        assertThat(body.duplicates()).isEqualTo(1);
        assertThat(body.rejected()).isEqualTo(1);
        assertThat(body.items()).hasSize(3);

        BulkIngestResponse.BulkIngestItemResult acceptedItem = body.items().get(0);
        BulkIngestResponse.BulkIngestItemResult duplicateItem = body.items().get(1);
        BulkIngestResponse.BulkIngestItemResult rejectedItem = body.items().get(2);

        assertThat(acceptedItem.index()).isEqualTo(0);
        assertThat(acceptedItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.ACCEPTED);
        assertThat(acceptedItem.ticketId()).isNotNull();
        assertThat(acceptedItem.error()).isNull();

        assertThat(duplicateItem.index()).isEqualTo(1);
        assertThat(duplicateItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.DUPLICATE);
        assertThat(duplicateItem.ticketId()).isEqualTo(preExisting.getBody().id());
        assertThat(duplicateItem.error()).isNull();

        assertThat(rejectedItem.index()).isEqualTo(2);
        assertThat(rejectedItem.outcome()).isEqualTo(BulkIngestResponse.Outcome.REJECTED);
        assertThat(rejectedItem.ticketId()).isNull();
        assertThat(rejectedItem.error()).isNotNull();

        ResponseEntity<TicketResponse> fetchedAccepted = getTicket(acceptedItem.ticketId(), adminToken);
        assertThat(fetchedAccepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetchedAccepted.getBody().externalRef()).isEqualTo(acceptedRef);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                url("/api/tickets?size=100"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(adminToken)),
                String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).doesNotContain(rejectedRef);
    }

    @Test
    @DisplayName("501-item bulk request is rejected with 400 and sampled refs are not observable afterward")
    void oversizedBatchIsRejectedAndNothingObservableIsCreated() {
        String adminToken = loginAsSeededAdmin();
        String marker = "ext-alt-bulk-oversize-" + System.nanoTime();
        List<CreateTicketRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(new CreateTicketRequest(
                    marker + "-" + i,
                    "subject",
                    "body",
                    TicketChannel.WEB,
                    "oversize@example.com",
                    null));
        }

        ResponseEntity<String> response = postBulkExpectingError(tooMany, adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Full proof of "none of 501 created" is not available black-box because there is no
        // endpoint to query by externalRef. This checks representative refs via list content.
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
