package io.github.santhosh2013.supportsense.ticket.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately minimal in A1 — visibility filtering (BR-A10) is added as a Specification
 * fragment in a later batch and composed here, not reimplemented per-method.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByExternalRef(String externalRef);
}
