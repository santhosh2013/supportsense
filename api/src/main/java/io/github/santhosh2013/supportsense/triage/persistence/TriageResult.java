package io.github.santhosh2013.supportsense.triage.persistence;

import io.github.santhosh2013.supportsense.ticket.persistence.Category;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * PERSISTENCE-ONLY in milestone A1 — no service class, no REST endpoint, no business logic
 * touches this entity until A2/A5. It exists so ddl-auto=validate is clean and A2 starts on
 * solid ground. See requirements FR-1.
 */
@Entity
@Table(name = "triage_results")
public class TriageResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predicted_category_id")
    private Category predictedCategory;

    @Column(name = "category_confidence")
    private BigDecimal categoryConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_priority")
    private TicketPriority predictedPriority;

    @Column(name = "priority_confidence")
    private BigDecimal priorityConfidence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predicted_team_id")
    private Team predictedTeam;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false)
    private boolean abstained;

    @Enumerated(EnumType.STRING)
    @Column(name = "abstention_reason", nullable = false)
    private AbstentionReason abstentionReason = AbstentionReason.NONE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TriageResult() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public Category getPredictedCategory() {
        return predictedCategory;
    }

    public BigDecimal getCategoryConfidence() {
        return categoryConfidence;
    }

    public TicketPriority getPredictedPriority() {
        return predictedPriority;
    }

    public BigDecimal getPriorityConfidence() {
        return priorityConfidence;
    }

    public Team getPredictedTeam() {
        return predictedTeam;
    }

    public String getModelName() {
        return modelName;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public boolean isAbstained() {
        return abstained;
    }

    public AbstentionReason getAbstentionReason() {
        return abstentionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
