package org.com.dcp.waitlist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers: available capacity, full capacity, duplicate requests, FIFO promotion,
 * prerequisite failure (including the skip-but-retain policy), and invalid/repeated drops.
 */
class CourseRegistrationServiceTest {

    private static final String PREREQ = "CS-101";

    private CourseOffering offering;
    private CourseRegistrationService service;

    @BeforeEach
    void setUp() {
        offering = new CourseOffering("CS-201", "Data Structures", 2, PREREQ);
        service = new CourseRegistrationService(offering);
        service.registerLearner(learner("alice"));
        service.registerLearner(learner("bob"));
        service.registerLearner(learner("carol"));
        service.registerLearner(learner("dave"));
        service.registerLearner(noPrereqLearner("erin"));
    }

    private static Learner learner(String id) {
        return new Learner(id, id, Set.of(PREREQ));
    }

    private static Learner noPrereqLearner(String id) {
        return new Learner(id, id, Set.of());
    }

    @Test
    void enrollSucceedsWhenCapacityAvailableAndEligible() {
        EnrollmentResult result = service.enroll("alice");

        assertEquals(EnrollmentStatus.ENROLLED, result.status());
        assertTrue(result.isSuccess());
        assertEquals(List.of("alice"), service.getEnrolledLearnerIds());
    }

