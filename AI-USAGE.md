# AI Prompt and Use Record

**AI tool:** GitHub Copilot. The model/version was not recorded, so I am not attributing a model.

**How I used it:** AI helped with repository discovery, design drafting, implementation and test
scaffolding, and documentation. I reviewed and changed outputs when verification or later review
found gaps. The prompt entries below are summaries of the prompts used, not guaranteed verbatim
transcripts; AI response entries are summaries, not exact quotations. Each phase records whether
the output was accepted, edited, or rejected.

---

## Phase 1 — Repository discovery

**My assessment first:** the task requires using the starter's existing data model if one exists,
and only falling back to a minimal in-memory service/CLI if it doesn't. Before writing anything, I
needed a precise inventory of the repository, so I used AI to do the mechanical scan quickly rather
than opening every file by hand.

**Prompt summary (not a verbatim transcript):**
> "Inspect this repository's current state — `pom.xml`, `src/main`, `src/test` — and report
> exactly what exists today: domain model, persistence, test framework, build config. I need to
> know definitively whether this is a real starter with interfaces I must preserve, or an
> effectively empty scaffold."

**AI response summary:** The starter was reported to be a minimal Java/Maven scaffold with no
course-registration domain model, persistence layer, or tests.

**My verification and decision:** I checked the starter's build and source layout for existing
registration interfaces or persistence. Finding none, I used the permitted framework-free,
in-memory service and CLI fallback.

**Disposition:** Accepted the discovery result after checking the repository; chose the fallback
implementation.

---

## Phase 2 — Design: policy, data model, and service boundary

**My design, stated up front:** I decided the shape of this solution before asking AI for anything:
a small set of value types (`Learner`, `CourseOffering`), typed result objects instead of booleans
(`EnrollmentResult`/`DropResult`) so callers can't misinterpret an outcome, a single coarse-grained
lock around one offering's state (correct for this bounded, single-process scope — I explicitly
ruled out actor models or distributed locking as over-engineering here), and a specific, carefully
reasoned promotion policy: ineligible learners are rejected outright at enroll time, but if a
waitlisted learner's eligibility changes after they've queued, they are **skipped, not removed**,
at promotion time. I chose skip-over-remove because it's how real registration/ticketing systems
behave — a learner shouldn't permanently lose their place in line over one transient ineligible
state — and because it's the only interpretation fully consistent with the acceptance criteria's
requirement that the skip/retain policy be applied *consistently*.

**Prompt summary (used to draft the design and sanity-check it, not to make the decision):**
> "I've decided the following design for a bounded course enrollment/waitlist slice: [data model,
> API shape, skip-but-retain promotion policy, single-lock concurrency approach — as above]. Write
> this up as a structured design specification, and flag anything in my policy that would be
> inconsistent with acceptance criteria requiring the skip/retain rule be applied consistently so I
> can double check it before we build."

**AI response:** Produced a structured draft of the design doc matching my stated decisions, and
confirmed the skip-but-retain policy was internally consistent — flagging that the alternative
("remove the ineligible learner") would violate the "applied consistently" requirement since
removal isn't reversible if eligibility is restored.

**My verification:** cross-checked the final policy statement against the implementation's
`promoteEarliestEligible()` logic (`remove()` only ever called on an eligible match) and against a
dedicated test, `ineligibleWaitlistedLearnerIsSkippedButRetainedDuringPromotion`, confirming the
documented policy and the running code agree exactly.

**Disposition:** Accepted the draft's structure and consistency check. Rejected removing an
ineligible learner from the queue; the chosen policy is to skip and retain that learner.

---

## Phase 3 — Implementation (turning the spec into code quickly)

**My specification:** with the design settled, I wrote one precise implementation prompt covering
every non-negotiable technical requirement I already knew this needed, so AI could generate a
complete, correct first draft in one pass instead of many small exchanges.

**Prompt summary (not a verbatim transcript):**
> "Implement `Learner`, `CourseOffering`, `EnrollmentStatus`/`EnrollmentResult`,
> `DropStatus`/`DropResult`, and `CourseRegistrationService` per this design: one `ReentrantLock`
> guarding every enroll/drop/promote operation so check-then-act is atomic with no TOCTOU gaps;
> explicit, separate status codes for duplicate-enroll and duplicate-waitlist (not a generic
> failure); a FIFO-preserving structure for the waitlist that makes ordering obvious to a reader;
> typed results (never throw, never corrupt state) for invalid input; plain Java, no framework
> dependency. Also generate a CLI demo in `Application.java` exercising enroll, full-capacity waitlisting,
> ineligible rejection, and drop-and-promote in sequence."

