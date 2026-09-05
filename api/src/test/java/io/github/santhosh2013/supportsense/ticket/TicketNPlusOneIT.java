package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.CustomerTier;
import io.github.santhosh2013.supportsense.ticket.persistence.Team;
import io.github.santhosh2013.supportsense.ticket.persistence.TeamRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Category;
import io.github.santhosh2013.supportsense.ticket.persistence.CategoryRepository;
import io.github.santhosh2013.supportsense.ticket.persistence.Ticket;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketSpecifications;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * FR-4: "no N+1" on list endpoints. Proves @EntityGraph collapses category/team/assignee
 * loading into a fixed, small statement count regardless of how many tickets are returned —
 * asserting the count is the only way to actually prove N+1 is gone rather than assume it.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TicketNPlusOneIT {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionFactory sessionFactory;

    @Test
    @DisplayName("listing 10 tickets with category/team/assignee costs a fixed, small number of queries")
    void listingDoesNotScaleQueryCountWithRowCount() {
        Team team = teamRepository.save(new Team("Billing", "billing-" + System.nanoTime(), null));
        Category category = categoryRepository.save(
                new Category("Invoice", "invoice-" + System.nanoTime(), null, team, null, "P2", false));
        User assignee = userRepository.save(
                new User("assignee-" + System.nanoTime() + "@example.com", "hash", "Agent", UserRole.AGENT, team));
        User principal = userRepository.save(
                new User("principal-" + System.nanoTime() + "@example.com", "hash", "Lead", UserRole.LEAD, team));

        for (int i = 0; i < 10; i++) {
            Ticket ticket = new Ticket(
                    "ext-n1-" + System.nanoTime() + "-" + i,
                    "subject",
                    "body",
                    TicketChannel.WEB,
                    "customer@example.com",
                    CustomerTier.FREE);
            ticket.setTeam(team);
            ticket.setCategory(category);
            ticket.setAssignee(assignee);
            ticketRepository.save(ticket);
        }

        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();

        var page = ticketRepository.findAll(TicketSpecifications.visibleTo(principal), PageRequest.of(0, 20));
        // Force lazy associations to actually resolve — this is exactly where N+1 would
        // otherwise appear if @EntityGraph were missing.
        page.forEach(t -> {
            if (t.getCategory() != null) {
                t.getCategory().getName();
            }
            if (t.getTeam() != null) {
                t.getTeam().getName();
            }
            if (t.getAssignee() != null) {
                t.getAssignee().getEmail();
            }
        });

        long queryCount = statistics.getPrepareStatementCount();

        assertThat(page.getTotalElements()).isEqualTo(10);
        // A handful of queries (count query + one fetch-joined select, plus pagination
        // bookkeeping) — NOT 10 rows x 3 associations = 30+ if N+1 had crept back in.
        assertThat(queryCount).isLessThanOrEqualTo(5);
    }
}
