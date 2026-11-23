# Course Enrollment & Waitlist Slice

A bounded implementation of enrollment, dropping, and FIFO waitlist promotion for **one course
offering**, built against the provided (empty) Maven/Java 17 starter. See `DESIGN.md` for the
full design specification and `AI-USAGE.md` for the AI prompt/use record.

## Requirements

- JDK 17+
- Maven 3.6+ (project uses plain `mvn`; no wrapper was present in the starter)

## Project Layout

```
src/main/java/org/com/dcp/waitlist/
  Learner.java                     - learner id, name, completed-course ids
  CourseOffering.java               - offering id, capacity, optional prerequisite, eligibility rule
  LearnerDirectory.java              - package-private roster lookup/upsert (SRP-separated from the service)
  EnrollmentStatus.java / EnrollmentResult.java
  DropStatus.java / DropResult.java
  CourseRegistrationService.java    - the service boundary: enroll / drop / promote (SLF4J logging)
src/main/java/org/com/dcp/Application.java - small runnable CLI demo
src/test/java/org/com/dcp/waitlist/
  CourseRegistrationServiceTest.java            - functional tests (capacity, FIFO, prerequisites, invalid/duplicate requests)
  CourseRegistrationServiceConcurrencyTest.java - focused concurrency test
```

## Run the tests

```powershell
mvn test
```

Expected: `Tests run: 17, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

## Run the demo

The service depends on the SLF4J API (facade) plus `slf4j-simple` at runtime for console output,
so the demo needs those jars on the classpath alongside `target\classes`:

```powershell
mvn -q compile
mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
java -cp "target\classes;$(Get-Content cp.txt -Raw)" org.com.dcp.Application
Remove-Item cp.txt
```

This enrolls two learners into a 2-seat offering, waitlists a third, rejects a fourth for a
missing prerequisite, then drops a learner and shows the waitlisted learner get promoted. All
output is emitted as SLF4J INFO/WARN log lines, tagged with `learnerId`/`courseId` (never names
or other PII).

## Key Behaviors

- Enrollment succeeds only when capacity remains and the learner meets the course's prerequisite.
- A full course waitlists eligible learners; duplicate enroll/waitlist requests are rejected
  cleanly (`ALREADY_ENROLLED` / `ALREADY_WAITLISTED`) without creating duplicate records.
- Dropping an enrolled learner promotes the earliest **eligible** waitlisted learner (FIFO);
  an ineligible learner at the front is skipped and stays on the waitlist (see `DESIGN.md` §5).
- When roster data is updated or an enrollment request finds open capacity, eligible existing
  waitlisted learners are promoted before a new learner can take a seat. If no waitlisted learner
  is eligible, the seat remains available to a new eligible learner; ineligible waitlisted
  learners retain their positions.
- Invalid requests (unknown learner) and duplicate drops return a clear typed result without
  corrupting state.
- Concurrent enroll requests and concurrent drops/enrollments are safe:
  `CourseRegistrationServiceConcurrencyTest` verifies no overbooking and no lost/duplicated
  records under contention.