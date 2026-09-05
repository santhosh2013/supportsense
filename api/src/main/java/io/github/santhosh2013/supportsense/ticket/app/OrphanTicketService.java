package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.TeamRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A ticket that has exhausted its ingestion attempts must NOT become a dead end.
 * {@code team_id NULL} is a transient state measured in seconds — never a resting state
 * (see the untriaged-ticket-visibility design decision). Marking a row FAILED with no
 * owning team would make it invisible to every human, which is the exact failure this
 * routing exists to prevent.
 */
@Service
public class OrphanTicketService {

    private final TeamRepository teamRepository;
    private final TicketRepository ticketRepository;
    private final SupportSenseProperties.Ingestion ingestionProperties;

    public OrphanTicketService(
            TeamRepository teamRepository, TicketRepository ticketRepository, SupportSenseProperties properties) {
        this.teamRepository = teamRepository;
        this.ticketRepository = ticketRepository;
        this.ingestionProperties = properties.ingestion();
    }

    @Transactional
    public void routeToFallbackTeam(Ticket ticket) {
        applyFallbackRouting(ticket);
    }

    /**
     * Routes a never-claimed orphan in its own REQUIRES_NEW transaction. Calls the shared
     * private routing logic directly rather than {@link #routeToFallbackTeam} — that public
     * method is itself {@code @Transactional} (REQUIRED), and calling it via {@code this}
     * from within an already-active REQUIRES_NEW transaction is a self-invocation that
     * bypasses its proxy. It happens to be harmless here (REQUIRES_NEW already established
     * a transaction), but the pattern is exactly the defect class this codebase has been
     * burned by twice already — kept unambiguous rather than accidentally correct.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void routeNeverClaimedOrphan(Long ticketId) {
        ticketRepository.findById(ticketId).ifPresent(this::applyFallbackRouting);
    }

    private void applyFallbackRouting(Ticket ticket) {
        Team fallbackTeam = teamRepository
                .findBySlug(ingestionProperties.fallbackTeamSlug())
                .orElseThrow(() -> new IllegalStateException(
                        "Fallback team '" + ingestionProperties.fallbackTeamSlug() + "' is not seeded"));

        ticket.setTeam(fallbackTeam);
        ticket.setIngestionState(IngestionState.FAILED);
        // Recorded on the ticket only — TriageResult is persistence-only in A1 (no
        // classification pipeline exists yet to write a TriageResult row against).
        ticket.setIngestionError("Exceeded max ingestion attempts; routed to fallback team");
        ticketRepository.save(ticket);
    }
}
