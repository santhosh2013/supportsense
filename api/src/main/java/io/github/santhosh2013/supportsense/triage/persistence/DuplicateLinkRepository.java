package io.github.santhosh2013.supportsense.triage.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Bare repository only — see {@link DuplicateLink}'s javadoc for the A1 scope boundary. */
public interface DuplicateLinkRepository extends JpaRepository<DuplicateLink, Long> {}
