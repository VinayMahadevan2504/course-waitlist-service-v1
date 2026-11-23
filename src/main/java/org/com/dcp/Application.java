package org.com.dcp;

import org.com.dcp.waitlist.CourseOffering;
import org.com.dcp.waitlist.CourseRegistrationService;
import org.com.dcp.waitlist.DropResult;
import org.com.dcp.waitlist.EnrollmentResult;
import org.com.dcp.waitlist.Learner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        CourseOffering offering = new CourseOffering("CS-201", "Data Structures", 2, "CS-101");
        CourseRegistrationService service = new CourseRegistrationService(offering);

        service.registerLearner(new Learner("alice", "Alice", Set.of("CS-101")));
        service.registerLearner(new Learner("bob", "Bob", Set.of("CS-101")));
        service.registerLearner(new Learner("carol", "Carol", Set.of("CS-101"))); // joins waitlist
        service.registerLearner(new Learner("dave", "Dave", Set.of()));           // missing prerequisite

        logResult("enroll(alice)", service.enroll("alice"));
        logResult("enroll(bob)", service.enroll("bob"));
        logResult("enroll(carol) -> expect WAITLISTED (course full)", service.enroll("carol"));
        logResult("enroll(dave) -> expect INELIGIBLE (no prerequisite)", service.enroll("dave"));

        logRosters(service);

        DropResult dropResult = service.drop("alice");
        log.info("drop(alice): status={} message={}", dropResult.status(), dropResult.message());

        logRosters(service);
    }

    private static void logRosters(CourseRegistrationService service) {
        log.info("Enrolled: {}", service.getEnrolledLearnerIds());
        log.info("Waitlist: {}", service.getWaitlistedLearnerIds());
    }

    private static void logResult(String label, EnrollmentResult result) {
        log.info("{}: status={} message={}", label, result.status(), result.message());
    }
}