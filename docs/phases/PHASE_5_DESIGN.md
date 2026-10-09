# Phase 5 Design — Sealed Posting Core

**Status:** DESIGN ONLY — no code written · **Date:** 2026-09-01
**Blocked by:** nothing. P5 needs none of B-1, B-2, B-4, B-5.
**Baseline verified:** 281 passing · 0 failing · 3 UI skipped (`mvn clean verify`)

> P5 is not "port `applyLedgerEntries` to Java". The reference application
> *has* that function, and it is genuinely careful — exact comparison, org
> scoping, unresolved-ledger detection. It still did not hold, because being
> careful is not the same as being unavoidable.

---

## 1. What the reference actually does — measured, this phase

Ten distinct call sites create vouchers:

```
src/app/api/organizations/[orgId]/vouchers/route.ts
src/app/api/organizations/[orgId]/payments/route.ts
src/app/api/organizations/[orgId]/receipts/route.ts
src/app/api/organizations/[orgId]/payroll/post-month/route.ts
src/app/api/organizations/[orgId]/payroll/pay-month/route.ts
src/backend/services/billing/post-invoice.ts
src/backend/services/billing/post-bill.ts
src/backend/services/manufacturing/post-wo-journal.ts
src/backend/services/migration/tally.ts
src/backend/services/payroll/post-month.ts
```

Each writes `voucher` itself and *then* calls `applyLedgerEntries` for the
ledger side — or, in Tally's case, does not.

### 1.1 The choke point is a convention, not a boundary

`applyLedgerEntries` (`posting.ts:392`) validates well:

```ts
const totalDebit  = sum(entries.map(e => D(e.debitAmount)));
const totalCredit = sum(entries.map(e => D(e.creditAmount)));
if (!totalDebit.equals(totalCredit)) throw new UnbalancedEntriesError(...);
```

Exact comparison, no epsilon. It also refuses ledgers outside the tenant
(`LedgerScopeError`) — with a comment explaining that a partial application
produces "a silent divergence between the journal and the ledger balances".

**The author understood the invariant precisely.** It still failed, three ways:

| # | Failure | Where | Consequence |
|---|---|---|---|
| **F-1** | Tally import never calls it — inlines its own `applyLedgerBalances` with no balance check, no org scoping, `if (!nature) continue` | `migration/tally.ts:697-718` | D-22: imported books need not balance |
| **F-2** | It validates *the entries handed to it*, never that they match the `voucher` header written separately | all ten paths | header and entries can disagree |
| **F-3** | The API layer pre-checks balance with a **₹0.01 epsilon** | `vouchers/route.ts:136` via `closeEnough` (`money.ts:26`) | a voucher off by a paisa posts as "balanced" |

**F-3 is new to this phase and was not in the P0 catalogue.** `closeEnough(a, b,
epsilon = "0.01")` is the API-layer gate. `applyLedgerEntries` is exact, so the
two disagree about what balanced means — the looser one runs first and decides
what reaches the strict one.

**F-2 is structural and the most instructive.** `post-wo-journal.ts:92` writes:

```ts
totalDebit: amount,
totalCredit: amount,
```

The header is balanced *by construction*. Nothing verifies that the entries
subsequently passed to `applyLedgerEntries` sum to `amount`. Both halves can be
internally valid while disagreeing with each other.

### 1.2 The lesson for P5

> A shared helper is only a choke point for code that chooses to call it.

Six of the ten paths write `tx.voucher.create(...)` directly. Nothing in the type
system, the module graph, or the database prevented that. **P5's job is to make
the equivalent structurally impossible in Java**, not to write a better helper.

---

## 2. What P2 already guarantees

The schema does real work here, and P5 must not duplicate it in Java:

```sql
CHECK (total_debit_e4 = total_credit_e4)                    -- voucher
CHECK (debit_amount_e4 = 0 OR credit_amount_e4 = 0)         -- one-sided
CHECK (debit_amount_e4 > 0 OR credit_amount_e4 > 0)         -- non-zero
CHECK (is_posted = 0 OR posted_at IS NOT NULL)
CREATE TRIGGER audit_log_no_update / audit_log_no_delete    -- append-only
```

Scaled INTEGER (B-3) means `total_debit_e4 = total_credit_e4` is an **exact
integer comparison**. F-3 cannot be reproduced here even if someone wanted it —
there is no epsilon available at the storage layer.

**So the header-level invariant is already enforced by SQLite.** What P2 does
*not* enforce is F-2: that the entries sum to the header. SQLite cannot express
that as a `CHECK` (it spans rows).

