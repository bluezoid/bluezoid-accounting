# Test Strategy

**Status:** PROPOSED · **Supersedes** `TESTING_STRATEGY.md` (kept for history)

---

## 1. Principle

> A feature is complete when its tests pass **and** the existing suite still
> passes. Compiling is not evidence of anything.

For an accounting product the tests are not a safety net bolted on afterwards —
they are the specification. Where the reference application has good tests, port
the tests **first**, then make them pass.

## 2. The reference suite as oracle — with a caveat

646 tests exist (520 unit, 105 integration, 21 Playwright). But:

- The **21 Playwright tests default to `https://accubook.tensparrows.com`** —
  production. They are smoke-level navigation checks and are **not a usable
  oracle** for a desktop port. Effective oracle: **625 tests.**
- **Some tests do not test what they appear to.** The year-end test
  (`costing-and-year-end.test.ts:134-209`) **never calls the balance-sheet
  route**; it re-implements the arithmetic inline and asserts its own
  re-implementation is self-consistent, while dropping `isPosted: true` from the
  filter. Deleting the feature would not fail it.

**Rule: before porting a test, read it and confirm it exercises the production
path.** A tautological test ported faithfully gives false confidence.

### Coverage map — what is safe to trust

| Trust | Domains |
|---|---|
| **High** (port the tests) | GST returns (~124), TDS/TCS (~75), payroll calculation, posting invariants, permissions/scoping, money helpers, bank-reconciliation scoring, recurring invoices, concurrency (14 integration files with deliberate interleaving) |
| **None** (treat as unspecified) | Budgets, approvals, quotations, sales/purchase orders, cost centres, projects, expense claims, attendance, leave, report templates, notifications, audit log, **multi-currency**, GSTReturn persistence, all master CRUD, **the entire UI (73 pages)** |

For the second group: read the code **and** `docs/UserManual.md`, then write
tests that define intended behaviour. Do not assume the reference is correct
there — several of its worst defects (credit notes, valuation `asOf`) are in
untested areas, which is not a coincidence.

## 3. Test levels

### 3.1 Unit — domain logic, no I/O
Posting invariants; money arithmetic and scale mapping; GST computation and each
return; TDS/TCS threshold branches; payroll components; valuation and cost
layers; year-end close; date/fiscal-year logic; licence token verification;
entitlement resolution; module state resolution; migration version logic.

**Property-based tests earn their place here:**
- money round-trips `BigDecimal → long → BigDecimal` exactly, at every scale;
- **every** generated voucher either balances or is rejected — never posts
  unbalanced;
- `cgst + sgst + igst` always reconciles to the computed total;
- allocations and splits always sum **exactly** to the whole;
- a credit note always moves revenue and receivables in the **opposite**
  direction to an invoice (directly targets D-23).

### 3.2 Integration — real SQLite, real transactions
Posting end to end; document numbering under concurrency; migrations from every
shipped version on **populated** data; backup/restore round trip; entitlement
gating; multi-company isolation; `SQLITE_BUSY` handling; company switching.

Adopt the reference harness's good discipline:
- a **safety guard** that refuses to run against anything but a test database;
- full reset between tests;
- **deterministic interleaving** — their `Gate` helper forces
  `A reads → B reads → A commits → B commits` rather than hoping for a race.

Concurrency semantics differ under SQLite (single writer, `SQLITE_BUSY` instead
of constraint-violation races), so those 14 files are a **specification to
re-satisfy under different failure modes**, not code to copy.

### 3.3 UI — JavaFX (TestFX)
Activation; company create/switch; voucher entry; invoice and **credit note**
creation; reports render; read-only state when expired; module absent/dormant;
upgrade; downgrade; backup; restore. Workflow-level, not pixel-level.

This is the **largest untested surface** — 73 web pages have zero component
tests — so the desktop UI has no oracle and must be specified as it is built.

### 3.4 Security
Every row of THREAT_MODEL.md §8 is a test: tampered entitlement, replay, clock
rollback, cross-machine copy, wrong passphrase, tampered archive, expired
subscription (export still works), injection, path traversal, diagnostics
redaction, audit-chain break.

### 3.5 Migration
Every shipped schema version → current, on realistic populated data; interrupted
at each step (kill the process) → rollback verified; newer-schema file refused;
**trial balance == 0 before and after**; `foreign_key_check` clean.

### 3.6 Upgrade / downgrade
BASIC→PRO, PRO→BASIC, and the **PRO→BASIC→PRO round trip** asserting zero data
loss and continuity of number counters; dormant-module writes refused; dormant
data readable; posted dormant vouchers still in the trial balance; migration
while dormant.

### 3.7 Backup / restore
Round-trip fidelity; restore of a backup taken **during** concurrent writes;
corrupted archive refused; wrong passphrase; older-schema backup migrates
forward; newer-schema backup refused; interrupted restore leaves live data
intact; interrupted upload leaves no partial object; restore onto a **different
machine**.

### 3.8 Failure injection
Interrupted update; interrupted backup; corrupt database; network unavailable;
disk full mid-write; expired subscription mid-session; clock changes; second app
instance; company file on a network path; read-only filesystem.

### 3.9 Build verification
Each plan's installer builds; installs on a **clean VM** per OS; launches with no
system Java; module inclusion/exclusion is genuinely physical (assert the classes
are absent, not merely hidden).

## 4. Accounting invariants — asserted continuously

Cheap, and they catch whole classes of bug. Assert after every posting test,
every migration, every backup, and on every company open:

1. Every posted voucher balances **exactly** (no epsilon).
2. Trial balance sums to zero across all posted entries.
3. No orphaned voucher entries (`foreign_key_check` clean).
4. No entry line carries both a debit and a credit.
5. If any balance is cached: cached == recomputed from the journal.
6. Document numbers unique per company/type/year, never reused.
7. Inventory: sum of layer remainders == stock on hand (perpetual model).
8. A closed fiscal year accepts no new postings.

## 5. Regression policy

- Every fixed bug gets a test that **fails without the fix**.
- Every defect in `DEFECTS_IN_REFERENCE_APP.md` marked "do not reproduce" gets a
  test asserting the **corrected** behaviour, citing the defect id.
- Full unit + integration suite runs before any work is reported complete.
- Failures are reported with output — **never hidden, never worked around**.

## 6. What "good coverage" means here

Not a percentage:

- **100% of the accounting invariants** in §4 asserted.
- **Every tax computation path** including boundaries — the reference proves the
  value (ESI at *exactly* ₹21,000; the Form 16A `31-March 23:59:59.999` boundary
  that had silently dropped the heaviest TDS day of the year).
- **Every migration** exercised on realistic data.
- **Every entitlement state** tested at the use-case boundary.
- **Every "do not reproduce" defect** covered by an inverted test.
- Backup/restore verified including failure and interruption paths.

## 7. CI

Per platform (Windows/macOS/Linux runners): build → unit → integration →
package → sign → smoke-install on a clean VM → publish with hashes and a signed
update manifest. Migration and round-trip suites run on every change to schema
or entitlement code.