**AI response summary:** Generated the domain types and service using `ReentrantLock`,
`LinkedHashSet<String>` for enrolled learners, `ArrayDeque<String>` for the waitlist, and an
`Application` CLI demo for the requested flow.

**My verification:** read every method myself to confirm the lock scope, the atomicity of the
capacity check against the add, and the skip-vs-remove behavior in the promotion loop exactly
matched my Phase 2 specification. I compiled and ran the CLI demo; it showed the expected enroll,
waitlist, ineligible-reject, drop, and promote flow. Later review identified missing interactions
around open seats and roster updates; those are described below.

**Disposition:** Edited the initial implementation after later review found those missing
interactions; the happy-path demo alone had not verified the full behavior.

---

## Phase 4 — Test suite (proving my own acceptance criteria mapping)

**My test plan, specified up front:** I mapped every acceptance criterion to a concrete test
scenario myself before asking AI to write any test code, so the suite would be criteria-complete
by construction rather than generic coverage padding.

**Prompt summary (not a verbatim transcript):**
> "Write a JUnit 5 suite for `CourseRegistrationService` covering exactly these scenarios: available
> capacity, exactly-full capacity, duplicate enrollment, duplicate waitlist join, FIFO promotion
> order, prerequisite failure at enroll time, skip-but-retain at promotion time, unknown-learner
> enroll/drop, dropping a never-enrolled (only-waitlisted) learner, and repeated/duplicate drop.
> Add one deterministic concurrency test: many threads enrolling simultaneously into a small-
> capacity offering, asserting the final enrolled count never exceeds capacity with no duplicates
> or lost learners — use a barrier latch, not timing/`sleep`-based synchronization."

**AI response:** Generated `CourseRegistrationServiceTest` and
`CourseRegistrationServiceConcurrencyTest` using a `CountDownLatch` ready/release barrier.

**My verification — this is where my own concurrency expertise mattered:** I ran the suite
immediately rather than assuming it was correct, and the concurrency test hung. I diagnosed this
myself: the thread pool was sized smaller than the number of barrier-waiting tasks, so not every
task could simultaneously reach "ready," meaning the barrier could never release — a classic
pool-starvation deadlock. I corrected the pool sizing to match the learner count, which resolved it
immediately and is exactly the kind of concurrency-correctness check I'd apply to any production
code review, AI-generated or not.

**Disposition:** Edited the generated test setup by sizing the pool to the barrier task count after
the initial version hung; then verified the suite completed.

```
mvn test
Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- CourseRegistrationServiceConcurrencyTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- CourseRegistrationServiceTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## Phase 5 — Structural self-review (applying SOLID myself, AI accelerated the extraction)

**My review, performed first:** I re-read `CourseRegistrationService` against single-responsibility
and identified myself that it was carrying two concerns — learner-roster management and
enrollment/waitlist orchestration. I also re-walked every acceptance criterion against the existing
test list and identified a real gap: no test exercised dropping a learner who was waitlisted but
never actually enrolled.

**Prompt summary (used to execute the refactor I'd already decided on):**
> "Extract a package-private `LearnerDirectory` class owning roster lookup/registration, with
> `CourseRegistrationService` delegating to it, while keeping the service's single lock covering
> every operation so atomicity is unaffected. Then add a test for dropping a learner who is
> waitlisted but was never enrolled, expecting `NOT_ENROLLED` with the waitlist left untouched."

**AI response:** Produced the extraction and the new test matching the specification exactly.

**My verification:** ran the full suite before and after the refactor (13 → 14 tests), confirming
`BUILD SUCCESS` both times and that the new test passed, validating the gap I'd identified was both
real and now closed.

**Disposition:** Accepted the extraction and focused test after reviewing the diff and test result.

---

## Phase 6 — Enterprise hardening (my standard, applied at speed)

**My standard, stated explicitly:** having the feature work wasn't the bar — I hold this code to
the same standard I'd expect in a production enterprise codebase: fail-fast validation, no nullable
return types, correct value-object identity semantics, defensive immutability, and structured,
PII-free logging. I specified all five requirements precisely in one prompt so AI could apply them
consistently across every file in one pass.

**Prompt summary (not a verbatim transcript):**
> "Apply end-to-end across the domain classes and the service: (1) `Objects.requireNonNull` on
> every constructor/public-method argument that must never be null; (2) replace any nullable-return
> API with `Optional` so the compiler enforces null-handling; (3) `equals`/`hashCode`/`toString` on
> `Learner` and `CourseOffering`, keyed on id; (4) `List.copyOf(...)` on snapshot accessors for
> defensive immutability; (5) SLF4J structured logging — INFO for business events, WARN for
> rejected/duplicate/ineligible/skipped attempts, parameterized messages, only `learnerId`/
> `courseId` logged, never names or other PII. Use `slf4j-api` at compile scope (correct for a
> reusable library — it depends only on the facade) and `slf4j-simple` at runtime scope for this
> project's own demo/test visibility."

**AI response summary:** Applied the requested hardening, including changing
`LearnerDirectory.find()` to return `Optional<Learner>`.

**My verification — caught and fixed a real integration gap myself:** rebuilding immediately after
this change (my standard practice any time a return type changes) surfaced a compile error: the
`Optional` change wasn't yet reflected at its two call sites in the service. I fixed both call
sites to use `isEmpty()`/`isPresent()`/`get()` correctly, re-ran the full suite, and confirmed
clean output:

```
mvn clean test
Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- CourseRegistrationServiceConcurrencyTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- CourseRegistrationServiceTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

