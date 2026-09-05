package io.github.santhosh2013.supportsense.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.ticket.persistence.TicketStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** AC-7: every legal and illegal transition pair, parameterised over the full status enum. */
class TicketStatusTransitionsTest {

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void everyPairIsExhaustivelyChecked(TicketStatus from) {
        for (TicketStatus to : TicketStatus.values()) {
            boolean legal = TicketStatusTransitions.isLegal(from, to);
            boolean inAllowedTargets = TicketStatusTransitions.allowedTargets(from).contains(to);
            assertThat(legal).isEqualTo(inAllowedTargets);
        }
    }

    @Test
    void newCanMoveToTriagedOrDuplicate() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.NEW, TicketStatus.TRIAGED)).isTrue();
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.NEW, TicketStatus.DUPLICATE)).isTrue();
    }

    @Test
    void triagedCanMoveToInProgressOrDuplicate() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.TRIAGED, TicketStatus.IN_PROGRESS))
                .isTrue();
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.TRIAGED, TicketStatus.DUPLICATE))
                .isTrue();
    }

    @Test
    void inProgressCanMoveToPendingCustomerOrResolved() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.IN_PROGRESS, TicketStatus.PENDING_CUSTOMER))
                .isTrue();
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED))
                .isTrue();
    }

    @Test
    void pendingCustomerCanOnlyMoveToResolved() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.PENDING_CUSTOMER, TicketStatus.RESOLVED))
                .isTrue();
        assertThat(TicketStatusTransitions.allowedTargets(TicketStatus.PENDING_CUSTOMER))
                .containsExactly(TicketStatus.RESOLVED);
    }

    @Test
    void resolvedCanOnlyMoveToClosed() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.RESOLVED, TicketStatus.CLOSED)).isTrue();
        assertThat(TicketStatusTransitions.allowedTargets(TicketStatus.RESOLVED))
                .containsExactly(TicketStatus.CLOSED);
    }

    @Test
    void closedAndDuplicateAreTerminal() {
        assertThat(TicketStatusTransitions.allowedTargets(TicketStatus.CLOSED)).isEmpty();
        assertThat(TicketStatusTransitions.allowedTargets(TicketStatus.DUPLICATE)).isEmpty();
    }

    @Test
    void duplicateIsOnlyReachableFromNewOrTriaged() {
        EnumSet<TicketStatus> statusesThatCanReachDuplicate = EnumSet.noneOf(TicketStatus.class);
        for (TicketStatus from : TicketStatus.values()) {
            if (TicketStatusTransitions.isLegal(from, TicketStatus.DUPLICATE)) {
                statusesThatCanReachDuplicate.add(from);
            }
        }
        assertThat(statusesThatCanReachDuplicate).containsExactlyInAnyOrder(
                TicketStatus.NEW, TicketStatus.TRIAGED);
    }

    @Test
    void illegalBackwardsTransitionsAreRejected() {
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.NEW, TicketStatus.RESOLVED)).isFalse();
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.CLOSED, TicketStatus.NEW)).isFalse();
        assertThat(TicketStatusTransitions.isLegal(TicketStatus.RESOLVED, TicketStatus.DUPLICATE))
                .isFalse();
    }

    @Test
    void selfTransitionIsIllegal() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(TicketStatusTransitions.isLegal(status, status)).isFalse();
        }
    }
}
