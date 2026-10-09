# Phase 6 Design — Accounting Modules (Chart of Accounts, Ledgers, Trial Balance)

**Status:** DESIGN ONLY — no code written · **Date:** 2026-09-02
**Blocked by:** nothing
**Baseline verified:** 333 passing · 0 failing · 3 UI skipped

> **Exit criterion.** A complete manual bookkeeping cycle:
> company → CoA → ledgers → voucher type → journal/receipt/payment →
> trial balance → reversal → trial balance still correct.

---

## 1. What already exists — P6 is smaller than the roadmap implies

The roadmap says P6 delivers "18 groups, 23 ledgers". Measured against the code,
**P2 already provisions them**:

| Seed | Count in `CompanyProvisioning` |
|---|--:|
| `GROUPS` | **18** |
| `LEDGERS` | **4** |
| `VOUCHER_TYPES` | **8** |

The "23 ledgers" figure comes from the *reference* application, not from a P6
requirement. P2 deliberately seeds only four — `CASH_IN_HAND`,
`OPENING_BALANCE_EQUITY`, `RETAINED_EARNINGS`, `ROUND_OFF` — under decision
**P2-1**: module ledgers arrive with their modules. GST Input/Output belongs to
P9, Purchase Accounts to P7.

**That decision is right and P6 must not undo it.** Seeding a GST ledger in a
build without the taxation module would put an account on the trial balance that
nothing can ever post to.

So P6 is not "write the chart of accounts". It is:

1. turn the provisioning *data* into a governed accounting *model*
2. protect the system accounts later modules resolve by `system_code`
3. define the ledger lifecycle
4. build the trial balance
5. prove the whole cycle end to end

---

## 2. Reference research — four defects in account governance

### R-1 · `isSystem` is written and never enforced — NEW

`provision.ts:231` sets `isSystem: true`. Measured across the ledger routes:

```
src/app/api/organizations/[orgId]/ledgers/route.ts             isSystem checks: 0
src/app/api/organizations/[orgId]/ledgers/[ledgerId]/route.ts  isSystem checks: 0
```

The flag exists as documentation. Nothing consults it.

### R-2 · A system ledger can be hard-deleted — NEW, severe

The DELETE handler is otherwise well designed: it soft-deletes when
`voucherEntry` rows exist and hard-deletes only when clean. But it never checks
`isSystem`.

On a **freshly provisioned company** no ledger has postings yet, so every system
ledger — Retained Earnings, Round Off, Opening Balance Equity — is hard-deletable.
The failure surfaces later, when a module resolves it and finds nothing.

### R-3 · A ledger can be moved to a group of different nature after posting — NEW

The PATCH handler validates only name uniqueness, then passes `validatedData`
straight through:

```ts
const ledger = await prisma.ledger.update({ where: { id: ledgerId }, data: validatedData });
```

`validatedData` includes `groupId`. Moving a ledger from Sundry Debtors (ASSETS)
to Sales Accounts (INCOME) flips `isDebitNatured` in `posting.ts:417`, so
**every future posting moves its balance the opposite way** while historical
`currentBalance` was accumulated under the old sign. The ledger silently becomes
a mixture of two conventions.

`openingBalance` is likewise freely editable — the D-1 family, where editing one
number silently restates prior years.

### R-4 · Name-based resolution creates duplicate control accounts

`post-invoice.ts:207` resolves the round-off account by **display name**:

```ts
const l = await getOrCreateNamedLedger(tx, orgId, "Round Off", "Indirect Expenses");
```

`getOrCreate`, not `get`. Rename "Round Off" to "Rounding" — permitted, since
R-1 means nothing blocks it — and the next invoice **creates a second one**. The
balance splits across two accounts, both real, neither complete.

**Our schema already prevents R-4**: `system_code` is `UNIQUE` and is what the
posting layer resolves on. P6 must make sure nothing regresses to name lookup.

---

## 3. Gap in our own schema

`ledger_group` has `is_system`. **`ledger` does not.**

