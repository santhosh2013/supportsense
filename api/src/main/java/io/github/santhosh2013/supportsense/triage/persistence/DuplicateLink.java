package io.github.santhosh2013.supportsense.triage.persistence;

import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
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
import io.github.santhosh2013.supportsense.auth.persistence.User;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * PERSISTENCE-ONLY in milestone A1 — see {@link TriageResult}'s javadoc. Pairs are stored
 * in canonical order ({@code ticketA.id < ticketB.id}), enforced by a database CHECK, so
 * A-dup-B and B-dup-A cannot both exist as distinct rows.
 */
@Entity
@Table(name = "duplicate_links")
public class DuplicateLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_a_id", nullable = false)
    private Ticket ticketA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_b_id", nullable = false)
    private Ticket ticketB;

    @Column(nullable = false)
    private BigDecimal similarity;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private User confirmedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DuplicateStatus status = DuplicateStatus.SUGGESTED;

    protected DuplicateLink() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicketA() {
        return ticketA;
    }

    public Ticket getTicketB() {
        return ticketB;
    }

    public BigDecimal getSimilarity() {
        return similarity;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public User getConfirmedBy() {
        return confirmedBy;
    }

    public DuplicateStatus getStatus() {
        return status;
    }
}
