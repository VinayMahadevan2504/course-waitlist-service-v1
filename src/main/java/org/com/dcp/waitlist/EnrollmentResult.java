package org.com.dcp.waitlist;

import java.util.Objects;

/**
 * Result of an enrollment request.
 *
 * @param status  the outcome
 * @param message human-readable explanation, useful for API responses / CLI output
 */
public record EnrollmentResult(EnrollmentStatus status, String message) {

    public EnrollmentResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public boolean isSuccess() {
        return status == EnrollmentStatus.ENROLLED || status == EnrollmentStatus.WAITLISTED;
    }
}