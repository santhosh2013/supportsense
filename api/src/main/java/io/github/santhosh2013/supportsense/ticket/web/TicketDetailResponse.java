package io.github.santhosh2013.supportsense.ticket.web;

import java.util.List;

public record TicketDetailResponse(TicketResponse ticket, List<TicketEventResponse> events) {}
