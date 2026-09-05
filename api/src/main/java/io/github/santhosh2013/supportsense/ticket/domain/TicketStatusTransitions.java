package io.github.santhosh2013.supportsense.ticket.domain;

import io.github.santhosh2013.supportsense.ticket.persistence.TicketStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * BR-A09's transition map — pure, zero Spring/JPA imports. The map is the single source of
 * truth for legal transitions; the 409 response body's "allowed targets" list is derived
 * from it, never hand-maintained separately.
 */
public final class TicketStatusTransitions {

    private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_TRANSITIONS = new EnumMap<>(TicketStatus.class);

    static {
        LEGAL_TRANSITIONS.put(
                TicketStatus.NEW, EnumSet.of(TicketStatus.TRIAGED, TicketStatus.DUPLICATE));
        LEGAL_TRANSITIONS.put(
                TicketStatus.TRIAGED, EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.DUPLICATE));
        LEGAL_TRANSITIONS.put(
                TicketStatus.IN_PROGRESS,
                EnumSet.of(TicketStatus.PENDING_CUSTOMER, TicketStatus.RESOLVED));
        LEGAL_TRANSITIONS.put(TicketStatus.PENDING_CUSTOMER, EnumSet.of(TicketStatus.RESOLVED));
        LEGAL_TRANSITIONS.put(TicketStatus.RESOLVED, EnumSet.of(TicketStatus.CLOSED));
        LEGAL_TRANSITIONS.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
        LEGAL_TRANSITIONS.put(TicketStatus.DUPLICATE, EnumSet.noneOf(TicketStatus.class));
    }

    private TicketStatusTransitions() {}

    public static boolean isLegal(TicketStatus from, TicketStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** Used to populate the 409 ProblemDetail body — never hand-maintained separately. */
    public static Set<TicketStatus> allowedTargets(TicketStatus from) {
        return Set.copyOf(LEGAL_TRANSITIONS.getOrDefault(from, Set.of()));
    }
}
