package org.com.dcp.waitlist;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The learner roster: who is known to this offering's registration process, and their
 * completed-course history. Kept separate from {@link CourseRegistrationService} so that
 * roster management (a lookup/upsert concern) doesn't bleed into enrollment/waitlist
 * orchestration (single responsibility principle).
 *
 * <p>Not independently thread-safe: callers (here, {@code CourseRegistrationService}) are
 * responsible for guarding concurrent access, since roster lookups must be atomic with the
 * enrollment/waitlist decisions that depend on them.
 */
final class LearnerDirectory {

    private final Map<String, Learner> learners = new LinkedHashMap<>();

    void register(Learner learner) {
        Objects.requireNonNull(learner, "learner must not be null");
        learners.put(learner.getId(), learner);
    }

    /** Never returns null: an unknown id yields {@link Optional#empty()}, not a null reference. */
    Optional<Learner> find(String learnerId) {
        return Optional.ofNullable(learners.get(learnerId));
    }

    boolean contains(String learnerId) {
        return learners.containsKey(learnerId);
    }
}