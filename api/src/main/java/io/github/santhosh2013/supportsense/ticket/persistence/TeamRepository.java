package io.github.santhosh2013.supportsense.ticket.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findBySlug(String slug);
}