```sql
CREATE TABLE ledger (
    id, group_id, system_code, name, code,
    opening_fiscal_year_id, opening_balance_e4, opening_balance_type,
    is_active,            -- present
    ...                   -- is_system ABSENT
    UNIQUE (system_code)
);
```

`system_code IS NOT NULL` is a workable proxy — every seeded ledger has one and
user ledgers do not — but it conflates two ideas: *"the posting layer resolves
this by a stable handle"* and *"a user may not delete this"*. A future module may
well want a stable handle on a user-created account.

**Recommendation (P6-1):** add `is_system` to `ledger` in migration 3, defaulting
to `system_code IS NOT NULL` for existing rows. Explicit beats inferred for a
rule that refuses a user's action.

---

## 4. Chart of accounts — the model

### 4.1 Hierarchy (18 groups, unchanged from P2)

```
Assets                                    ASSETS
├── Current Assets
│   ├── Cash & Bank                       ← control: bank module (P10)
│   ├── Sundry Debtors                    ← control: receivables (P7)
│   └── Stock-in-Hand                     ← control: inventory (P7/P8)
└── Fixed Assets

Liabilities                               LIABILITIES
├── Current Liabilities
│   ├── Sundry Creditors                  ← control: payables (P7)
│   └── Duties & Taxes                    ← control: taxation (P9)
└── Loans (Liability)

Income                                    INCOME
├── Sales Accounts
└── Other Income

Expenses                                  EXPENSES
├── Direct Expenses          (affects gross profit)
└── Indirect Expenses

Capital Account                           EQUITY
```

Five natures, already CHECK-constrained in P2. `affects_gross_profit` is set only
on Direct Expenses — that is what separates gross from net profit in P11.

### 4.2 Nature is a property of the group, not the ledger

A ledger's normal balance derives from its group. This is why R-3 is dangerous
and why P6 must forbid a nature-changing move once postings exist (§5.3).

### 4.3 System vs user accounts

| | System | User |
|---|---|---|
| `system_code` | set | null |
| Created by | provisioning or a module | the customer |
| Rename | **allowed** (display only) | allowed |
| Move group | refused if nature would change | refused if nature would change and postings exist |
| Deactivate | refused | allowed |
| Delete | **refused, always** | only when it has never been posted to |

Renaming a system ledger is *safe here* precisely because resolution is by
`system_code`, never by name. That is the structural difference from R-4.

---

## 5. Ledger lifecycle

### 5.1 Create
Name unique per company; group must exist; `system_code` reserved for
provisioning and modules; opening balance requires an opening fiscal year
(already CHECK-constrained by P2).

### 5.2 Deactivate
`is_active = 0`. The ledger keeps its history and continues to appear in the
trial balance when it has a balance — **deactivation hides it from pickers, never
from the books.** Posting to an inactive ledger is refused.

### 5.3 Move between groups

| Case | Rule |
|---|---|
| No postings | allowed |
| Has postings, same nature | allowed |
| Has postings, different nature | **refused** — R-3 |

### 5.4 Delete
A system ledger is never deletable. A user ledger is deletable only while it has
no `voucher_entry` rows; otherwise the answer is deactivation. This keeps the
reference's good instinct (R-2's soft-delete) and closes its hole.

### 5.5 Opening balances
Dated to a fiscal year (P2 already enforces this). Changing one after the year is
closed is refused — P11 owns the closed-year rule; P6 only records the
constraint.

---

## 6. Voucher types

Eight, seeded per company by P2: Payment, Receipt, Contra, Journal, Sales,
Purchase, Debit Note, Credit Note.

**Kernel vs feature ownership** — the distinction that matters from P7 onward:

| Voucher type | Who may post it in P6 | Later owner |
|---|---|---|
| Journal, Payment, Receipt, Contra | **P6 — manual bookkeeping** | stays kernel |
| Sales, Purchase | nobody yet | P7 documents |
| Debit Note, Credit Note | nobody yet | P7, sign-aware (fixes D-23) |

P6 delivers manual posting for the first four. The rest exist as types so
numbering scopes are stable, but P6 provides no use case that posts them — a
deliberate refusal to pre-empt P7.