---

## 3. Design — three layers, matching the P4 pattern

P4 established that one layer depending on developer discipline is not enough.
P5 applies the same shape.

```
   Use cases: Sales · Purchase · Journal · Tally · OCR · scheduler · future payroll
                                  │
                                  │  PostingRequest (data only)
                                  ▼
   ┌──────────────────────────────────────────────────────────┐
   │  L1  PostingService  — the only public entry point       │
   │        entitlement · validate · balance · number · audit │
   └──────────────────────────────────────────────────────────┘
                                  │  package-private
                                  ▼
   ┌──────────────────────────────────────────────────────────┐
   │  L2  VoucherWriter   — package-private, no public ctor   │
   │        writes voucher + voucher_entry together, or not   │
   └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼
   ┌──────────────────────────────────────────────────────────┐
   │  L3  Persistence guard (P4) + SQLite CHECK constraints   │
   └──────────────────────────────────────────────────────────┘
```

### 3.1 L1 — `PostingService`, the only way in

```java
public final class PostingService {
    public PostedVoucher post(PostingRequest request) { ... }
    public PostedVoucher reverse(VoucherId original, ReversalRequest request) { ... }
}
```

`PostingRequest` is an immutable value: fiscal year, voucher type, date,
narration, entries, an optional idempotency key, and the originating module id.
It carries no database handle and no connection, so a caller cannot smuggle a
transaction in.

### 3.2 L2 — sealed, and what "sealed" means concretely

The JPMS module exports `com.accubook.posting` and **does not export**
`com.accubook.posting.internal`. `VoucherWriter` lives in the latter with no
public constructor.

P4 measured that JPMS is defeatable by reflection when a module is on the path.
So sealing is necessary but not sufficient — which is exactly why L3 exists and
why the P4 persistence guard is load-bearing rather than decorative.

**Table ownership.** `voucher`, `voucher_entry` and `number_counter` are owned by
the `accounting` **kernel** module, so they are always writable and never gated
by entitlement — the trial balance must survive any downgrade. Entitlement is
checked on the *originating module* instead (§3.3).

### 3.3 Entitlement — on the originator, not the ledger

This is the subtle part, and it follows from a P4 test that already exists
(`ledgerIsNotOwnedByPayroll`):

> A payroll journal entry is a **ledger** entry. If payroll owned the ledger, a
> downgrade would stop the trial balance being maintained and the books would
> cease to balance.

So `PostingRequest` names its originating module, and `PostingService` calls
`EntitlementGuard.requireWritable(originator, "post a voucher")`. Payroll going
READ_ONLY stops *new payroll postings*; it does not make the ledger read-only,
and it never touches vouchers already posted.

---

## 4. The twelve questions, answered

| # | Question | Answer | Proven by |
|---|---|---|---|
| 1 | Can a feature bypass the posting core? | No public API writes `voucher`. Architecture test enumerates every write path. | `PostingBoundaryArchitectureTest` |
| 2 | Can an unbalanced journal reach SQLite? | Three independent barriers: L1 exact check, L2 header-from-entries, L3 `CHECK`. **No epsilon anywhere.** | `UnbalancedPostingTest` |
| 3 | Can a multi-entry transaction partially commit? | One `UnitOfWork`; P2 proved SQLite does not poison on constraint violation, so rollback is explicit. | `PartialCommitFailureIT` |
| 4 | Can two writers allocate the same voucher number? | `BEGIN IMMEDIATE` + `number_counter` UPDATE inside the same transaction. | `ConcurrentVoucherNumberIT` |
| 5 | Can a voucher post twice? | Optional idempotency key, `UNIQUE`; replay returns the original. | `IdempotentPostingTest` |
| 6 | Can READ_ONLY reach a mutating use case? | `requireWritable` at L1; persistence guard at L3. | `ReadOnlyPostingTest` |
| 7 | Can direct repository access bypass the guard? | No repository can write `voucher` — `VoucherWriter` is unexported and package-private. | `DirectWriteBypassTest` |
| 8 | Can an exception leave partial data? | Rollback on any `SQLException` **or** `RuntimeException`. | `FailureInjectionIT` |
| 9 | Can audit and accounting diverge? | Audit is written **inside** the same transaction, not after it. | `AuditAtomicityIT` |
| 10 | Can downgrade affect existing vouchers? | Ledger tables are kernel-owned; entitlement gates the originator only. | `DowngradePreservesVouchersIT` |
| 11 | Can a posted voucher be modified or deleted? | No update/delete API; triggers refuse it. §5. | `PostedVoucherImmutabilityIT` |
| 12 | How does correction work? | Reversal by contra-voucher. §5. | `ReversalTest` |