    @Test
    void enrollWaitlistsEligibleLearnerWhenCourseIsFull() {
        service.enroll("alice");
        service.enroll("bob"); // fills capacity of 2

        EnrollmentResult result = service.enroll("carol");

        assertEquals(EnrollmentStatus.WAITLISTED, result.status());
        assertTrue(result.isSuccess(), "waitlisting is a successful outcome, not a rejection");
        assertEquals(List.of("alice", "bob"), service.getEnrolledLearnerIds());
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds());
    }

    @Test
    void duplicateEnrollmentDoesNotCreateDuplicateEnrolledRecord() {
        service.enroll("alice");

        EnrollmentResult second = service.enroll("alice");

        assertEquals(EnrollmentStatus.ALREADY_ENROLLED, second.status());
        assertEquals(List.of("alice"), service.getEnrolledLearnerIds());
    }

    @Test
    void duplicateWaitlistRequestDoesNotCreateDuplicateWaitlistRecord() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol"); // waitlisted

        EnrollmentResult second = service.enroll("carol");

        assertEquals(EnrollmentStatus.ALREADY_WAITLISTED, second.status());
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds());
    }

    @Test
    void droppingEnrolledLearnerPromotesEarliestEligibleWaitlistedLearnerFifo() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol"); // 1st in waitlist
        service.enroll("dave");  // 2nd in waitlist

        DropResult dropResult = service.drop("alice");

        assertEquals(DropStatus.DROPPED, dropResult.status());
        assertEquals("carol", dropResult.promotedLearnerId());
        assertTrue(dropResult.promotedSomeone());
        assertEquals(List.of("bob", "carol"), service.getEnrolledLearnerIds());
        assertEquals(List.of("dave"), service.getWaitlistedLearnerIds());
    }

    @Test
    void ineligibleLearnerIsRejectedOutrightAndNeverJoinsTheWaitlist() {
        service.enroll("alice");
        service.enroll("bob"); // fills capacity

        EnrollmentResult result = service.enroll("erin"); // erin has not completed the prerequisite

        assertEquals(EnrollmentStatus.INELIGIBLE, result.status());
        assertFalse(service.getWaitlistedLearnerIds().contains("erin"));
        assertFalse(service.getEnrolledLearnerIds().contains("erin"));
    }

    @Test
    void ineligibleWaitlistedLearnerIsSkippedButRetainedDuringPromotion() {
        // Documents the skip-but-retain policy (see CourseRegistrationService javadoc):
        // a learner can become ineligible *after* joining the waitlist (e.g. a prerequisite
        // correction is recorded). When promotion time comes, that learner must be skipped,
        // not discarded, and the next eligible learner in FIFO order is promoted instead.
        service.enroll("alice");
        service.enroll("bob");  // fills capacity (2)
        service.enroll("carol"); // 1st on waitlist, currently eligible
        service.enroll("dave");  // 2nd on waitlist, currently eligible

        // Simulate carol's prerequisite status being corrected to ineligible while still waitlisted.
        service.registerLearner(noPrereqLearner("carol"));

        DropResult dropResult = service.drop("alice");

        assertEquals(DropStatus.DROPPED, dropResult.status());
        assertEquals("dave", dropResult.promotedLearnerId(), "dave should be promoted, skipping ineligible carol");
        assertTrue(service.getEnrolledLearnerIds().contains("dave"));
        assertFalse(service.getEnrolledLearnerIds().contains("carol"));
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds(), "carol stays on waitlist, not discarded");
    }

    @Test
    void updatedWaitlistedLearnerIsPromotedWhenTheyBecomeEligible() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol");
        service.registerLearner(noPrereqLearner("carol"));

        service.drop("alice");
        assertFalse(service.getEnrolledLearnerIds().contains("carol"));
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds());

        service.registerLearner(learner("carol"));

        assertTrue(service.getEnrolledLearnerIds().contains("carol"));
        assertFalse(service.getWaitlistedLearnerIds().contains("carol"));
    }

    @Test
    void newEligibleLearnerCanTakeOpenSeatWhileIneligibleWaiterKeepsTheirPlace() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol");
        service.registerLearner(noPrereqLearner("carol"));
        service.drop("alice");

        EnrollmentResult queuedRetry = service.enroll("carol");
        service.registerLearner(learner("frank"));
        EnrollmentResult newLearner = service.enroll("frank");

        assertEquals(EnrollmentStatus.ALREADY_WAITLISTED, queuedRetry.status());
        assertEquals(EnrollmentStatus.ENROLLED, newLearner.status());
        assertEquals(List.of("bob", "frank"), service.getEnrolledLearnerIds());
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds());
    }

    @Test
    void invalidEnrollRequestForUnknownLearnerReturnsClearResultWithoutCorruptingState() {
        EnrollmentResult result = service.enroll("unknown-learner");

        assertEquals(EnrollmentStatus.UNKNOWN_LEARNER, result.status());
        assertFalse(result.isSuccess());
        assertTrue(service.getEnrolledLearnerIds().isEmpty());
        assertTrue(service.getWaitlistedLearnerIds().isEmpty());
    }

    @Test
    void droppingUnenrolledLearnerReturnsNotEnrolledWithoutCorruptingState() {
        DropResult result = service.drop("alice"); // never enrolled

        assertEquals(DropStatus.NOT_ENROLLED, result.status());
        assertTrue(service.getEnrolledLearnerIds().isEmpty());
    }

    @Test
    void droppingWaitlistedButNotEnrolledLearnerReturnsNotEnrolledAndLeavesWaitlistIntact() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol"); // waitlisted, not enrolled

        DropResult result = service.drop("carol");

        assertEquals(DropStatus.NOT_ENROLLED, result.status());
        assertNull(result.promotedLearnerId());
        assertEquals(List.of("carol"), service.getWaitlistedLearnerIds(), "waitlist must be untouched");
        assertEquals(List.of("alice", "bob"), service.getEnrolledLearnerIds());
    }

    @Test
    void droppingUnknownLearnerReturnsClearResult() {
        DropResult result = service.drop("ghost");

        assertEquals(DropStatus.UNKNOWN_LEARNER, result.status());
    }

    @Test
    void repeatedDropOfTheSameLearnerIsHandledWithoutCorruptingState() {
        service.enroll("alice");
        service.enroll("bob");
        service.enroll("carol"); // waitlisted

        DropResult first = service.drop("alice");
        assertEquals(DropStatus.DROPPED, first.status());
        assertEquals("carol", first.promotedLearnerId());

        DropResult second = service.drop("alice"); // already dropped
        assertEquals(DropStatus.NOT_ENROLLED, second.status());
        assertNull(second.promotedLearnerId());
        // state unchanged by the duplicate drop
        assertEquals(List.of("bob", "carol"), service.getEnrolledLearnerIds());
        assertTrue(service.getWaitlistedLearnerIds().isEmpty());
    }

    @Test
    void dropWithEmptyWaitlistLeavesSeatOpenWithoutPromotion() {
        service.enroll("alice");

        DropResult result = service.drop("alice");

        assertEquals(DropStatus.DROPPED, result.status());
        assertNull(result.promotedLearnerId());
        assertFalse(result.promotedSomeone());
        assertTrue(service.getEnrolledLearnerIds().isEmpty());
    }
}