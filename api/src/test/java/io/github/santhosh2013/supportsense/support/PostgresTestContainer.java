package io.github.santhosh2013.supportsense.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 16 for every integration test. H2 is never used — behaviour must match
 * production, and A3 onwards needs the vector extension this image carries.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle via Spring.
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("supportsense")
                .withUsername("supportsense")
                .withPassword("supportsense")
                .withReuse(true);
    }
}
