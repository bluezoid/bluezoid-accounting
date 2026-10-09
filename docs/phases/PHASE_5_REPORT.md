# Phase 5 Report — Sealed Posting Core

**Date:** 2026-09-02 · **Status:** Implementation complete, awaiting review
**Tests:** 333 passing · 0 failing · 3 UI skipped · `BUILD SUCCESS`
**Baseline before P5:** 281 passing (+52)

---

## 1. Implementation summary

A new Maven/JPMS module, `accubook-accounting`, holding the only code in the
application that writes `voucher` or `voucher_entry`.

| Layer | Type | Visibility |
|---|---|---|
| API | `PostingService` | public — `post`, `reverse`, nothing else |
| API | `PostingRequest`, `EntryLine`, `ReversalRequest`, `PostedVoucher`, `VoucherStatus` | public value types |
| Internal | `VoucherWriter`, `NumberAllocator`, `AuditWriter` | package-private, **unexported package** |
| Storage | migration 2 + P2 CHECK constraints and triggers | database |

## 2. Architectural boundaries

```
Use cases (sales, purchase, journal, Tally, OCR, scheduler, future payroll)
                    │  PostingRequest — data only, no connection
                    ▼
            PostingService                     exported
                    │
                    ▼
   VoucherWriter · NumberAllocator · AuditWriter    NOT exported
                    │
                    ▼
      P4 persistence guard → SQLite CHECK + triggers
```

`module-info.java` exports `com.accubook.accounting.posting` and **not**
`...posting.internal`. The module requires neither `accubook.licensing` nor
JavaFX; both are asserted by `PostingBoundaryTest`.

## 3. All posting entry points

**Two**, and they are the same method pair for every caller:

- `PostingService.post(PostingRequest)`
- `PostingService.reverse(String, ReversalRequest)`

There is no repository, builder, or DAO that writes a voucher. This is the
measured contrast with the reference application, which has **ten** distinct
voucher-creating call sites.

## 4. Database changes — migration 2

```sql
status CHECK (status IN ('DRAFT','POSTED','REVERSED','CANCELLED'))
CHECK (total_debit_e4 > 0)                              -- A-1
CHECK (is_posted = (CASE WHEN status IN ('POSTED','REVERSED') THEN 1 ELSE 0 END))  -- A-2
reverses_voucher_id · reversal_reason · posted_by
idempotency_key + UNIQUE partial index
originator_module
TRIGGER voucher_posted_no_update / _no_delete
TRIGGER voucher_entry_no_update_when_posted / _no_delete_when_posted
```

Table rebuilt with `foreign_keys = OFF`, since P2 measured that `DROP TABLE`
with FKs on cascade-deletes children. Existing rows are migrated:
`is_posted = 1 → 'POSTED'`, `REJECTED → CANCELLED`.

## 5. Tests added — 52

| Suite | Count | Covers |
|---|--:|---|
| `EntryLineTest` | 6 | one-sided, positive, reversal |
| `VoucherStatusTest` | 5 | lifecycle, terminal states, A-2 |
| `PostingRequestTest` | 9 | balance, A-1, F-3, defensive copy |
| `PostingBoundaryTest` | 4 | JPMS export, no public writer methods, no licensing/JavaFX |
| `PostingServiceIT` | 23 | posting, refusals, entitlement, idempotency, numbering, immutability, reversal |
| `PostingConcurrencyIT` | 5 | 120 concurrent posts, failure injection, counter rollback |

### Adversarial results — all refused

| Attack | Result |
|---|---|
| `UPDATE voucher` on a posted row | trigger aborts |
| `DELETE FROM voucher` on a posted row | trigger aborts |
| `UPDATE voucher_entry` of a posted voucher | trigger aborts |
| Empty voucher (`0 = 0`) | refused at construction **and** by CHECK |
| Imbalance of ₹0.005 | refused — no epsilon |
| Reverse a reversal | refused (A-4) |
| Reversal without a reason | refused |
| Post with READ_ONLY / DORMANT originator | refused |

### Concurrency

8 threads × 15 posts = **120 vouchers, 120 unique numbers, 0 failures**.
40 concurrent posts leave `SUM(debit) - SUM(credit) = 0`.
6 simultaneous posts with one idempotency key → **1 voucher**.

### Failure injection

Audit insert forced to abort after voucher and entries were written:
**0 vouchers, 0 entries**, only the seeded audit row. The voucher-number counter
rolls back with it.

### Idempotency

Replay returns the original with `replayed = true`; three repeated interactive
saves with one form-scoped key produce one voucher; different keys produce
different vouchers; no key produces two vouchers (correct — two genuine sales).

### Reversal

