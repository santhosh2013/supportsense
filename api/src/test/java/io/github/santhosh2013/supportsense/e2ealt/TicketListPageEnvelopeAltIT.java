package io.github.santhosh2013.supportsense.e2ealt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.HashSet;
import java.util.Set;
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
class TicketListPageEnvelopeAltIT {

    private static final String SEEDED_ADMIN_EMAIL = "admin@supportsense.local";
    private static final String SEEDED_ADMIN_PASSWORD = "test-admin-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("GET /api/tickets returns Spring Page envelope and includes created refs")
    void listReturnsPageEnvelopeAndReflectsCreatedTickets() throws Exception {
        String adminToken = loginAsSeededAdmin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        int createdCount = 3;
        Set<String> createdRefs = new HashSet<>();
        for (int i = 0; i < createdCount; i++) {
            String externalRef = "ext-alt-page-" + System.nanoTime() + "-" + i;
            createdRefs.add(externalRef);
            ResponseEntity<TicketResponse> created = restTemplate.exchange(
                    url("/api/tickets"),
                    HttpMethod.POST,
                    new HttpEntity<>(
                            new CreateTicketRequest(
                                    externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                            headers),
                    TicketResponse.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets?page=0&size=100"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.has("content")).isTrue();
        assertThat(body.has("totalElements")).isTrue();
        assertThat(body.has("totalPages")).isTrue();
        assertThat(body.has("number")).isTrue();
        assertThat(body.has("size")).isTrue();
        assertThat(body.has("first")).isTrue();
        assertThat(body.has("last")).isTrue();
        assertThat(body.has("numberOfElements")).isTrue();
        assertThat(body.has("empty")).isTrue();

        assertThat(body.get("totalElements").asLong()).isGreaterThanOrEqualTo(createdCount);

        Set<String> foundRefs = new HashSet<>();
        for (JsonNode item : body.get("content")) {
            String ref = item.get("externalRef").asText();
            if (createdRefs.contains(ref)) {
                foundRefs.add(ref);
            }
        }
        assertThat(foundRefs).isEqualTo(createdRefs);
    }

    @Test
    @DisplayName("size query parameter is clamped to 100")
    void sizeIsClampedTo100() throws Exception {
        String adminToken = loginAsSeededAdmin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets?page=0&size=1000"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("size").asInt()).isEqualTo(100);
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
