package org.com.dcp.waitlist;


public enum EnrollmentStatus {
    /** Capacity was available and the learner met the prerequisite; seat granted. */
    ENROLLED,
    /** Capacity was full but the learner was eligible; added to the end of the FIFO waitlist. */
    WAITLISTED,
    /** No-op: the learner already holds an enrolled seat in this offering. */
    ALREADY_ENROLLED,
    /** No-op: the learner is already on the waitlist for this offering. */
    ALREADY_WAITLISTED,
    /** Rejected: the learner has not completed the offering's prerequisite course. */
    INELIGIBLE,
    /** Rejected: no learner with the given id has been registered via {@code registerLearner}. */
    UNKNOWN_LEARNER
}