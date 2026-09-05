package io.github.santhosh2013.supportsense.ticket.web;

import io.github.santhosh2013.supportsense.ticket.app.TicketService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
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
