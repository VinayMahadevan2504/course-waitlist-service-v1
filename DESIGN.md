# Design Specification — Course Enrollment & Waitlist Slice

## 1. Scope / Service Boundary

This delivers exactly one bounded slice: enrollment, drop, and FIFO waitlist promotion for
**one course offering**. There is no catalog, no multi-term registration, no identity/payment
integration, and no frontend — consistent with the assessment's scope boundaries.

The repository's starter was an empty Maven/Java 17 project with no domain model, so this was
treated as the "minimal service" fallback: a plain, framework-free Java library with an
in-memory store, exercised by a small CLI demo (`Main`) and verified by JUnit 5 tests.

## 2. Data Model

| Type | Responsibility |
|---|---|
| `Learner` | id, display name, set of completed course ids (used for prerequisite checks). Identity is by id. |
| `CourseOffering` | id, title, fixed `capacity`, optional `prerequisiteCourseId`. Owns the eligibility rule: `isEligible(learner)` = no prerequisite, or learner has completed it. |
| `LearnerDirectory` | package-private roster lookup/upsert, kept separate from enrollment orchestration (SRP). Not independently thread-safe; callers must guard concurrent access. `find()` returns `Optional<Learner>` to make "not found" explicit and unmissable at compile time. |
| `EnrollmentStatus` / `EnrollmentResult` | Outcome of an enroll request: `ENROLLED`, `WAITLISTED`, `ALREADY_ENROLLED`, `ALREADY_WAITLISTED`, `INELIGIBLE`, `UNKNOWN_LEARNER`, plus a human-readable message. |
| `DropStatus` / `DropResult` | Outcome of a drop request: `DROPPED`, `NOT_ENROLLED`, `UNKNOWN_LEARNER`, plus the id of any learner promoted as a side effect. |
| `CourseRegistrationService` | The service boundary. Holds the enrolled set and the FIFO waitlist for one `CourseOffering`, delegating roster lookups to `LearnerDirectory`. |

## 3. API (service boundary)

```
registerLearner(Learner)                 -> void      (idempotent upsert into the roster)
enroll(String learnerId)                 -> EnrollmentResult
drop(String learnerId)                   -> DropResult
getEnrolledLearnerIds()                  -> List<String>  (snapshot, insertion order)
getWaitlistedLearnerIds()                -> List<String>  (snapshot, FIFO order)
```

This is intentionally a plain Java API (no REST/HTTP layer), since the starter required no
specific transport and adding one would be out of scope. The result types are designed so a thin
REST controller could wrap this service directly if needed later (status enum -> HTTP code,
message -> body).

### 3.1 Example usage (illustrating the API contract)

```java
CourseRegistrationService service = new CourseRegistrationService(
        new CourseOffering("CS-201", "Data Structures", 2, "CS-101"));

service.registerLearner(new Learner("alice", "Alice A.", Set.of("CS-101")));
service.registerLearner(new Learner("bob",   "Bob B.",   Set.of("CS-101")));
service.registerLearner(new Learner("carol", "Carol C.", Set.of()));        // no prerequisite

service.enroll("alice");  // -> ENROLLED        (1/2 seats used)
service.enroll("bob");    // -> ENROLLED        (2/2 seats used, now full)
service.enroll("carol");  // -> INELIGIBLE       (missing CS-101; never queued)
service.enroll("alice");  // -> ALREADY_ENROLLED (duplicate request, no state change)

DropResult result = service.drop("alice");
// -> DROPPED, promotedLearnerId == null (no eligible waitlisted learner existed)
```

Every call returns a typed result the caller branches on — there is no boolean/`null` return a
caller could misinterpret or ignore.

## 4. Enrollment & Capacity Invariants

1. A learner is enrolled in this offering **at most once** (checked via `ALREADY_ENROLLED`).
2. A learner appears on the waitlist **at most once** (checked via `ALREADY_WAITLISTED`).
3. `enrolled.size()` never exceeds `CourseOffering.capacity` — enforced inside the same
   lock-protected method that reads and mutates state, so check-then-act is atomic.
4. Waitlist order is FIFO; insertion order is preserved via `ArrayDeque` with duplicate
   prevention via a `contains` check (acceptable at this list's expected scale; a sorted
   `Set` indexed by join-sequence would be the next optimization if waitlists grew large).

## 5. Prerequisite / Waitlist-Promotion Policy (stated assumption)