---

## 5. Immutability and reversal — designed now, deliberately

You flagged this as the item that must not be discovered during Sales/Purchase.
Agreed, and it is the one place P5 adds schema.

**Posted vouchers are immutable.** No API updates or deletes a voucher with
`is_posted = 1`. Two `BEFORE UPDATE` / `BEFORE DELETE` triggers enforce it in the
database, mirroring the audit-log triggers P2 already ships:

```sql
CREATE TRIGGER voucher_posted_no_update BEFORE UPDATE ON voucher
  WHEN OLD.is_posted = 1
  BEGIN SELECT RAISE(ABORT, 'A posted voucher cannot be modified'); END;
```

DRAFT vouchers remain editable — that is a working document, not a statutory
record. The transition to posted is one-way.

**Correction is a reversal**, never an edit: a new voucher with debits and
credits swapped, dated in an open period, linked by `reverses_voucher_id`, and
itself immutable. The original stays in the trial balance. This is what an
auditor expects and what the reference application's credit-note path got wrong
(D-23 posts backwards because `decideInvoiceEntries` has no `CREDIT_NOTE`
branch — P5's reversal is a single tested path rather than a per-document
branch, which is how that class of defect is avoided).

**Schema additions (migration 3):**

```sql
ALTER TABLE voucher ADD COLUMN reverses_voucher_id TEXT REFERENCES voucher (id);
ALTER TABLE voucher ADD COLUMN idempotency_key TEXT;
CREATE UNIQUE INDEX voucher_idempotency ON voucher (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE TRIGGER voucher_posted_no_update ...
CREATE TRIGGER voucher_posted_no_delete ...
CREATE TRIGGER voucher_entry_no_update_when_posted ...
```

`ON DELETE CASCADE` already exists on `voucher_entry`. P2 measured that with
`foreign_keys = ON`, dropping a parent destroys children — so the no-delete
trigger on `voucher` is what actually protects entries.

---

## 6. Voucher numbering

`number_counter` (P2) is keyed by scope. Allocation happens **inside** the
posting transaction, after `BEGIN IMMEDIATE`:

```sql
UPDATE number_counter SET last_number = last_number + 1 WHERE scope = ?;
SELECT last_number FROM number_counter WHERE scope = ?;
```

`BEGIN IMMEDIATE` takes the write lock up front, so two concurrent posts queue
rather than race. P2 measured that a *deferred* transaction upgrading its lock
fails with `SQLITE_BUSY` immediately regardless of `busy_timeout` — which is why
IMMEDIATE is not optional here.

A gap is acceptable on rollback; a duplicate is not. Numbers are for humans,
uniqueness is for the law.

---

## 7. Test strategy

**Unit** — balance arithmetic at scale 4, one-sided entries, zero-amount refusal,
reversal construction, idempotency key handling.

**Integration** — full post against real SQLite; rollback leaves zero rows;
audit written atomically; numbering under contention.

**Architecture** — no module outside `accubook-accounting` can reference
`VoucherWriter`; every posting path resolves to `PostingService`. This is the
test that would have caught the Tally bypass.

**Security / adversarial**
- direct `INSERT INTO voucher` through a repository → refused by the P4 guard
- reflection into `posting.internal` → module not exported
- posting with a READ_ONLY originator → refused
- `UPDATE voucher SET ... WHERE is_posted = 1` → trigger aborts
- an entry list that disagrees with a supplied header → **F-2 regression test**
- a balance off by ₹0.005 → refused (**F-3 regression test**: no epsilon)

**Concurrency** — N threads posting simultaneously: every voucher number unique,
no lost writes, trial balance sums to zero afterwards.

**Failure injection** — throw after the voucher insert but before entries; after
entries but before audit; during numbering. Each must leave **zero** rows.

**Regression** — all 281 existing tests stay green.

---

## 8. JPMS layout

```
accubook-accounting/
  src/main/java/
    module-info.java                     exports com.accubook.accounting.posting;
                                         (internal NOT exported)
    com/accubook/accounting/posting/
      PostingService.java      PostingRequest.java     PostedVoucher.java
      EntryLine.java           ReversalRequest.java    PostingException.java
      UnbalancedPostingException.java    DuplicatePostingException.java
    com/accubook/accounting/posting/internal/
      VoucherWriter.java       NumberAllocator.java    AuditWriter.java
      BalanceValidator.java
```

