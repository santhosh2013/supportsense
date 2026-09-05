package io.github.santhosh2013.supportsense.ticket.app;

import io.github.santhosh2013.supportsense.ticket.web.BulkIngestResponse;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Validates and records an independent, explicit result for every bulk-ingestion item. */
@Service
public class BulkIngestionService {

    private final TicketIngestionService ingestionService;
    private final Validator validator;

    public BulkIngestionService(TicketIngestionService ingestionService, Validator validator) {
        this.ingestionService = ingestionService;
        this.validator = validator;
    }

    public BulkIngestResponse ingest(List<CreateTicketRequest> requests) {
        List<BulkIngestResponse.BulkIngestItemResult> items = new ArrayList<>(requests.size());
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;

        for (int index = 0; index < requests.size(); index++) {
            ItemOutcome outcome = ingestItem(index, requests.get(index));
            items.add(outcome.item());
            accepted += outcome.accepted();
            duplicates += outcome.duplicate();
            rejected += outcome.rejected();
        }

        return new BulkIngestResponse(accepted, duplicates, rejected, items);
    }

    private ItemOutcome ingestItem(int index, CreateTicketRequest request) {
        String validationError = validationError(request);
        if (validationError != null) {
            return ItemOutcome.rejected(index, request == null ? null : request.externalRef(), validationError);
        }

        try {
            TicketIngestionService.IngestionResult result = ingestionService.ingest(request);
            return result.wasDuplicate()
                    ? ItemOutcome.duplicate(index, request.externalRef(), result.ticket().id())
                    : ItemOutcome.accepted(index, request.externalRef(), result.ticket().id());
        } catch (DataIntegrityViolationException | IllegalStateException e) {
            return ItemOutcome.rejected(index, request.externalRef(), e.getMessage());
        }
    }

    private String validationError(CreateTicketRequest request) {
        if (request == null) {
            return "Item must not be null";
        }
        Set<ConstraintViolation<CreateTicketRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .reduce((left, right) -> left + "; " + right)
                .orElse("Invalid item");
    }

    private record ItemOutcome(
            BulkIngestResponse.BulkIngestItemResult item, int accepted, int duplicate, int rejected) {

        static ItemOutcome accepted(int index, String externalRef, Long ticketId) {
            return new ItemOutcome(
                    new BulkIngestResponse.BulkIngestItemResult(
                            index, externalRef, BulkIngestResponse.Outcome.ACCEPTED, ticketId, null),
                    1,
                    0,
                    0);
        }

        static ItemOutcome duplicate(int index, String externalRef, Long ticketId) {
            return new ItemOutcome(
                    new BulkIngestResponse.BulkIngestItemResult(
                            index, externalRef, BulkIngestResponse.Outcome.DUPLICATE, ticketId, null),
                    0,
                    1,
                    0);
        }

        static ItemOutcome rejected(int index, String externalRef, String error) {
            return new ItemOutcome(
                    new BulkIngestResponse.BulkIngestItemResult(
                            index, externalRef, BulkIngestResponse.Outcome.REJECTED, null, error),
                    0,
                    0,
                    1);
        }
    }
}