Numbering is already handled: `PostingService` allocates from `number_counter`
scoped by `voucher_type_id + fiscal_year_id`, inside the posting transaction.
P6 adds no numbering logic.

---

## 7. Trial balance

### 7.1 The query

```sql
SELECT l.id, l.name, g.nature,
       COALESCE(SUM(e.debit_amount_e4), 0)  AS debit_e4,
       COALESCE(SUM(e.credit_amount_e4), 0) AS credit_e4
FROM ledger l
JOIN ledger_group g ON g.id = l.group_id
LEFT JOIN voucher_entry e ON e.ledger_id = l.id
LEFT JOIN voucher v ON v.id = e.voucher_id
     AND v.is_posted = 1
     AND v.fiscal_year_id = ?
GROUP BY l.id, l.name, g.nature
ORDER BY l.name
```

SQL aggregation, not in-memory. `LEFT JOIN` so a zero-balance ledger still
appears — an account that exists but has not moved is information.

### 7.2 The P5 lifecycle trap — and the test that must exist

P5 changed `status` and removed `APPROVED`. `CompanyService` had been filtering
`is_posted = 1 AND status = 'APPROVED'`, which would have **silently returned
zero for every balance**. It was caught by an existing P2 test, and the filter is
now `is_posted = 1` alone.

`is_posted` is now derived by CHECK from `status`, so it is the single correct
predicate. **A REVERSED voucher is still posted and still counts** — it and its
contra sum to zero, which is the arithmetic that keeps a reversal honest.

**P6-T1 (required):** a test asserting the trial balance includes REVERSED
vouchers and still balances. This is the regression guard against the class of
bug P5 introduced and P2's tests caught by luck of coverage.

### 7.3 Presentation
Net each ledger to one side by nature; debit-natured (ASSETS, EXPENSES) net
`debit - credit`, credit-natured the reverse. Totals must be equal — and since
every voucher balances and the schema enforces it, any inequality is a defect,
not a rounding artefact.

---

## 8. P5 boundary — non-negotiable

P6 consumes `PostingService.post(...)`. It does not reference `VoucherWriter`,
which it *cannot*: the package is unexported.

**Architecture test P6-T2:** no module other than `accubook.accounting` requires
`accubook.accounting.posting.internal`, and no P6 class issues SQL against
`voucher` or `voucher_entry`.

P6 owns `ledger`, `ledger_group`, `voucher_type` — masters. It never writes the
journal directly.

---

## 9. B-1 exposure — traced, as instructed

Which P6 decisions become expensive if inventory later proves perpetual?

| P6 decision | B-1 sensitive? | Why |
|---|---|---|
| 18-group hierarchy | **No** | Stock-in-Hand exists under both models |
| Nature per group | **No** | Stock is an asset either way |
| Seeding only 4 ledgers | **No** — protective | COGS is *not* seeded; perpetual creates it in P8, periodic never needs it |
| `affects_gross_profit` on Direct Expenses | **Low** | Periodic puts purchases here; perpetual puts COGS here. Same flag, different account |
| Ledger lifecycle rules | **No** | Governance, not valuation |
| Trial balance query | **No** | Sums the journal whatever produced it |

**Conclusion: B-1 does not materially change the P6 kernel.** The reason is
structural — P2-1 keeps module ledgers out of provisioning, so the accounts whose
existence depends on B-1 (COGS, Purchase Accounts, Inventory Adjustment) are not
P6's to create.

**Recommendation: proceed with P6; do not elevate B-1.** Settle it during or
just after P6, when the chart of accounts is concrete enough to reason about.

The one thing P6 must *avoid* is seeding a COGS ledger "for completeness". That
would silently commit to perpetual.

---

## 10. Adversarial review of this design

### A-5 · Renaming a system ledger — SAFE HERE, but assert it
Safe because resolution is by `system_code`. **A test must prove it**, or a
future refactor to name lookup would reintroduce R-4 silently.
Test: `SystemLedgerRenameDoesNotBreakResolution`.

