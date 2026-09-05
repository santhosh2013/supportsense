package io.github.santhosh2013.supportsense.ticket.web;

import io.github.santhosh2013.supportsense.ticket.app.BulkIngestionService;
import io.github.santhosh2013.supportsense.ticket.app.TicketIngestionService;
import io.github.santhosh2013.supportsense.ticket.app.TicketService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BULK_SIZE = 500;
    private static final String BULK_TOO_LARGE_PROBLEM_TYPE = "https://supportsense.dev/problems/bulk-too-large";

    private final TicketService ticketService;
    private final TicketIngestionService ingestionService;
    private final BulkIngestionService bulkIngestionService;

    public TicketController(
            TicketService ticketService,
            TicketIngestionService ingestionService,
            BulkIngestionService bulkIngestionService) {
        this.ticketService = ticketService;
        this.ingestionService = ingestionService;
        this.bulkIngestionService = bulkIngestionService;
    }

    /**
     * BR-A01/BR-A02: always 202 or 200 (existing ticket on a duplicate externalRef), never
     * 503. Per ADR-0015, an executor rejection is not surfaced here at all — the ticket is
     * already durably persisted and the sweep recovers it.
     */
    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketIngestionService.IngestionResult result = ingestionService.ingest(request);
        HttpStatus status = result.wasDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result.ticket());
    }

    /**
     * Bulk seed endpoint (sheet 06). Enforces a hard max of 500 — an oversized batch is
     * rejected with 400 rather than silently truncated. Every item independently commits
     * via {@code TicketIngestionService.ingest()}, which already isolates each insert in
     * its own transaction (TicketInsertAttempt REQUIRES_NEW) — one item's constraint
     * violation or duplicate never affects any other item's outcome. Partial success is
     * reported honestly: every input index has an explicit ACCEPTED/DUPLICATE/REJECTED
     * outcome, never a single all-or-nothing status.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    public ResponseEntity<BulkIngestResponse> createBulk(@RequestBody List<CreateTicketRequest> requests) {
        if (requests.size() > MAX_BULK_SIZE) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Bulk request contains " + requests.size() + " items; max is " + MAX_BULK_SIZE);
            problem.setType(URI.create(BULK_TOO_LARGE_PROBLEM_TYPE));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem.getDetail());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(bulkIngestionService.ingest(requests));
    }

    @GetMapping
    public ResponseEntity<Page<TicketResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(ticketService.listVisibleTickets(authentication.getName(), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getById(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getVisibleTicket(authentication.getName(), id));
    }
}
