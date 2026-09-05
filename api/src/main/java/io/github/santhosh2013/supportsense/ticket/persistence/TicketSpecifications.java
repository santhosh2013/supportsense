package io.github.santhosh2013.supportsense.ticket.persistence;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * BR-A10, expressed exactly once: LEAD/ADMIN see everything; everyone else sees only their
 * own team's tickets. Untriaged tickets (team_id NULL) fall out of this naturally — an
 * AGENT's predicate never matches a NULL team_id, so no special case is needed.
 *
 * <p>Composed into every {@link TicketRepository} finder. An ArchUnit rule asserts no
 * finder is called without it — reimplementing this per-query is how one endpoint
 * eventually forgets it.
 */
public final class TicketSpecifications {

    private TicketSpecifications() {}

    public static Specification<Ticket> visibleTo(User principal) {
        return (root, query, cb) -> {
            if (principal.getRole() == UserRole.LEAD || principal.getRole() == UserRole.ADMIN) {
                return cb.conjunction();
            }
            return sameTeamAs(principal, root, cb);
        };
    }

    private static Predicate sameTeamAs(
            User principal, jakarta.persistence.criteria.Root<Ticket> root, CriteriaBuilder cb) {
        Long principalTeamId = principal.getTeam() == null ? null : principal.getTeam().getId();
        if (principalTeamId == null) {
            // A team-less AGENT (shouldn't normally happen, but must not accidentally see
            // untriaged tickets via a NULL == NULL match) sees nothing.
            return cb.disjunction();
        }
        return cb.equal(root.get("team").get("id"), principalTeamId);
    }
}
