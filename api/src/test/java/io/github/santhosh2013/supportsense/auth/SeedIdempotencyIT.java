package io.github.santhosh2013.supportsense.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

/**
 * AC-13: re-running the seed migration must never duplicate rows. Flyway itself will not
 * re-execute an already-applied version, so this test replays the seed SQL directly against
 * the same schema to prove the ON CONFLICT upserts are genuinely idempotent.
 */
@Tag("integration")
@SpringBootTest
@Import(PostgresTestContainer.class)
class SeedIdempotencyIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("re-applying the seed script twice more still yields 5 teams and 10 categories")
    void seedScriptIsIdempotent() throws Exception {
        String seedSql = StreamUtils.copyToString(
                new ClassPathResource("db/migration/V2__seed_taxonomy.sql").getInputStream(),
                StandardCharsets.UTF_8);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(seedSql);
        jdbc.execute(seedSql);

        Integer teamCount = jdbc.queryForObject("SELECT count(*) FROM teams", Integer.class);
        Integer categoryCount = jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class);
        Integer adminCount =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class);

        assertThat(teamCount).isEqualTo(5);
        assertThat(categoryCount).isEqualTo(10);
        assertThat(adminCount).isEqualTo(1);
    }
}