### A-6 · Deactivating a ledger that still has a balance — DESIGN CHANGE
§5.2 permits deactivating any user ledger. If it holds a non-zero balance, it
vanishes from pickers while still affecting the trial balance — confusing, but
**hiding it would be worse**: the books would stop balancing.
**Resolution:** allow it, keep it in the trial balance, and mark it *inactive* in
the report so the reader knows why an unfamiliar account appears.

### A-7 · Cross-company ledger ids — ALREADY CLOSED
B-6 gives each company its own `company.db`, so a ledger id from another company
cannot resolve. The reference needed `LedgerScopeError` because it is one shared
database; we get it from topology. **Test anyway** — a regression here would be
invisible.

### A-8 · The empty trial balance — NEW
A freshly provisioned company has 4 ledgers and no postings. The trial balance is
four zero rows, totals 0 = 0. That is *correct* and must not be mistaken for the
A-1 degenerate-voucher case: a zero **report** is legitimate, a zero **voucher**
is not. Test both so the distinction is recorded.

### A-9 · Group deletion — GAP IN THIS DESIGN
§5 covers ledger lifecycle and says nothing about groups. Deleting a group with
child ledgers or child groups would orphan them; `ledger.group_id` is
`NOT NULL REFERENCES`, so SQLite would refuse — but only with foreign keys ON,
and P2 measured that they default OFF per connection.
**Resolution:** system groups are never deletable; a user group is deletable only
when it has no children of either kind. Do not rely on the FK alone.

### A-10 · Reversal in a different fiscal year — INTERACTION
P5-2 posts a reversal into the current open period. If the original is in FY1 and
the reversal in FY2, **FY1's trial balance no longer nets to zero for that
voucher** — correctly, since the correction belongs to FY2.
This is right, and it is exactly why P11 needs the prior-period restatement
conversation. **P6 must test it explicitly** so the behaviour is deliberate and
documented rather than discovered.

### Findings summary

| ID | Severity | Disposition |
|---|---|---|
| A-5 rename vs resolution | Medium | safe; assert with a test |
| A-6 deactivate with balance | Medium | design updated — stays in trial balance |
| A-7 cross-company ids | Low | closed by B-6; test anyway |
| A-8 empty trial balance | Low | legitimate; test both cases |
| A-9 group deletion | **High** | design gap, rules added |
| A-10 cross-year reversal | Medium | correct; must be tested and documented |

---

## 11. Test strategy

**Unit** — nature derivation, lifecycle rules, group-move validation, trial
balance netting.

**Integration** — provisioning idempotency; full cycle (§ exit criterion);
reversal then re-check.

**Architecture** — P6-T2 (no direct journal access); **plus the two carried P4/P5
items**: every non-kernel table has exactly one owner, and the module-smuggling
test, both of which become writable once P6 exists.

**Adversarial** — delete a system ledger; delete a posted-to user ledger; move a
ledger across natures with postings; rename a system ledger then post; post to an
inactive ledger; duplicate `system_code`; cross-company ledger id; delete a group
with children; cross-year reversal.

**Regression** — all 333 must stay green.

---

## 12. Decisions needed

**P6-1 · Add `is_system` to `ledger`?**
Recommend **yes**, migration 3, defaulting to `system_code IS NOT NULL`.
Explicit beats inferred for a rule that refuses a user action.

**P6-2 · May a user rename a system ledger?**
Recommend **yes**. Resolution is by `system_code`, and Indian businesses
legitimately call Round Off "Rounding A/c". The reference could not allow this
safely; we can.

**P6-3 · Trial balance default scope — fiscal year or all time?**
Recommend **fiscal year**, matching Indian practice and P11's closing model.

**P6-4 · Does P6 ship a UI, or use cases only?**
Recommend **use cases + a minimal read-only trial balance screen**. Enough to
demonstrate the exit criterion without pre-empting P7's document screens.

**No B-1, B-2, B-4 or B-5 decision is required.**

---

## 13. Explicitly NOT in P6

Inventory · items · parties · invoices · bills · GST · TDS · banking · payroll ·
manufacturing · year-end closing · P&L · balance sheet · cash flow · Sales,
Purchase, Debit Note and Credit Note *posting* (types exist; P7 owns the
documents).
