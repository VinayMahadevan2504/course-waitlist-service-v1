package org.com.dcp.waitlist;

public enum DropStatus {
    /** The learner was enrolled and has been removed; a waitlist promotion may have occurred. */
    DROPPED,
    /** No-op: the learner was registered but not currently enrolled (never enrolled, already dropped, or only waitlisted). */
    NOT_ENROLLED,
    /** Rejected: no learner with the given id has been registered via {@code registerLearner}. */
    UNKNOWN_LEARNER
}