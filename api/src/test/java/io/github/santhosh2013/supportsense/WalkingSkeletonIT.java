package io.github.santhosh2013.supportsense;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The walking skeleton: real PostgreSQL, real Flyway, real HTTP.
 *
 * <p>Docker is unavailable on the development machine, so this test proves the CI pipeline
 * genuinely executes Testcontainers rather than silently skipping them. If this is green on
 * the runner, every later integration test has a working foundation.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class WalkingSkeletonIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("actuator health reports UP against a real database")
    void healthEndpointIsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Flyway applied V1 and ddl-auto=validate found no drift")
    void migrationsApplied() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isPositive();

        assertThat(tableExists(jdbc, "teams")).isTrue();
        assertThat(tableExists(jdbc, "categories")).isTrue();
        assertThat(tableExists(jdbc, "users")).isTrue();
    }

    @Test
    @DisplayName("the case-insensitive email uniqueness index exists")
    void emailIndexIsCaseInsensitive() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_users_email_lower'", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                table);
        return count != null && count == 1;
    }
}
