# AI Prompt and Use Record

**AI tool:** GitHub Copilot (agentic coding assistant, Claude Sonnet 5).

**How I used it:** as a productivity accelerator, not a decision-maker. For every phase below, the
engineering decision — the data model, the eligibility/promotion policy, the concurrency approach,
the SOLID boundary, the hardening standard — was mine, drawn from my own experience with
transactional, concurrent, and enterprise Java systems. I used AI to turn each fully-specified
decision into working code quickly: I wrote a precise, constraint-complete prompt describing
exactly what I wanted and why, had the AI generate the first draft, and then independently verified
the result against my own specification (compiling, running the test suite, reading the generated
code line-by-line, exercising the CLI). This kept me focused on the engineering problem — correct
capacity/queue invariants, atomicity, testability — while AI handled the mechanical work of typing
out classes, boilerplate, and test scaffolding. Below is the record of each prompt, what AI
produced, and how I verified it before it became part of the submission.

---

## Phase 1 — Repository discovery

**My assessment first:** the task requires using the starter's existing data model if one exists,
and only falling back to a minimal in-memory service/CLI if it doesn't. Before writing anything, I
needed a precise inventory of the repository, so I used AI to do the mechanical scan quickly rather
than opening every file by hand.

**Prompt:**
> "Inspect this repository's current state — `pom.xml`, `src/main`, `src/test` — and report
> exactly what exists today: domain model, persistence, test framework, build config. I need to
> know definitively whether this is a real starter with interfaces I must preserve, or an
> effectively empty scaffold."

**AI response:** `pom.xml` declared only `groupId/artifactId/version`, Java 17, no dependencies.
`Main.java` contained only placeholder template content. No domain classes, no persistence layer,
no test dependencies, no `src/test` directory.

**My verification and decision:** confirmed directly by opening `pom.xml` and `Main.java` myself.
This matched the assessment's documented fallback case, so I made the call to build a
framework-free, in-memory Java service plus a CLI demo — the correct, proportionate choice given
the explicit instructions, not a default I accepted passively.

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

**Prompt (used to generate the design document and a sanity check, not to make the decision):**
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

---

## Phase 3 — Implementation (turning the spec into code quickly)

**My specification:** with the design settled, I wrote one precise implementation prompt covering
every non-negotiable technical requirement I already knew this needed, so AI could generate a
complete, correct first draft in one pass instead of many small exchanges.

**Prompt:**
> "Implement `Learner`, `CourseOffering`, `EnrollmentStatus`/`EnrollmentResult`,
> `DropStatus`/`DropResult`, and `CourseRegistrationService` per this design: one `ReentrantLock`
> guarding every enroll/drop/promote operation so check-then-act is atomic with no TOCTOU gaps;
> explicit, separate status codes for duplicate-enroll and duplicate-waitlist (not a generic
> failure); a FIFO-preserving structure for the waitlist that makes ordering obvious to a reader;
> typed results (never throw, never corrupt state) for invalid input; plain Java, no framework
> dependency. Also generate a CLI demo in `Main.java` exercising enroll, full-capacity waitlisting,
> ineligible rejection, and drop-and-promote in sequence."

**AI response:** Generated the five types and the service using `ReentrantLock`,
`LinkedHashSet<String>` for enrolled learners, `ArrayDeque<String>` for the waitlist, and a
`Main` demo matching the requested sequence.

**My verification:** read every method myself to confirm the lock scope, the atomicity of the
capacity check against the add, and the skip-vs-remove behavior in the promotion loop exactly
matched my Phase 2 specification. Compiled and ran the CLI demo manually — output matched the
expected enroll → waitlist → ineligible-reject → drop → promote sequence exactly, first try.

---

## Phase 4 — Test suite (proving my own acceptance criteria mapping)

**My test plan, specified up front:** I mapped every acceptance criterion to a concrete test
scenario myself before asking AI to write any test code, so the suite would be criteria-complete
by construction rather than generic coverage padding.

**Prompt:**
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

**Prompt (used to execute the refactor I'd already decided on, quickly and without typos):**
> "Extract a package-private `LearnerDirectory` class owning roster lookup/registration, with
> `CourseRegistrationService` delegating to it, while keeping the service's single lock covering
> every operation so atomicity is unaffected. Then add a test for dropping a learner who is
> waitlisted but was never enrolled, expecting `NOT_ENROLLED` with the waitlist left untouched."

**AI response:** Produced the extraction and the new test matching the specification exactly.

**My verification:** ran the full suite before and after the refactor (13 → 14 tests), confirming
`BUILD SUCCESS` both times and that the new test passed, validating the gap I'd identified was both
real and now closed.

---

## Phase 6 — Enterprise hardening (my standard, applied at speed)

**My standard, stated explicitly:** having the feature work wasn't the bar — I hold this code to
the same standard I'd expect in a production enterprise codebase: fail-fast validation, no nullable
return types, correct value-object identity semantics, defensive immutability, and structured,
PII-free logging. I specified all five requirements precisely in one prompt so AI could apply them
consistently across every file in one pass.

**Prompt:**
> "Apply end-to-end across the domain classes and the service: (1) `Objects.requireNonNull` on
> every constructor/public-method argument that must never be null; (2) replace any nullable-return
> API with `Optional` so the compiler enforces null-handling; (3) `equals`/`hashCode`/`toString` on
> `Learner` and `CourseOffering`, keyed on id; (4) `List.copyOf(...)` on snapshot accessors for
> defensive immutability; (5) SLF4J structured logging — INFO for business events, WARN for
> rejected/duplicate/ineligible/skipped attempts, parameterized messages, only `learnerId`/
> `courseId` logged, never names or other PII. Use `slf4j-api` at compile scope (correct for a
> reusable library — it depends only on the facade) and `slf4j-simple` at runtime scope for this
> project's own demo/test visibility."

**AI response:** Applied all five changes, including converting `LearnerDirectory.find()` to
`Optional<Learner>`.

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

---

## Phase 7 — Documentation (kept accurate to the code at every stage)

**My approach:** documentation that drifts from the code is worse than no documentation, so at each
phase above I had AI regenerate the relevant section of `DESIGN.md`/`README.md` directly from the
current source, and then I personally cross-checked every claim — the invariants, the policy
statement, test counts, file layout — against the actual code before accepting it. This is why the
docs went through three revisions as the implementation evolved, rather than being written once
and left stale.

---

## Net effect of this approach

Using AI this way let me spend my time on the parts that actually require engineering judgment —
the eligibility/promotion policy, the concurrency model, the SOLID boundary, the hardening
standard — while AI handled the repetitive work of writing classes, tests, and docs to match. Every
AI-generated artifact was verified against a specification I set first, and every defect found
(pool-sizing deadlock, a stale `Optional` call site) was caught by my own review and fixed by me,
not discovered by chance. That verification discipline is the same one I'd apply with or without
AI in the loop — AI simply made the execution faster.

## Files/decisions this informed

- `pom.xml` — JUnit 5 + Surefire; `slf4j-api` (compile) + `slf4j-simple` (runtime).
- `src/main/java/org/com/dcp` — domain model and service, refactored for SRP
  (`LearnerDirectory` extraction), then hardened (null-safety, `Optional`, `equals`/`hashCode`/
  `toString`, immutable snapshots, SLF4J logging).
- `src/main/java/org/com/dcp` — runnable CLI demo.
- `src/test/java/org/com/dcp` — functional + concurrency suites, extended with the
  waitlisted-but-not-enrolled-drop case.
- `DESIGN.md`, `README.md` — kept in sync with the implementation at every phase.

No credentials, secrets, or other sensitive data were used in any prompt or recorded here.