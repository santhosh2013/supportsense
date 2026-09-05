package io.github.santhosh2013.supportsense.ticket.app;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.github.santhosh2013.supportsense.ticket.domain.IngestionDispatchPort;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.persistence.CustomerTier;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class IngestionSweepServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Mock private TicketRepository ticketRepository;
    @Mock private IngestionDispatchPort dispatchPort;
    @Mock private IngestionMetrics metrics;
    @Mock private ReaperItemProcessor reaperItemProcessor;
    @Mock private OrphanTicketService orphanTicketService;

    private IngestionSweepService service;

    @BeforeEach
    void setUp() {
        SupportSenseProperties properties = new SupportSenseProperties(
                new SupportSenseProperties.Security("secret", "password", Duration.ofMinutes(15), Duration.ofDays(7)),
                new SupportSenseProperties.Ingestion(
                        4, 8, 500, Duration.ofMinutes(15), 3, Duration.ofHours(1), "customer-success"),
                new SupportSenseProperties.PreScreen(List.of("refund")));
        TimeSource timeSource = () -> NOW;
        service = new IngestionSweepService(
                ticketRepository,
                timeSource,
                dispatchPort,
                metrics,
                properties,
                reaperItemProcessor,
                orphanTicketService);
    }

    @Test
    void oneFailingStaleItemDoesNotStopLaterItemsInTheBatch() {
        Ticket first = ticket(1L);
        Ticket failing = ticket(2L);
        Ticket last = ticket(3L);
        when(ticketRepository.findStaleProcessing(NOW.minus(Duration.ofMinutes(15))))
                .thenReturn(List.of(first, failing, last));
        doThrow(new IllegalStateException("bad fallback configuration"))
                .when(reaperItemProcessor)
                .processStaleTicket(2L);

        service.reap();

        verify(reaperItemProcessor).processStaleTicket(1L);
        verify(reaperItemProcessor).processStaleTicket(2L);
        verify(reaperItemProcessor).processStaleTicket(3L);
    }

    @Test
    void neverClaimedOrphanUsesInjectedClockAndConfiguredOneHourThreshold() {
        Instant cutoff = NOW.minus(Duration.ofHours(1));
        when(ticketRepository.findNeverClaimedOrphanIds(
                        org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(41L));

        service.routeNeverClaimedOrphans();

        verify(orphanTicketService).routeNeverClaimedOrphan(41L);
    }

    @Test
    void oneFailingNeverClaimedOrphanDoesNotStopLaterItems() {
        Instant cutoff = NOW.minus(Duration.ofHours(1));
        when(ticketRepository.findNeverClaimedOrphanIds(
                        org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(51L, 52L, 53L));
        doThrow(new IllegalStateException("bad fallback configuration"))
                .when(orphanTicketService)
                .routeNeverClaimedOrphan(52L);

        service.routeNeverClaimedOrphans();

        verify(orphanTicketService).routeNeverClaimedOrphan(51L);
        verify(orphanTicketService).routeNeverClaimedOrphan(52L);
        verify(orphanTicketService).routeNeverClaimedOrphan(53L);
    }

    private Ticket ticket(Long id) {
        Ticket ticket = new Ticket(
                "ext-" + id, "subject", "body", TicketChannel.WEB, "customer@example.com", CustomerTier.FREE);
        try {
            var field = Ticket.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(ticket, id);
            return ticket;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