- **Prerequisite rule**: a course offering has at most one prerequisite course id. A learner is
  eligible if the offering has no prerequisite, or the learner's completed-course set contains it.
- **Ineligible learners never join the waitlist**: `enroll()` rejects an ineligible learner
  outright (`INELIGIBLE`), consistent with "an eligible learner joins the waitlist" in the
  acceptance criteria. They are not enrolled and not queued.
- **Skip-but-retain at promotion time**: eligibility can still change *after* a learner joins the
  waitlist (e.g. a prerequisite-completion record is corrected or a transcript update lands after
  the learner queued). When a seat opens, `drop()` promotes the **earliest eligible** learner in
  FIFO order. If the learner at the front has since become ineligible, they are **skipped, not
  removed** — they keep their place in line, and the next eligible learner is promoted instead.
  This mirrors real registration systems: a waitlisted learner shouldn't lose their spot because
  of one failed promotion attempt; they should get another chance once eligible again.
- If no eligible learner is found anywhere in the waitlist, the seat is simply left open
  (`promotedLearnerId == null`).

This policy is covered directly by
`ineligibleWaitlistedLearnerIsSkippedButRetainedDuringPromotion` in the test suite.

## 6. Concurrency Approach

All mutation (`enroll`, `drop`, the internal promotion step) and all snapshot reads are guarded
by a single `ReentrantLock` on the service instance, making "check capacity, then add" and "drop,
then promote" atomic with respect to each other. This is intentionally coarse-grained:

