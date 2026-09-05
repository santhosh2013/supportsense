package io.github.santhosh2013.supportsense.ticket.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Batch 5 metrics — design §8. */
@Component
public class IngestionMetrics {

    private final Counter accepted;
    private final Counter duplicate;
    private final Counter queueRejected;
    private final Counter sweepRedispatched;
    private final Counter reaperReset;
    private final Counter reaperExhausted;

    public IngestionMetrics(MeterRegistry registry) {
        this.accepted = Counter.builder("ingestion.accepted")
                .description("Tickets accepted for ingestion")
                .register(registry);
        this.duplicate = Counter.builder("ingestion.duplicate")
                .description("BR-A02 idempotent duplicate hits")
                .register(registry);
        this.queueRejected = Counter.builder("ingestion.queue.rejected")
                .description("Ticket ingestion tasks rejected by the bounded executor")
                .register(registry);
        this.sweepRedispatched = Counter.builder("ingestion.sweep.redispatched")
                .description("Tickets claimed and redispatched by the sweep")
                .register(registry);
        this.reaperReset = Counter.builder("ingestion.reaper.reset")
                .description("Stuck PROCESSING rows reset to PENDING by the reaper")
                .register(registry);
        this.reaperExhausted = Counter.builder("ingestion.reaper.exhausted")
                .description("Rows marked FAILED after exhausting the attempt cap")
                .register(registry);
    }

    public void incrementAccepted() {
        accepted.increment();
    }

    public void incrementDuplicate() {
        duplicate.increment();
    }

    public void incrementQueueRejected() {
        queueRejected.increment();
    }

    public void incrementSweepRedispatched() {
        sweepRedispatched.increment();
    }

    public void incrementReaperReset() {
        reaperReset.increment();
    }

    public void incrementReaperExhausted() {
        reaperExhausted.increment();
    }
}
