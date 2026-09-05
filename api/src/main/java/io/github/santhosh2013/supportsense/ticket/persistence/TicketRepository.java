package io.github.santhosh2013.supportsense.ticket.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * The conditional claim (ADR-0011). Proceed only if exactly one row is affected — that
     * is what makes this safe across multiple concurrent Cloud Run instances with no
     * distributed lock. Bumps {@code attempt_count} on every claim, including the first.
     */
    @Modifying
    @Transactional
    @Query(
            "update Ticket t set t.ingestionState = io.github.santhosh2013.supportsense.ticket.persistence.IngestionState.PROCESSING, "
                    + "t.claimedAt = :now, t.attemptCount = t.attemptCount + 1 "
                    + "where t.id = :id and t.ingestionState = io.github.santhosh2013.supportsense.ticket.persistence.IngestionState.PENDING")
    int claimForProcessing(@Param("id") Long id, @Param("now") Instant now);

    /** The sweep's source of work — PENDING rows the executor never got to (or rejected). */
    @Query(
            "select t.id from Ticket t where t.ingestionState = io.github.santhosh2013.supportsense.ticket.persistence.IngestionState.PENDING "
                    + "order by t.createdAt")
    List<Long> findPendingIdsOrderByCreatedAt(org.springframework.data.domain.Pageable pageable);

    /** The reaper's source of work — rows stuck in PROCESSING past the staleness threshold. */
    @Query(
            "select t from Ticket t where t.ingestionState = io.github.santhosh2013.supportsense.ticket.persistence.IngestionState.PROCESSING "
                    + "and t.claimedAt is not null and t.claimedAt < :staleBefore")
    List<Ticket> findStaleProcessing(@Param("staleBefore") Instant staleBefore);
}


