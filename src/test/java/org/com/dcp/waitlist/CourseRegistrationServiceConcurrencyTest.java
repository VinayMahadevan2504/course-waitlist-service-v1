package org.com.dcp.waitlist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused concurrency test: many learners enroll concurrently in a course with a small
 * capacity. The coarse-grained lock in {@link CourseRegistrationService} must still enforce
 * the capacity invariant and avoid duplicate/lost records under contention.
 */
class CourseRegistrationServiceConcurrencyTest {

    @Test
    void concurrentEnrollmentsNeverExceedCapacityAndNeverLoseLearners() throws InterruptedException {
        int capacity = 10;
        int learnerCount = 100;
        CourseOffering offering = new CourseOffering("CS-201", "Data Structures", capacity, null);
        CourseRegistrationService service = new CourseRegistrationService(offering);

        for (int i = 0; i < learnerCount; i++) {
            service.registerLearner(new Learner("learner-" + i, "learner-" + i, Set.of()));
        }

        // Pool must have a thread for every learner: each task blocks on the start barrier
        // before enrolling, so a smaller pool would deadlock (ready threads can never free up
        // to let queued tasks reach the barrier).
        ExecutorService pool = Executors.newFixedThreadPool(learnerCount);
        CountDownLatch readyLatch = new CountDownLatch(learnerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger enrolledCount = new AtomicInteger();
        AtomicInteger waitlistedCount = new AtomicInteger();

        for (int i = 0; i < learnerCount; i++) {
            String id = "learner-" + i;
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                EnrollmentResult result = service.enroll(id);
                if (result.status() == EnrollmentStatus.ENROLLED) {
                    enrolledCount.incrementAndGet();
                } else if (result.status() == EnrollmentStatus.WAITLISTED) {
                    waitlistedCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(); // all threads lined up before releasing them together
        startLatch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent enrollments did not finish in time");

        List<String> enrolledIds = service.getEnrolledLearnerIds();
        List<String> waitlistedIds = service.getWaitlistedLearnerIds();

        assertEquals(capacity, enrolledIds.size(), "enrolled count must equal capacity exactly, no overbooking");
        assertEquals(capacity, enrolledCount.get());
        assertEquals(learnerCount - capacity, waitlistedCount.get());
        assertEquals(learnerCount - capacity, waitlistedIds.size());
        assertEquals(Set.copyOf(enrolledIds).size(), enrolledIds.size(), "no duplicate enrolled records");
        assertEquals(Set.copyOf(waitlistedIds).size(), waitlistedIds.size(), "no duplicate waitlist records");
        assertEquals(learnerCount, enrolledIds.size() + waitlistedIds.size(), "no learner lost under contention");
    }

    @Test
    void concurrentDropsAndEnrollmentsPreserveCapacityAndFifoPromotion() throws Exception {
        int capacity = 10;
        int initialWaitlistSize = 20;
        int newLearnerCount = 20;
        CourseOffering offering = new CourseOffering("CS-201", "Data Structures", capacity, null);
        CourseRegistrationService service = new CourseRegistrationService(offering);

        int totalLearners = capacity + initialWaitlistSize + newLearnerCount;
        for (int i = 0; i < totalLearners; i++) {
            service.registerLearner(new Learner("learner-" + i, "learner-" + i, Set.of()));
        }
        for (int i = 0; i < capacity + initialWaitlistSize; i++) {
            service.enroll("learner-" + i);
        }

        int taskCount = capacity + newLearnerCount;
        ExecutorService pool = Executors.newFixedThreadPool(taskCount);
        CountDownLatch readyLatch = new CountDownLatch(taskCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < capacity; i++) {
            String id = "learner-" + i;
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                assertEquals(DropStatus.DROPPED, service.drop(id).status());
                return null;
            }));
        }
        for (int i = capacity + initialWaitlistSize; i < totalLearners; i++) {
            String id = "learner-" + i;
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                EnrollmentStatus status = service.enroll(id).status();
                assertTrue(status == EnrollmentStatus.ENROLLED || status == EnrollmentStatus.WAITLISTED);
                return null;
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent drops and enrollments did not finish in time");
        for (Future<?> future : futures) {
            future.get();
        }

        List<String> enrolledIds = service.getEnrolledLearnerIds();
        List<String> waitlistedIds = service.getWaitlistedLearnerIds();
        Set<String> activeIds = new HashSet<>(enrolledIds);
        assertEquals(capacity, enrolledIds.size(), "all opened seats should be filled from the eligible waitlist");
        assertEquals(Set.copyOf(enrolledIds).size(), enrolledIds.size(), "no duplicate enrolled records");
        assertEquals(Set.copyOf(waitlistedIds).size(), waitlistedIds.size(), "no duplicate waitlist records");
        for (String id : waitlistedIds) {
            assertTrue(activeIds.add(id), "learner must not be both enrolled and waitlisted");
        }

        Set<String> expectedActiveIds = new HashSet<>();
        for (int i = capacity; i < totalLearners; i++) {
            expectedActiveIds.add("learner-" + i);
        }
        assertEquals(expectedActiveIds, activeIds, "no eligible waitlisted or newly enrolling learner is lost");
        List<String> expectedPromotedIds = new ArrayList<>();
        for (int i = capacity; i < capacity * 2; i++) {
            expectedPromotedIds.add("learner-" + i);
        }
        assertEquals(expectedPromotedIds, enrolledIds, "existing waitlisted learners are promoted in FIFO order");
        List<String> expectedWaitlistPrefix = new ArrayList<>();
        for (int i = capacity * 2; i < capacity + initialWaitlistSize; i++) {
            expectedWaitlistPrefix.add("learner-" + i);
        }
        assertEquals(expectedWaitlistPrefix, waitlistedIds.subList(0, capacity),
                "remaining original waitlisted learners stay ahead of concurrent newcomers");
        assertEquals(initialWaitlistSize + newLearnerCount - capacity, waitlistedIds.size(),
                "remaining eligible learners must be on the waitlist");
    }
}