Depends on `accubook-platform`, `accubook-persistence`, `accubook-module-api`.
**Not** on `accubook-licensing` — it receives an `EntitlementGuard`, matching the
P4 rule that a module never learns licences exist. **Not** on JavaFX.

---

## 9. Decisions needed from you

**P5-1 · Posting core as its own Maven module?**
Recommend **yes** — `accubook-accounting`. JPMS unexported packages are the
sealing mechanism; keeping it in `accubook-app` would put it beside code that
must not reach it.

**P5-2 · Reversal date policy.** When the original's period is closed, must the
reversal be dated in the current open period (recommended), or may it carry the
original date? Affects comparative reporting in P11.

**P5-3 · Idempotency keys mandatory or optional?**
Recommend **optional but strongly typed** — required for non-interactive callers
(scheduler, OCR, Tally), optional for interactive posting. Making it mandatory
everywhere burdens the UI path for no benefit; making it unavailable is how the
reference's importers became re-runnable.

**P5-4 · Does P5 also port the *content* of posting decisions** (which ledger is
debited for a sales invoice), or only the mechanism? Recommend **mechanism only**
— content is P6/P7 and depends on B-1.

**None of these are business decisions I can make for you.** They are recorded in
`OPEN_DECISIONS.md` as P5-1…P5-4.

---

## 10. Explicitly NOT in P5

GST · inventory costing · banking · payroll · manufacturing · reporting · chart
of accounts content · document numbering formats per voucher type.

The interfaces are designed so those modules use the core without bypassing it;
none of their logic is written here. No decision belonging to B-1, B-2, B-4 or
B-5 is pre-empted.

---

## 11. Adversarial review of this design

Run against §3–§6 before presenting. Four findings, three of which change the
design.

### A-1 · The empty-voucher hole — CHANGES THE DESIGN

`CHECK (total_debit_e4 = total_credit_e4)` is satisfied by **0 = 0**.

A voucher with `total_debit_e4 = 0`, `total_credit_e4 = 0` and **no entries at
all** passes every P2 constraint and every balance check in §4 question 2. It is
"balanced" and meaningless. `voucher_entry` has a non-zero CHECK, but nothing
requires a voucher to *have* entries — SQLite cannot express that as a `CHECK`.

**Fix:** L1 refuses a `PostingRequest` with fewer than two entries, and refuses
a zero total. Add `CHECK (total_debit_e4 > 0)` in migration 3 to close it at the
storage layer too. Test: `EmptyVoucherTest`, `SingleEntryVoucherTest`.

This is the same shape as F-2 — an invariant that reads as enforced but has a
degenerate satisfying case.

### A-2 · `status` and `is_posted` are independent — CHANGES THE DESIGN

The schema permits `status = 'CANCELLED'` with `is_posted = 1`, and
`status = 'DRAFT'` with `is_posted = 1`. Only `is_posted = 0 OR posted_at IS NOT
NULL` is constrained.

A cancelled-but-posted voucher would sit in the trial balance while the UI shows
it cancelled — precisely the "balanced on paper, wrong in the ledger" divergence
P2's comment warns about.

**Fix:** migration 3 adds
`CHECK (is_posted = 0 OR status = 'APPROVED')`. Posting sets both together in
one statement. Cancellation of a posted voucher is not a status change — it is a
reversal (§5). Test: `PostedStatusConsistencyTest`.

### A-3 · Audit hash chain is a serialization point — NOTED, NOT FIXED

`audit_log.previous_hash` + `UNIQUE (sequence)` means every posting must read the
last hash and append. Under `BEGIN IMMEDIATE` this is safe — writes are already
serialized — so correctness holds. But it makes bulk import (Tally, P7 OCR)
strictly sequential.

Not a P5 problem: correctness first, and P5 posts one voucher at a time. Flagged
for **P7** when bulk paths arrive. Measure before optimising.

### A-4 · Reversal of a reversal

Nothing in §5 stops reversing a reversal, producing a chain. That is legitimate
(a correction can itself be wrong), but it must not become a cycle.

**Fix:** `reverses_voucher_id` must point at a voucher that is not itself already
reversed — enforced in L1 with a lookup, since SQLite cannot express it. Test:
`DoubleReversalTest`, `ReversalCycleTest`.

### Findings summary

| ID | Severity | Status |
|---|---|---|
| A-1 empty/degenerate voucher | **High** — passes all stated barriers | design updated, §11 |
| A-2 posted + cancelled | **High** — silent ledger divergence | design updated, §11 |
| A-3 audit chain serialization | Low — correct, not fast | deferred to P7 |
| A-4 reversal cycles | Medium | design updated, §11 |

