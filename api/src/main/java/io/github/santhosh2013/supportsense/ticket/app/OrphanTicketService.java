package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.ticket.persistence.IngestionState;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.TeamRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import org.springframework.stereotype.Service;
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
