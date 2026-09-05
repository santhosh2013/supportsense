package io.github.santhosh2013.supportsense.ticket.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketEventRepository extends JpaRepository<TicketEvent, Long> {}
