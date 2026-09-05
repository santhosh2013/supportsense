package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.CustomerTier;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.TeamRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketSpecifications;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * BR-A10: a user may see only tickets belonging to their team, unless LEAD or ADMIN.
 * Untriaged tickets (team_id NULL) fall out of the same predicate — no special case.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainer.class)
class TicketVisibilityIT {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("an agent sees a ticket owned by their own team")
    void agentSeesOwnTeamTicket() {
        Team billingTeam = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        User agent = userRepository.save(agentIn(billingTeam));
        Ticket ticket = ticketRepository.save(baselineTicket("ext-own-" + System.nanoTime()));
        ticket.setTeam(billingTeam);
        ticketRepository.save(ticket);

        var found = ticketRepository.findOne(
                TicketSpecifications.visibleTo(agent).and((root, q, cb) -> cb.equal(root.get("id"), ticket.getId())));

        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("an agent cannot see a ticket owned by another team")
    void agentCannotSeeOtherTeamTicket() {
        Team billingTeam = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        Team platformTeam = teamRepository.save(new Team("Platform", "platform-" + System.nanoTime(), null));
        User agent = userRepository.save(agentIn(billingTeam));
        Ticket ticket = ticketRepository.save(baselineTicket("ext-other-" + System.nanoTime()));
        ticket.setTeam(platformTeam);
        ticketRepository.save(ticket);

        var found = ticketRepository.findOne(
                TicketSpecifications.visibleTo(agent).and((root, q, cb) -> cb.equal(root.get("id"), ticket.getId())));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("an agent cannot see an untriaged ticket (team_id NULL)")
    void agentCannotSeeUntriagedTicket() {
        Team billingTeam = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        User agent = userRepository.save(agentIn(billingTeam));
        Ticket untriaged = ticketRepository.save(baselineTicket("ext-untriaged-" + System.nanoTime()));
        // team left null — simulates a ticket not yet classified.

        var found = ticketRepository.findOne(TicketSpecifications.visibleTo(agent)
                .and((root, q, cb) -> cb.equal(root.get("id"), untriaged.getId())));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("a lead sees an untriaged ticket")
    void leadSeesUntriagedTicket() {
        Team billingTeam = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        User lead = userRepository.save(leadIn(billingTeam));
        Ticket untriaged = ticketRepository.save(baselineTicket("ext-lead-untriaged-" + System.nanoTime()));

        var found = ticketRepository.findOne(TicketSpecifications.visibleTo(lead)
                .and((root, q, cb) -> cb.equal(root.get("id"), untriaged.getId())));

        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("a lead sees a ticket belonging to any team")
    void leadSeesAnyTeamTicket() {
        Team billingTeam = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        Team platformTeam = teamRepository.save(new Team("Platform", "platform-" + System.nanoTime(), null));
        User lead = userRepository.save(leadIn(billingTeam));
        Ticket ticket = ticketRepository.save(baselineTicket("ext-lead-any-" + System.nanoTime()));
        ticket.setTeam(platformTeam);
        ticketRepository.save(ticket);

        var found = ticketRepository.findOne(
                TicketSpecifications.visibleTo(lead).and((root, q, cb) -> cb.equal(root.get("id"), ticket.getId())));

        assertThat(found).isPresent();
    }

    private User agentIn(Team team) {
        return new User("agent-" + System.nanoTime() + "@example.com", "hash", "Agent", UserRole.AGENT, team);
    }

    private User leadIn(Team team) {
        return new User("lead-" + System.nanoTime() + "@example.com", "hash", "Lead", UserRole.LEAD, team);
    }

    private Ticket baselineTicket(String externalRef) {
        return new Ticket(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", CustomerTier.FREE);
    }
}
