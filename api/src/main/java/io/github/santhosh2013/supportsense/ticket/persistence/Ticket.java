package io.github.santhosh2013.supportsense.ticket.persistence;

import io.github.santhosh2013.supportsense.auth.persistence.User;
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
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * The central entity. Two independent state machines live here by design (ADR-0012):
 * {@code status} is the BR-A09 business lifecycle; {@code ingestionState} is the async
 * pipeline (ADR-0011) and has nothing to do with the ticket's support lifecycle.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_ref", nullable = false)
    private String externalRef;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketChannel channel;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    /** Point-in-time snapshot, not an FK — see ADR-0001. */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_tier", nullable = false)
    private CustomerTier customerTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status = TicketStatus.NEW;

    @Enumerated(EnumType.STRING)
    private TicketPriority priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_state", nullable = false)
    private IngestionState ingestionState = IngestionState.PENDING;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "ingestion_error")
    private String ingestionError;

    @Column(name = "auto_answered", nullable = false)
    private boolean autoAnswered;

    @Column(name = "auto_answered_at")
    private Instant autoAnsweredAt;

    @Column(name = "first_resolved_at")
    private Instant firstResolvedAt;

    @Column(name = "resolution_text")
    private String resolutionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_by")
    private ResolvedBy resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Version
    private int version;

    protected Ticket() {
        // JPA
    }

    public Ticket(
            String externalRef,
            String subject,
            String body,
            TicketChannel channel,
            String customerEmail,
            CustomerTier customerTier) {
        this.externalRef = externalRef;
        this.subject = subject;
        this.body = body;
        this.channel = channel;
        this.customerEmail = customerEmail;
        this.customerTier = customerTier;
        this.status = TicketStatus.NEW;
        this.ingestionState = IngestionState.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public TicketChannel getChannel() {
        return channel;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public CustomerTier getCustomerTier() {
        return customerTier;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public void setPriority(TicketPriority priority) {
        this.priority = priority;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public User getAssignee() {
        return assignee;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public IngestionState getIngestionState() {
        return ingestionState;
    }

    public void setIngestionState(IngestionState ingestionState) {
        this.ingestionState = ingestionState;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getIngestionError() {
        return ingestionError;
    }

    public void setIngestionError(String ingestionError) {
        this.ingestionError = ingestionError;
    }

    public boolean isAutoAnswered() {
        return autoAnswered;
    }

    public void setAutoAnswered(boolean autoAnswered) {
        this.autoAnswered = autoAnswered;
    }

    public Instant getAutoAnsweredAt() {
        return autoAnsweredAt;
    }

    public void setAutoAnsweredAt(Instant autoAnsweredAt) {
        this.autoAnsweredAt = autoAnsweredAt;
    }

    public Instant getFirstResolvedAt() {
        return firstResolvedAt;
    }

    public void setFirstResolvedAt(Instant firstResolvedAt) {
        this.firstResolvedAt = firstResolvedAt;
    }

    public String getResolutionText() {
        return resolutionText;
    }

    public void setResolutionText(String resolutionText) {
        this.resolutionText = resolutionText;
    }

    public ResolvedBy getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(ResolvedBy resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public void setSlaDueAt(Instant slaDueAt) {
        this.slaDueAt = slaDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFirstResponseAt() {
        return firstResponseAt;
    }

    public void setFirstResponseAt(Instant firstResponseAt) {
        this.firstResponseAt = firstResponseAt;
    }

    public int getVersion() {
        return version;
    }
}
