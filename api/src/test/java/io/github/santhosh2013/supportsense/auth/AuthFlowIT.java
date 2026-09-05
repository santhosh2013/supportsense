package io.github.santhosh2013.supportsense.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.auth.web.RefreshRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class AuthFlowIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        // The JDK's default HttpURLConnection has hardcoded retry logic for 401/407
        // responses that throws HttpRetryException("cannot retry due to server
        // authentication, in streaming mode") — this is unrelated to output streaming and
        // cannot be disabled via SimpleClientHttpRequestFactory. Apache HttpClient5 handles
        // auth-challenge responses correctly.
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("register then login returns a working access token")
    void registerThenLoginWorks() {
        String email = "agent-" + System.nanoTime() + "@example.com";

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Test Agent", null),
                AuthResponse.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().accessToken()).isNotBlank();

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                url("/api/auth/login"),
                new LoginRequest(email, "correct-horse-battery-staple"),
                AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("duplicate email registration returns 409")
    void duplicateEmailIsRejected() {
        String email = "dup-" + System.nanoTime() + "@example.com";
        RegisterRequest request = new RegisterRequest(email, "correct-horse-battery-staple", "Dup Agent", null);

        restTemplate.postForEntity(url("/api/auth/register"), request, AuthResponse.class);
        ResponseEntity<String> second =
                restTemplate.postForEntity(url("/api/auth/register"), request, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("login failure never reveals whether the account exists")
    void loginFailureMessageIsGeneric() throws Exception {
        String existingEmail = "exists-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(existingEmail, "correct-horse-battery-staple", "Existing", null),
                AuthResponse.class);

        ResponseEntity<String> wrongPasswordResponse = restTemplate.postForEntity(
                url("/api/auth/login"), new LoginRequest(existingEmail, "wrong-password"), String.class);
        ResponseEntity<String> noSuchAccountResponse = restTemplate.postForEntity(
                url("/api/auth/login"),
                new LoginRequest("nobody-" + System.nanoTime() + "@example.com", "irrelevant"),
                String.class);

        assertThat(wrongPasswordResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noSuchAccountResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode wrongPasswordBody = objectMapper.readTree(wrongPasswordResponse.getBody());
        JsonNode noSuchAccountBody = objectMapper.readTree(noSuchAccountResponse.getBody());
        assertThat(wrongPasswordBody.get("detail").asText())
                .isEqualTo(noSuchAccountBody.get("detail").asText());
    }

    @Test
    @DisplayName("replaying a rotated refresh token revokes the whole family")
    void refreshTokenReuseRevokesFamily() {
        String email = "theft-" + System.nanoTime() + "@example.com";

        AuthResponse registration = restTemplate
                .postForEntity(
                        url("/api/auth/register"),
                        new RegisterRequest(email, "correct-horse-battery-staple", "Theft Test", null),
                        AuthResponse.class)
                .getBody();
        assertThat(registration).isNotNull();
        String firstRefreshToken = registration.refreshToken();

        // Legitimate rotation: R1 -> R2.
        ResponseEntity<AuthResponse> rotateOnce =
                restTemplate.postForEntity(url("/api/auth/refresh"), new RefreshRequest(firstRefreshToken), AuthResponse.class);
        assertThat(rotateOnce.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondRefreshToken = rotateOnce.getBody().refreshToken();
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

        // Replay of the already-rotated R1 is theft — must be rejected.
        ResponseEntity<String> replay =
                restTemplate.postForEntity(url("/api/auth/refresh"), new RefreshRequest(firstRefreshToken), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // R2, though never itself replayed, must now be revoked as part of the family.
        ResponseEntity<String> descendantAlsoRevoked =
                restTemplate.postForEntity(url("/api/auth/refresh"), new RefreshRequest(secondRefreshToken), String.class);
        assertThat(descendantAlsoRevoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the taxonomy seed produces exactly 5 teams and 10 leaf categories")
    void seedProducesExpectedTaxonomy() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer teamCount = jdbc.queryForObject("SELECT count(*) FROM teams", Integer.class);
        Integer categoryCount = jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class);
        Integer adminCount =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class);

        assertThat(teamCount).isEqualTo(5);
        assertThat(categoryCount).isEqualTo(10);
        assertThat(adminCount).isEqualTo(1);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
