package org.com.dcp.waitlist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enrollment, drop and FIFO waitlist-promotion for exactly one {@link CourseOffering}.
 *
 * <p>Invariants enforced by this class:
 * <ul>
 *   <li>A learner can be enrolled in this offering at most once (no duplicate enrollment).</li>
 *   <li>A learner can appear on the waitlist at most once (no duplicate waitlist entries).</li>
 *   <li>{@code enrolled.size()} never exceeds {@link CourseOffering#getCapacity()}.</li>
 *   <li>Waitlist order is FIFO; promotion always considers the earliest-joined learner first.</li>
 * </ul>
 *
 * <p><b>Eligibility / waitlist policy (documented assumption):</b> eligibility is evaluated at
 * both enrollment time and promotion time. If the learner at the front of the waitlist is no
 * longer eligible (or never was) when a seat opens, that learner is <b>skipped, not removed</b>:
 * they remain on the waitlist and the next earliest eligible learner is promoted instead. This
 * mirrors real registration systems where a waitlisted learner keeps their place and may become
 * eligible later (e.g. after completing a prerequisite), rather than losing their spot because of
 * one failed promotion attempt.
 *
 * <p><b>Concurrency:</b> a single {@link ReentrantLock} guards all state mutation and snapshot
 * reads, making register/enroll/drop/promote atomic with respect to one another. This is
 * intentionally coarse-grained: correct and simple for a single in-memory offering of this
 * bounded scope. A higher-throughput or persistent implementation would replace this with
 * per-offering database transactions (e.g. {@code SELECT ... FOR UPDATE} on the offering row)
 * instead of an in-process lock.
 */
public final class CourseRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(CourseRegistrationService.class);

    private final CourseOffering offering;
    private final LearnerDirectory directory = new LearnerDirectory();
    private final Set<String> enrolled = new LinkedHashSet<>();
    private final Deque<String> waitlist = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public CourseRegistrationService(CourseOffering offering) {
        this.offering = Objects.requireNonNull(offering, "offering must not be null");
    }

    /** Registers (or updates) a learner's profile. Idempotent. */
    public void registerLearner(Learner learner) {
        Objects.requireNonNull(learner, "learner must not be null");
        lock.lock();
        try {
            directory.register(learner);
            log.debug("Learner registered, learnerId={}, courseId={}", learner.getId(), offering.getId());
            promoteAvailableSeats();
        } finally {
            lock.unlock();
        }
    }

    public EnrollmentResult enroll(String learnerId) {
        Objects.requireNonNull(learnerId, "learnerId must not be null");
        lock.lock();
        try {
            Optional<Learner> learnerLookup = directory.find(learnerId);
            if (learnerLookup.isEmpty()) {
                log.warn("Enrollment rejected: unknown learner, learnerId={}, courseId={}", learnerId, offering.getId());
                return new EnrollmentResult(EnrollmentStatus.UNKNOWN_LEARNER,
                        "Learner " + learnerId + " is not registered");
            }
            Learner learner = learnerLookup.get();
            if (enrolled.contains(learnerId)) {
                log.warn("Enrollment rejected: duplicate enrollment, learnerId={}, courseId={}", learnerId, offering.getId());
                return new EnrollmentResult(EnrollmentStatus.ALREADY_ENROLLED,
                        "Learner " + learnerId + " is already enrolled");
            }
            if (waitlist.contains(learnerId)) {
                promoteAvailableSeats();
                if (enrolled.contains(learnerId)) {
                    return new EnrollmentResult(EnrollmentStatus.ENROLLED,
                            "Learner " + learnerId + " promoted from the waitlist");
                }
                log.warn("Enrollment rejected: already waitlisted, learnerId={}, courseId={}", learnerId, offering.getId());
                return new EnrollmentResult(EnrollmentStatus.ALREADY_WAITLISTED,
                        "Learner " + learnerId + " is already on the waitlist");
            }
            if (!offering.isEligible(learner)) {
                log.warn("Enrollment rejected: prerequisite not met, learnerId={}, courseId={}", learnerId, offering.getId());
                return new EnrollmentResult(EnrollmentStatus.INELIGIBLE,
                        "Learner " + learnerId + " does not meet the prerequisite");
            }
            promoteAvailableSeats();
            if (enrolled.size() < offering.getCapacity()) {
                enrolled.add(learnerId);
                log.info("Learner enrolled, learnerId={}, courseId={}, enrolledCount={}, capacity={}",
                        learnerId, offering.getId(), enrolled.size(), offering.getCapacity());
                return new EnrollmentResult(EnrollmentStatus.ENROLLED,
                        "Learner " + learnerId + " enrolled in " + offering.getId());
            }
            waitlist.addLast(learnerId);
            log.info("Course full; learner waitlisted, learnerId={}, courseId={}, waitlistPosition={}",
                    learnerId, offering.getId(), waitlist.size());
            return new EnrollmentResult(EnrollmentStatus.WAITLISTED,
                    "Course full; learner " + learnerId + " added to waitlist");
        } finally {
            lock.unlock();
        }
    }

    public DropResult drop(String learnerId) {
        Objects.requireNonNull(learnerId, "learnerId must not be null");
        lock.lock();
        try {
            if (!directory.contains(learnerId)) {
                log.warn("Drop rejected: unknown learner, learnerId={}, courseId={}", learnerId, offering.getId());
                return new DropResult(DropStatus.UNKNOWN_LEARNER,
                        "Learner " + learnerId + " is not registered", null);
            }
            if (!enrolled.remove(learnerId)) {
                log.warn("Drop rejected: learner not currently enrolled, learnerId={}, courseId={}", learnerId, offering.getId());
                return new DropResult(DropStatus.NOT_ENROLLED,
                        "Learner " + learnerId + " is not currently enrolled", null);
            }
            log.info("Learner dropped, learnerId={}, courseId={}", learnerId, offering.getId());
            String promotedId = promoteAvailableSeats();
            String message = promotedId == null
                    ? "Learner " + learnerId + " dropped; seat left open (no eligible waitlisted learner)"
                    : "Learner " + learnerId + " dropped; learner " + promotedId + " promoted from waitlist";
            return new DropResult(DropStatus.DROPPED, message, promotedId);
        } finally {
            lock.unlock();
        }
    }

    private String promoteAvailableSeats() {
        String firstPromotedId = null;
        while (enrolled.size() < offering.getCapacity()) {
            String promotedId = promoteEarliestEligible();
            if (promotedId == null) {
                break;
            }
            if (firstPromotedId == null) {
                firstPromotedId = promotedId;
            }
        }
        return firstPromotedId;
    }

    /**
     * Promotes the earliest eligible learner on the waitlist into the now-open seat.
     * Ineligible learners encountered along the way are skipped and remain waitlisted.
     *
     * @return the promoted learner's id, or {@code null} if no eligible learner was found.
     */
    private String promoteEarliestEligible() {
        if (waitlist.isEmpty()) {
            return null;
        }
        Iterator<String> it = waitlist.iterator();
        while (it.hasNext()) {
            String candidateId = it.next();
            Optional<Learner> candidate = directory.find(candidateId);
            if (candidate.isPresent() && offering.isEligible(candidate.get())) {
                it.remove();
                enrolled.add(candidateId);
                log.info("Learner promoted from waitlist, learnerId={}, courseId={}, enrolledCount={}",
                        candidateId, offering.getId(), enrolled.size());
                return candidateId;
            }
            if (candidate.isPresent()) {
                log.warn("Skipping ineligible waitlisted learner during promotion, learnerId={}, courseId={}",
                        candidateId, offering.getId());
            }
        }
        log.info("No eligible waitlisted learner found; seat left open, courseId={}", offering.getId());
        return null;
    }

    /** Returns an immutable snapshot; mutating the returned list has no effect on service state. */
    public List<String> getEnrolledLearnerIds() {
        lock.lock();
        try {
            return List.copyOf(enrolled);
        } finally {
            lock.unlock();
        }
    }

    /** Returns an immutable snapshot, in FIFO order; mutating the returned list has no effect on service state. */
    public List<String> getWaitlistedLearnerIds() {
        lock.lock();
        try {
            return List.copyOf(waitlist);
        } finally {
            lock.unlock();
        }
    }

    public CourseOffering getOffering() {
        return offering;
    }
}