Contra voucher in the caller's period; original marked REVERSED and otherwise
byte-identical; entries mirrored; `SUM(debit) - SUM(credit) = 0` across both.

## 6. Defects found and fixed during implementation

**6.1 `CompanyService` trial balance would have silently returned zero.**
Production code filtered `is_posted = 1 AND status = 'APPROVED'`. The P5
lifecycle removes `APPROVED`, so every ledger balance would have become zero
with no error. Caught by the existing P2 integration tests. The status clause was
redundant once `is_posted` is derived, and was removed.

**6.2 `build.sh` excluded the posting core from the installer.**
A real build printed `excluded: accubook-accounting` — the shipped application
would not have been able to post at all. `ALWAYS_INCLUDED` now lists it.
**Caught by running the build, not by review.**

**6.3 `NumberAllocator` omitted `number_counter.updated_at`** (NOT NULL).

**6.4 A test fixture seeded the degenerate voucher.** `CoreSchemaConstraintIT`
seeded `total_debit_e4 = 0, total_credit_e4 = 0` — exactly the A-1 case. The
suite itself demonstrated that an entry-less voucher was previously legal.

## 7. Honest limits — what the tests do NOT prove

**7.1 The concurrency tests do not isolate the allocator.** Measured: replacing
the atomic `UPDATE ... last_number + 1` with a racy read-sleep-write **still
passes every concurrency test**, because `Database` serialises writers on a lock
and `BEGIN IMMEDIATE` takes the write lock up front. The safety comes from the
transaction boundary, not the allocator's SQL. The atomic form is kept because it
is correct independently, but these tests would not catch its removal. Documented
in the suite itself rather than left implied.

**7.2 The reflection test was corrected mid-implementation.** It first asserted
`setAccessible` throws. It does not — the test runs inside the same module, so
that assertion was false. It now checks the third, cheapest barrier: no method on
`VoucherWriter` is public. The real barriers remain the unexported package and
the P4 persistence guard.

**7.3 P1–P4 test-fixture changes.** Five fixtures changed, none weakened:
- `SchemaMigrationSafetyIT`: version assertions 1→2, fixture migrations
  renumbered 2→3 and 3→4 (they collided with the real migration 2)
- `CoreSchemaConstraintIT`, `ConcurrentAccountingWriteIT`: `'APPROVED'` →
  `'POSTED'`, zero-total voucher → non-zero

Every assertion they make is unchanged; only fixture data and version numbers
moved. No test was deleted or made less strict.

## 8. Files

**Created (16):**
```
accubook-accounting/pom.xml
accubook-accounting/src/main/java/module-info.java
  .../posting/PostingService.java  PostingRequest.java  PostedVoucher.java
  .../posting/EntryLine.java  ReversalRequest.java  VoucherStatus.java
  .../posting/PostingException.java  UnbalancedPostingException.java
  .../posting/DuplicatePostingException.java
  .../posting/internal/VoucherWriter.java  NumberAllocator.java
  .../posting/internal/AuditWriter.java  PostingInternals.java
docs/phases/PHASE_5_DESIGN.md   docs/phases/PHASE_5_REPORT.md
```
**Test files created (6):** `EntryLineTest`, `VoucherStatusTest`,
`PostingRequestTest`, `PostingBoundaryTest`, `PostingTestFixture`,
`PostingServiceIT`, `PostingConcurrencyIT`

**Modified (8):** `pom.xml` (module list), `build.sh`, `Schemas.java`
(migration 2), `CompanyService.java` (trial-balance filter),
`SchemaMigrationSafetyIT`, `CoreSchemaConstraintIT`, `ConcurrentAccountingWriteIT`,
`OPEN_DECISIONS.md`, `docs/logs/2026-09-02.md`

## 9. Status

- **Reference app:** untouched — `git status` clean
- **Staged:** nothing · **Committed:** nothing · **Pushed:** nothing
- **Installer:** rebuilt and verified, 70 MB `.deb`, all modules included

## 10. Remaining risks and deferred decisions

| Item | Phase | Note |
|---|---|---|
| Audit hash chain serialises bulk import | P7 | correct but sequential; measure before optimising |
| Prior-period restatement workflow | future | extension point left; not built (P5-2) |
| Posting *content* — which ledger for which document | P6/P7 | needs B-1 |
| Module smuggling test end to end | P5/P6 | still needs a real optional module (carried from P4) |
| Architecture test: every non-kernel table has an owner | P6 | carried from P4 |
| `DuplicatePostingException` is defined but unused | P6 | reserved for key-reuse-with-different-payload detection |

**No decision belonging to B-1, B-2, B-4 or B-5 was made.**
