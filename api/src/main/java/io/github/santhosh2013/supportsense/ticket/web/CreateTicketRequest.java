package io.github.santhosh2013.supportsense.ticket.web;

import io.github.santhosh2013.supportsense.ticket.persistence.CustomerTier;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTicketRequest(
        @NotBlank String externalRef,
        @NotBlank String subject,
        @NotBlank String body,
        @NotNull TicketChannel channel,
        @NotBlank @Email String customerEmail,
        CustomerTier customerTier) {}
