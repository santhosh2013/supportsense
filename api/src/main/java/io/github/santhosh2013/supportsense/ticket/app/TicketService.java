package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketSpecifications;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * BR-A10 is enforced here, once, by composing {@link TicketSpecifications#visibleTo} into
 * every read. A cross-team or untriaged lookup by an AGENT returns 404, never 403 — see
 * ADR-0006.
 */
@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    public TicketService(TicketRepository ticketRepository, UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Page<TicketResponse> listVisibleTickets(String principalEmail, Pageable pageable) {
        User principal = requirePrincipal(principalEmail);
        Specification<Ticket> visibility = TicketSpecifications.visibleTo(principal);
        return ticketRepository.findAll(visibility, pageable).map(this::toResponse);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public TicketResponse getVisibleTicket(String principalEmail, Long ticketId) {
        User principal = requirePrincipal(principalEmail);
        Specification<Ticket> visibility = TicketSpecifications.visibleTo(principal)
                .and((root, query, cb) -> cb.equal(root.get("id"), ticketId));

        Ticket ticket = ticketRepository
                .findOne(visibility)
                // 404, not 403 — existence itself must not leak across teams (ADR-0006).
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return toResponse(ticket);
    }

    private User requirePrincipal(String email) {
        return userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private TicketResponse toResponse(Ticket ticket) {
        Long categoryId = ticket.getCategory() == null ? null : ticket.getCategory().getId();
        Long teamId = ticket.getTeam() == null ? null : ticket.getTeam().getId();
        Long assigneeId = ticket.getAssignee() == null ? null : ticket.getAssignee().getId();
        return new TicketResponse(
                ticket.getId(),
                ticket.getExternalRef(),
                ticket.getSubject(),
                ticket.getChannel().name(),
                ticket.getCustomerEmail(),
                ticket.getCustomerTier().name(),
                ticket.getStatus().name(),
                ticket.getPriority() == null ? null : ticket.getPriority().name(),
                categoryId,
                teamId,
                assigneeId,
                ticket.getIngestionState().name(),
                ticket.getCreatedAt());
    }
}