- **Why coarse-grained is acceptable here**: the scope is one offering, in memory, with modest
  expected concurrency (one classroom's worth of registration traffic). A single lock keeps the
  invariants trivially easy to reason about and verify.
- **What would change at scale / with persistence**: replace the in-process lock with a
  database transaction around the offering row (e.g. `SELECT ... FOR UPDATE` or an optimistic
  version column on `CourseOffering`), so multiple service instances stay consistent. The internal
  method shapes (`enroll`/`drop`/`promoteEarliestEligible`) would map directly onto a single
  transactional unit of work.
- **Verification**: `CourseRegistrationServiceConcurrencyTest` fires one enrollment per learner
  from 100 threads simultaneously (barrier-synchronized start) against a 10-seat offering, and
  asserts exactly 10 enrolled, exactly 90 waitlisted, no duplicates, and no lost learners.

## 7. Error Handling

Invalid input (unknown learner id) and duplicate requests (double enroll, double drop) return a
typed result with a clear message rather than throwing or silently no-op'ing, and never mutate
state. This keeps the API safe for callers (CLI or future HTTP layer) to surface directly to
users.

Two distinct failure categories are handled differently, deliberately:

- **Programmer errors** (a `null` reference passed where one isn't allowed — e.g. `null`
  `learnerId`, `null` `Learner`, `null` offering) fail fast via `Objects.requireNonNull(...)` at
  the public API boundary. These are bugs in the caller, not business outcomes, so they throw
  `NullPointerException` immediately rather than propagating `null` deeper into the state machine.
- **Business-rule outcomes** (an unknown-but-non-null learner id, a duplicate request, an
  ineligible learner) are never exceptions — they are typed `EnrollmentResult`/`DropResult`
  values the caller is expected to branch on, consistent with the acceptance criteria's "return
  clear results without corrupting state."
- Internally, `LearnerDirectory.find(id)` returns `Optional<Learner>` instead of a nullable
  `Learner`, so the service cannot forget to null-check a roster lookup — the compiler enforces
  handling both branches (`UNKNOWN_LEARNER` vs. proceed).
- Snapshot accessors (`getEnrolledLearnerIds()`, `getWaitlistedLearnerIds()`) return
  `List.copyOf(...)` — immutable copies — so a caller cannot accidentally corrupt internal state
  by mutating a returned list.

## 7.1 Testing Strategy (why each test category exists, not just what it checks)

Tests are organized to map directly onto the acceptance criteria, so each category has a specific
reason to exist rather than being generic coverage:

| Category | What it proves | Why it matters here |
|---|---|---|
| Available capacity | `enroll()` succeeds and occupies a seat when room remains | Baseline happy path — AC1 |
| Exactly-full capacity | The seat-counting boundary is correct (capacity `N` allows exactly `N`, not `N-1` or `N+1`) | Off-by-one is the most common capacity bug |
| Duplicate enroll / duplicate waitlist join | Re-requesting does not create a second record or move queue position | AC2 — idempotency under repeated client retries |
| FIFO promotion order | The *earliest* waitlisted learner is promoted, not an arbitrary one | AC3 — ordering is a correctness property, not a nice-to-have |
| Prerequisite failure | An ineligible learner is rejected at enroll time, never silently queued | AC1/AC4 — eligibility must gate entry, not just promotion |
| Skip-but-retain at promotion | An ineligible learner at the front of the queue is skipped, not evicted, and the next eligible learner is promoted instead | AC4 — the one policy decision the assessment explicitly calls out as needing a stated, consistently-applied answer |
| Unknown learner / unknown drop target | Returns a typed `UNKNOWN_LEARNER` result, never throws, never mutates state | AC5 — invalid requests must fail safely |
| Drop of a waitlisted-but-never-enrolled learner | Returns `NOT_ENROLLED` and leaves the waitlist untouched | AC5 — a gap I found during self-review (see `AI-USAGE.md` Phase 5); confirms drop can't be used to covertly remove a waitlist entry |
| Repeated/duplicate drop | Second drop on an already-dropped learner is a safe no-op, not a corrupting double-promotion | AC5 — guards against a seat being "opened" twice for one real vacancy |
| Concurrency (100 threads vs. 10 seats) | Final enrolled count never exceeds capacity; nobody is duplicated or lost under simultaneous requests | AC6 — the assessment explicitly asks for a concurrency test when the design involves locking |

Each row corresponds to exactly one test method in `CourseRegistrationServiceTest` or
`CourseRegistrationServiceConcurrencyTest` — there is no category of acceptance-criteria behavior
left unverified, and there is no test that exists purely for coverage-percentage padding.

## 8. Assumptions & Tradeoffs

- No persistence: state lives in memory for the lifetime of the `CourseRegistrationService`
  instance. Acceptable per scope ("in-memory store is sufficient").
- No concept of "drop from waitlist" (a learner voluntarily leaving the queue without ever being
  enrolled) was requested by the acceptance criteria, so it isn't implemented; `drop()` is scoped
  to enrolled learners only, matching AC3's wording ("dropping an enrolled learner").
- `Learner.completedCourseIds` is mutable only via re-registration (`registerLearner` upsert);
  there's no dedicated "record prerequisite completion" API, since that's outside this slice.
- Single course offering per service instance by design — matches "implement only one offering's
  slice" in the scope boundaries. Supporting multiple offerings would mean one service instance
  (or one lock) per offering, not a shared global lock.

## 9. Limitations & Follow-up Risks

- Coarse-grained locking would serialize all traffic for one offering under very high concurrent
  load; fine for this bounded scope, but would need the transactional approach above to scale out.
- `waitlist.contains()` for duplicate checks is O(n); irrelevant at expected waitlist sizes, but
  would need an index (e.g. `Set<String>` mirrored alongside the `Deque`) if waitlists grew large.
- No notification/acceptance workflow for promoted learners (e.g. "you have 24 hours to confirm")
  — out of scope here, but a real Register product would need it before a promotion is final.
- No audit trail / event log of enrollment and promotion decisions; would be valuable for support
  and dispute resolution in a production system.
- **Logging**: `CourseRegistrationService` logs via SLF4J — INFO for business events (enroll,
  waitlist, drop, promotion, no-eligible-candidate outcomes) and WARN for rejected/duplicate/
  ineligible/skipped attempts, each message parameterized (not concatenated) and tagged with
  `learnerId`/`courseId` only — never a learner's name or other PII. `slf4j-api` is a **compile**-
  scope dependency (the correct choice for a reusable library/service: it depends only on the
  facade and lets the consuming application choose its own binding); `slf4j-simple` is added at
  **runtime** scope solely so the CLI demo and test output show log lines without requiring the
  consumer to wire a binding themselves. Full structured/ECS JSON logging (e.g.
  `logstash-logback-encoder`) was considered and deferred as disproportionate to this bounded
  slice's scope — the facade usage means swapping in a JSON-emitting binding later requires no
  code change, only a dependency and config change.
- `CourseRegistrationService` knows both `LearnerDirectory` and the enrollment/waitlist state; this
  is an intentional composition (not inheritance) so each class stays single-purpose and unit-
  testable independently, while the service still owns the single lock needed for atomicity.