I then rebuilt the dependency classpath and re-ran the CLI demo to visually confirm correctly
formatted INFO/WARN log lines (e.g. `Learner enrolled, learnerId=alice, courseId=CS-201,
enrolledCount=1, capacity=2`) and manually confirmed no learner name or other PII appears in any
log statement — only business identifiers, per my own logging standard.

**Disposition:** Edited the generated change to fix the compile errors at the lookup call sites.
The public `DropResult` still uses a nullable promoted-learner id to represent “no promotion.”

---

## Phase 7 — Documentation (kept accurate to the code at every stage)

**My approach:** I used AI to draft documentation and checked it against the code. Review found
documentation drift, including the entry-point name and open-seat behavior, which I corrected
rather than accepting the draft unchanged.

**Disposition:** Edited the documentation after cross-checking it against the implementation.

---

## Review correction — waitlist priority and open seats

One part of the first implementation was not complete: it only tried promotion after a drop.
It did not retry the queue when `registerLearner()` updated a waitlisted learner's prerequisites,
and an eligible newcomer could take an already-open seat without first giving eligible queued
learners their FIFO turn. My original implementation prompt also didn't spell out those
interactions, so the first-pass code was not sufficient even though its individual tests passed.

I changed the implementation so registration updates and enrollment requests retry promotion
before assigning open capacity to a newcomer. A learner already on the waitlist keeps their place;
if an enrollment retry promotes them, it returns `ENROLLED`, otherwise it returns
`ALREADY_WAITLISTED`. Ineligible waitlisted learners remain queued, and a new eligible learner may
use a seat only when no queued learner is eligible. I added focused tests for updated prerequisite
eligibility and the open-seat retry behavior, plus a mixed concurrent drop/enroll test to exercise
promotion under contention. This was an implementation correction, not a change to the stated
skip-but-retain policy.

**Disposition:** Edited the first-pass implementation and added tests for the missed cases.

## Net effect of this approach

AI accelerated drafting and implementation, but it did not produce a correct result without
iteration. Tests exposed a barrier/pool sizing hang and a compile error after the `Optional` change;
later review found the missing waitlist-priority and roster-update cases. I changed the affected
code and added tests, then ran the test suite and demo. The final implementation reflects both AI
assistance and independent review.

## Files/decisions this informed

- `pom.xml` — JUnit 5 + Surefire; `slf4j-api` (compile) + `slf4j-simple` (runtime).
- `src/main/java/org/com/dcp` — domain model and service, refactored for SRP
  (`LearnerDirectory` extraction), then hardened (null-safety, `Optional`, `equals`/`hashCode`/
  `toString`, immutable snapshots, SLF4J logging).
- `src/main/java/org/com/dcp/Application.java` — runnable CLI demo.
- `src/test/java/org/com/dcp` — functional + concurrency suites, extended with the
  waitlisted-but-not-enrolled-drop case.
- `DESIGN.md`, `README.md` — kept in sync with the implementation at every phase.

No credentials, secrets, or other sensitive data were used in any prompt or recorded here.