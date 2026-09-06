package io.github.santhosh2013.supportsense.e2ealt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class TicketIdempotencyJourneyAltIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("same externalRef posted sequentially returns 202 then 200 with the same ticket id")
    void sequentialDuplicatePostsConvergeToSameTicket() {
        String accessToken = registerAndGetAccessToken("alt-idem-seq-" + System.nanoTime() + "@example.com");
        String externalRef = "ext-alt-idem-seq-" + System.nanoTime();
        CreateTicketRequest request = new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);

        ResponseEntity<TicketResponse> first = post(request, accessToken);
        ResponseEntity<TicketResponse> second = post(request, accessToken);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
    }

    @Test
    @DisplayName("same externalRef posted concurrently by 5 callers converges to one ticket id")
    void concurrentDuplicatePostsConvergeToSameTicket() throws InterruptedException {
        String accessToken = registerAndGetAccessToken("alt-idem-conc-" + System.nanoTime() + "@example.com");
        String externalRef = "ext-alt-idem-conc-" + System.nanoTime();
        CreateTicketRequest request = new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<ResponseEntity<TicketResponse>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    responses.add(post(request, accessToken));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(threadCount);
        assertThat(responses).allMatch(
                r -> r.getStatusCode() == HttpStatus.ACCEPTED || r.getStatusCode() == HttpStatus.OK);

        long acceptedCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count();
        long okCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertThat(acceptedCount).isEqualTo(1);
        assertThat(okCount).isEqualTo(threadCount - 1);

        long distinctIds = responses.stream().map(r -> r.getBody().id()).distinct().count();
        assertThat(distinctIds).isEqualTo(1);

        Long ticketId = responses.get(0).getBody().id();
        ResponseEntity<TicketResponse> followupGet = get(ticketId, accessToken);
        assertThat(followupGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(followupGet.getBody().externalRef()).isEqualTo(externalRef);
    }

    private ResponseEntity<TicketResponse> post(CreateTicketRequest request, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(
                url("/api/tickets"), HttpMethod.POST, new HttpEntity<>(request, headers), TicketResponse.class);
    }

    private ResponseEntity<TicketResponse> get(Long id, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(
                url("/api/tickets/" + id), HttpMethod.GET, new HttpEntity<>(headers), TicketResponse.class);
    }

    private String registerAndGetAccessToken(String email) {
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Idempotency Alt", null),
                AuthResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return registerResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
