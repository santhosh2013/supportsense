package io.github.santhosh2013.supportsense.ticket.web;

import java.util.List;

/** Partial-success response: every input has an explicit outcome. */
public record BulkIngestResponse(
        int accepted,
        int duplicates,
        int rejected,
        List<BulkIngestItemResult> items) {

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    public record BulkIngestItemResult(
            int index,
            String externalRef,
            Outcome outcome,
            Long ticketId,
            String error) {}
}
