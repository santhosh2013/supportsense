package io.github.santhosh2013.supportsense.e2ealt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.auth.web.RefreshRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.SecretKey;
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
class OpenApiContractDriftAltIT {

    private static final String TEST_JWT_SECRET =
            "test-secret-key-for-integration-tests-only-not-production-safe";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("hand-maintained and generated OpenAPI specs have matching path+method sets")
    void handMaintainedSpecMatchesGeneratedSpecPathsAndMethods() throws Exception {
        ResponseEntity<String> generatedResponse = restTemplate.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(generatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ObjectMapper jsonMapper = new ObjectMapper();
        JsonNode generatedSpec = jsonMapper.readTree(generatedResponse.getBody());
        Set<String> generatedPathMethods = extractPathMethods(generatedSpec);

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Path handMaintainedSpecPath = Path.of("src/main/resources/openapi.yaml").toAbsolutePath();
        assertThat(Files.exists(handMaintainedSpecPath)).isTrue();
        JsonNode handMaintainedSpec = yamlMapper.readTree(Files.readString(handMaintainedSpecPath));
        Set<String> handMaintainedPathMethods = extractPathMethods(handMaintainedSpec);

        Set<String> missingFromGenerated = new TreeSet<>(handMaintainedPathMethods);
        missingFromGenerated.removeAll(generatedPathMethods);

        Set<String> missingFromHandMaintained = new TreeSet<>(generatedPathMethods);
        missingFromHandMaintained.removeAll(handMaintainedPathMethods);

        assertThat(missingFromGenerated).isEmpty();
        assertThat(missingFromHandMaintained).isEmpty();
    }

    @Test
    @DisplayName("runtime negative-auth statuses are documented in openapi.yaml response codes")
    void observedNegativeAuthStatusesAreDocumented() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode openApi = yamlMapper.readTree(Files.readString(Path.of("src/main/resources/openapi.yaml").toAbsolutePath()));

        int missingAuthStatus = restTemplate
                .exchange(url("/api/tickets"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class)
            .getStatusCode()
            .value();
        assertDocumented(openApi, "/api/tickets", "get", missingAuthStatus);

        int expiredJwtStatus = getWithToken("/api/tickets", expiredAgentToken()).getStatusCode().value();
        assertDocumented(openApi, "/api/tickets", "get", expiredJwtStatus);

        int tamperedStatus = getWithToken("/api/tickets", tamperedToken()).getStatusCode().value();
        assertDocumented(openApi, "/api/tickets", "get", tamperedStatus);

        int algNoneStatus = getWithToken("/api/tickets", algNoneToken()).getStatusCode().value();
        assertDocumented(openApi, "/api/tickets", "get", algNoneStatus);

        String agentToken = registerAndGetAccessToken("alt-openapi-agent-" + System.nanoTime() + "@example.com");
        int wrongRoleBulkStatus = restTemplate.exchange(
                        url("/api/tickets/bulk"),
                        HttpMethod.POST,
                        new HttpEntity<>(List.of(sampleTicket()), authHeaders(agentToken)),
                        String.class)
            .getStatusCode()
            .value();
        assertDocumented(openApi, "/api/tickets/bulk", "post", wrongRoleBulkStatus);

        AuthResponse registration = restTemplate
                .postForEntity(
                        url("/api/auth/register"),
                        new RegisterRequest(
                                "alt-openapi-refresh-" + System.nanoTime() + "@example.com",
                                "correct-horse-battery-staple",
                                "OpenApi Alt",
                                null),
                        AuthResponse.class)
                .getBody();
        assertThat(registration).isNotNull();

        String firstRefresh = registration.refreshToken();
        ResponseEntity<AuthResponse> rotate =
                restTemplate.postForEntity(url("/api/auth/refresh"), new RefreshRequest(firstRefresh), AuthResponse.class);
        assertThat(rotate.getStatusCode()).isEqualTo(HttpStatus.OK);

        int replayStatus = restTemplate
                .postForEntity(url("/api/auth/refresh"), new RefreshRequest(firstRefresh), String.class)
            .getStatusCode()
            .value();
        assertDocumented(openApi, "/api/auth/refresh", "post", replayStatus);
    }

    private void assertDocumented(JsonNode openApi, String path, String method, int statusCode) {
        Set<String> documentedStatuses = responseStatusCodes(openApi, path, method);
        assertThat(documentedStatuses)
                .as("openapi.yaml %s %s should document observed HTTP %s", method.toUpperCase(Locale.ROOT), path, statusCode)
                .contains(Integer.toString(statusCode));
    }

    private Set<String> responseStatusCodes(JsonNode openApi, String path, String method) {
        Set<String> statuses = new TreeSet<>();
        JsonNode responses = openApi.path("paths").path(path).path(method).path("responses");
        Iterator<String> names = responses.fieldNames();
        while (names.hasNext()) {
            statuses.add(names.next());
        }
        return statuses;
    }

    private String expiredAgentToken() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("alt-openapi-expired@example.com")
                .claim("uid", 1L)
                .claim("role", "AGENT")
                .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
                .expiration(Date.from(now.minus(Duration.ofMinutes(1))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private String tamperedToken() {
        String valid = registerAndGetAccessToken("alt-openapi-tampered-" + System.nanoTime() + "@example.com");
        String[] parts = valid.split("\\.");
        return parts[0] + "." + parts[1] + ".tampered";
    }

    private String algNoneToken() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"attacker@example.com\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest(
                "ext-alt-openapi-" + System.nanoTime(),
                "subject",
                "body",
                TicketChannel.WEB,
                "customer@example.com",
                null);
    }

    private String registerAndGetAccessToken(String email) {
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "OpenApi Alt", null),
                AuthResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return registerResponse.getBody().accessToken();
    }

    private static Set<String> extractPathMethods(JsonNode spec) {
        Set<String> result = new TreeSet<>();
        JsonNode paths = spec.get("paths");
        if (paths == null) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
        while (pathEntries.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
            String path = pathEntry.getKey();
            Iterator<Map.Entry<String, JsonNode>> methodEntries = pathEntry.getValue().fields();
            while (methodEntries.hasNext()) {
                String method = methodEntries.next().getKey();
                if (isHttpMethod(method)) {
                    result.add(method.toUpperCase(Locale.ROOT) + " " + path);
                }
            }
        }
        return result;
    }

    private static boolean isHttpMethod(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "get", "post", "put", "delete", "patch", "options", "head", "trace" -> true;
            default -> false;
        };
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
