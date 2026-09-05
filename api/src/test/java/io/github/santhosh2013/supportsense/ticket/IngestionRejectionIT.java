package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Proves AC-6 / ADR-0015 for real: a genuinely saturated executor still returns 202, with
 * the ticket durably persisted and the rejection counter incremented — never a slow 202 and
 * never a 503. A test that only exercises the unsaturated happy path proves nothing about
 * this behaviour; this test deliberately fills the executor with a latch-blocked task first.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // AsyncConfig already defines 'ingestionExecutor'; TinyExecutorConfig deliberately
        // replaces it with a core=1/max=1/queue=1 pool so saturation is actually reachable.
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import({PostgresTestContainer.class, IngestionRejectionIT.TinyExecutorConfig.class})
class IngestionRejectionIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Qualifier("ingestionExecutor")
    private ThreadPoolTaskExecutor ingestionExecutor;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("a saturated executor still returns 202; the ticket persists and the sweep later completes it")
    void saturatedExecutorStillReturns202() throws InterruptedException {
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);

        // core=1, max=1, queue=1 (see TinyExecutorConfig). One blocking task occupies the
        // sole worker thread; the queue can hold exactly one more. A third submission must
        // be rejected — this is the genuine saturation the AbortPolicy is meant to trigger.
        ingestionExecutor.execute(() -> {
            workerStarted.countDown();
            try {
                blockWorker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Fills the single queue slot.
        ingestionExecutor.execute(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        String externalRef = "ext-saturated-" + System.nanoTime();
        double rejectedBefore = meterRegistry.counter("ingestion.queue.rejected").count();
        String accessToken = registerAndGetAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<CreateTicketRequest> requestEntity = new HttpEntity<>(
                new CreateTicketRequest(
                        externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                headers);

        ResponseEntity<TicketResponse> response =
                restTemplate.exchange(url("/api/tickets"), HttpMethod.POST, requestEntity, TicketResponse.class);

        // Requirement: 202, not a slow 202, not 503 — see AC-6 (amended).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(meterRegistry.counter("ingestion.queue.rejected").count())
            .isEqualTo(rejectedBefore + 1);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ingestionState = jdbc.queryForObject(
                "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, externalRef);
        assertThat(ingestionState).isEqualTo("PENDING");

        blockWorker.countDown();

        // The sweep subsequently processes it — proving rejection is recoverable, not lost.
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            String state = jdbc.queryForObject(
                    "SELECT ingestion_state FROM tickets WHERE external_ref = ?", String.class, externalRef);
            assertThat(state).isEqualTo("DONE");
        });
    }

    private String registerAndGetAccessToken() {
        String email = "rejection-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Rejection Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration
    static class TinyExecutorConfig {

        @Bean(name = "ingestionExecutor")
        @Primary
        public ThreadPoolTaskExecutor ingestionExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setThreadNamePrefix("test-ingestion-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            executor.initialize();
            return executor;
        }
    }
}
