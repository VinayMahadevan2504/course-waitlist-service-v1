package org.com.dcp.waitlist;

import java.util.Objects;

/**
 * Result of a drop request.
 *
 * @param status           the outcome
 * @param message          human-readable explanation
 * @param promotedLearnerId id of the waitlisted learner promoted as a consequence of this drop, or null if none
 */
public record DropResult(DropStatus status, String message, String promotedLearnerId) {

    public DropResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
        // promotedLearnerId is intentionally nullable: no promotion occurred
    }

    public boolean promotedSomeone() {
        return promotedLearnerId != null;
    }
}