A-1 and A-2 both survived my first pass of §4, where I claimed unbalanced
journals could not reach SQLite. That claim was true and incomplete: **balanced**
is not the same as **valid**. The revised design checks both.

---

## 12. Implementation-readiness check (post-approval, 2026-09-01)

**Question:** can P5 be implemented without making an accounting-policy decision
belonging to P6, P7 or P11?

**Answer: yes.** The decisive fact is **P2-2 — balances are computed, never
cached.**

The reference's `applyLedgerEntries` must know each ledger's *nature* to decide a
sign:

```ts
const isDebitNatured = nature === "ASSETS" || nature === "EXPENSES";
const delta = isDebitNatured ? debit.minus(credit) : credit.minus(debit);
await tx.ledger.update({ data: { currentBalance: { increment: delta } } });
```

That single line drags in the chart of accounts, group nature semantics, and
therefore B-1. **Our schema has no `current_balance` column**, so the posting
core never performs this step. It writes `voucher` and `voucher_entry`; balances
are a `SUM()` at read time.

| Concern | Belongs to | Needed by P5? |
|---|---|---|
| Which ledger is debited for a sales invoice | P6/P7, B-1 | **No** — caller supplies entries |
| Ledger nature / sign convention | P6 | **No** — no cached balance to sign |
| COGS at issue vs. period end | B-1 → P8 | **No** |
| GST rounding | B-4 → P9 | **No** |
| Year-end closing voucher vs. derived | B-2 → P11 | **No** — reversal ≠ closing |
| Prior-period restatement | future policy | **No** — extension point only |

P5 validates *structure* (balanced, non-empty, one-sided, immutable, atomic,
entitled). It never decides *content*. A caller hands it entries; the core
refuses invalid ones and never invents them.

**Cleared to implement.**

### Approved decisions incorporated

- **P5-1** `accubook-accounting`, internal package unexported.
- **P5-2** Reversal posts to the **current open fiscal period**; the original is
  never modified, even when its own period is open. Reversal records the reason
  and actor, references the original, and passes through the sealed core. A
  prior-period restatement workflow is left as an extension point, not built.
- **P5-3** Idempotency mandatory for non-interactive callers. For interactive
  callers it is optional but **the lost-response retry is designed for**, not
  waved at — see §13.
- **P5-4** Mechanism and invariants only. Known reference defects are not
  reproduced.

---

## 13. Interactive retry — the lost-response problem

"Optional for interactive" must not mean "duplicates don't matter". The failure
is concrete: the UI posts, the transaction **commits**, the response is lost, the
user clicks Save again.

**Design.** The client generates a `PostingAttemptId` (a UUID) when the *form is
opened*, not when Save is clicked, and sends it with every attempt. Re-clicking
Save reuses it; opening a new form generates a new one.

- Same id, already committed → return the original `PostedVoucher`, post nothing.
- Same id, never committed → post normally.
- New form → new id → a genuine second voucher, which is correct.

This makes the *interactive* path idempotent without demanding the caller invent
a business key, which is what makes mandatory keys burdensome. Mechanically it is
the same `idempotency_key` column and unique index the scheduler path uses; only
who generates it differs.

**Tested by** `InteractiveRetryTest`: commit, discard the result, replay the same
request → exactly one voucher, and the replay returns the original.

---

## 14. Voucher lifecycle — an explicit state machine

Replacing independent `status` + `is_posted` booleans (finding A-2).

```
   DRAFT ──post──► POSTED ──reverse──► REVERSED
     │                                    │
   cancel                            (terminal)
     │
     ▼
  CANCELLED (terminal, never posted)
```

Rules, enforced in L1 and by CHECK constraints:

| From | To | Allowed | Note |
|---|---|---|---|
| DRAFT | POSTED | ✅ | the only way to post |
| DRAFT | CANCELLED | ✅ | never entered the ledger |
| POSTED | REVERSED | ✅ | via a *new* contra voucher |
| POSTED | CANCELLED | ❌ | **the A-2 hole** — use reversal |
| POSTED | DRAFT | ❌ | posting is one-way |
| REVERSED | anything | ❌ | terminal |
| CANCELLED | anything | ❌ | terminal |

`is_posted` becomes derived, not independent:
`CHECK (is_posted = (status IN ('POSTED', 'REVERSED')))`.

A REVERSED voucher **stays in the trial balance** — it and its contra sum to
zero. "Reversed" is a display fact, not an erasure.
