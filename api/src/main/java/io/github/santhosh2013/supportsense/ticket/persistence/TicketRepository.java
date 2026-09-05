package io.github.santhosh2013.supportsense.ticket.persistence;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.NonNull;

/**
 * Visibility filtering (BR-A10) is applied via {@link TicketSpecifications#visibleTo} at
 * every call site, never reimplemented here.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByExternalRef(String externalRef);

    // Overrides the inherited method to add @EntityGraph — FR-4's "no N+1" requirement.
    @Override
    @EntityGraph(attributePaths = {"category", "team", "assignee"})
    @NonNull
    Page<Ticket> findAll(Specification<Ticket> spec, @NonNull Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"category", "team", "assignee"})
    @NonNull
    Optional<Ticket> findOne(Specification<Ticket> spec);
}


