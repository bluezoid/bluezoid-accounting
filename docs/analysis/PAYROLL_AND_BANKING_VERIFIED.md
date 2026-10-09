# Payroll & Banking — Verified Analysis

Status: VERIFIED FROM EXISTING ACCUBOOK
Source: `/home/achyut-pal/Desktop/accubook` (read-only reference)

---

## 1. THE PAYROLL FLOAT PROBLEM — highest-priority porting hazard

`src/backend/utils/payroll-calculations.util.ts` (386 lines) is
**entirely `number`-based with `Math.round` — no `Decimal` anywhere.**

Every other money path in the application routes through `Prisma.Decimal` and
explicitly forbids float math. Payroll is the exception, and it is not
incidental: the whole statutory engine (PF, ESI, PT, TDS, LOP) is built on
`number` + `Math.round` to whole rupees.

**Why this is the single most dangerous thing to port naively:**

Rewriting this in `BigDecimal` will produce **different rupee figures** than the
reference application in edge cases, because `Math.round` (half-up on a binary
float) and `BigDecimal.setScale(0, HALF_UP)` disagree wherever the float
representation sits just below a `.5` boundary. Payroll figures are filed with
statutory authorities. A silent one-rupee divergence in PF or TDS is a
compliance problem, not a rounding curiosity.

Worse, `post-month/route.ts:122-143` **deliberately re-derives** employer
contributions using the same `number` math, with a comment stating this is to
keep JV totals "byte-identical" to the payslip. So the float behavior is
load-bearing for the double-entry balance assertion.

**Port decision — DECISION REQUIRED.** Three options:

- **(a) Reproduce float semantics exactly** — port using `double` + an
  equivalent of `Math.round` for the statutory engine only. Bit-compatible with
  the reference and with already-filed returns; but knowingly carries float
  math into a new accounting product.
- **(b) Move to `BigDecimal` with explicit `HALF_UP` at each documented step**
  — correct going forward, but produces different numbers than history.
  Requires deciding what happens to already-computed payslips on migration.
- **(c) `BigDecimal` throughout, and treat the statutory rounding points as
  explicit specified rules** (round PF to whole rupees at this step, ESI at
  this step, etc.), validated against real payroll examples from the customer.

I recommend **(c)**, because the rounding points are already explicit in the
reference code (`Math.round` appears at each statutory boundary, not scattered),
so they can be transcribed as deliberate `setScale(0, HALF_UP)` operations. This
must be validated against a real payroll run before going live. Recorded in
OPEN_DECISIONS.

### Statutory constants (FY 2024-25, India) — these WILL need annual updates

```
PF:  wageLimit 15000, employee 12%, employer 12%,
     EPS 8.33%, EPF 3.67%, admin 0.5%, EDLI 0.5%
ESI: wageLimit 21000, employee 0.75%, employer 3.25%
PT:  Maharashtra slabs — 0/7500→0, 7500/10000→175, 10000+→200 (300 in Feb)
Standard deduction: new regime 75000, old regime 50000
```

**Notes that matter:**
- `adminCharges`, `edliCharges`, `employerEPSContribution`, `employerEPFContribution`
  are **declared but never used** — the EPS/EPF split is not computed and
  admin/EDLI are never charged. Real payroll needs these.
- PT slabs are **Maharashtra only**, hardcoded. A national product needs
  per-state PT. **DECISION REQUIRED** on state coverage.
- ESI cliff is `>` not `>=`, so gross of exactly ₹21,000 **is** ESI-applicable.
  Pinned by a test ("applicable at exactly ₹21k"). Preserve deliberately.

### TDS engine defects
- `totalTax` (`:228`) is computed and **never used** — dead code; cess is
  recomputed inside `finalTax`.
- **Surcharge is tested against gross `annualIncome`, not `taxableIncome`**
  (`:232-240`). This is likely wrong under the Act and needs a tax-professional
  ruling. Marked **DECISION REQUIRED** — do not silently "fix" a tax rule.
- `monthlyTds = round(finalTax / 12)` is a second independent rounding, so
  `monthlyTds * 12 != annualTax` in general.
- 87A rebates are applied **before** the `max(0, ...)` clamp.

### Payroll settings are write-only
`Organization.payrollSettings` (JSON) stores `epfWageCeiling`,
`componentsEnabled.{pf,esi,professionalTax,tds,gratuity,lwf}`,
`defaultTaxRegime`, `allowVpf` — and **none of them are read by any
calculation.** `isPfApplicable`/`isEsiApplicable` default `true` and no caller
ever passes them; `taxRegime` is hardcoded `"new"` on the bulk paths. Gratuity
and LWF have no calculation code at all.

**Port implication:** the settings UI exists but is disconnected. Wiring it up is
new feature work, not a port.

## 2. What to port faithfully — `buildPayrollJournal`

`src/backend/services/payroll/post-month.ts` is genuinely good and should be
preserved closely:

- **Pure function, no DB access** — so it throws before any transaction opens.
- **The journal:**
  ```
  Dr Salaries & Wages            (gross − LOP)
  Dr Employer PF Contribution
  Dr Employer ESI Contribution
    Cr Salaries Payable          (net)
    Cr PF Payable                (employee + employer combined)
    Cr ESI Payable               (employee + employer combined)
    Cr Professional Tax Payable
    Cr TDS Payable
    Cr Employee Deductions Payable  (the sweep — see below)
  ```
  LOP is netted out of wage expense; PF/ESI payables carry combined
  employee+employer amounts, matching how the challan is actually remitted.
- **Alias-tolerant component classification** — `"pf"`, `"epf"`,
  `"provident fund"`, `"pf employee"` all map to the same component. This exists
  because a real production bug (documented in `posting.ts`) had a deduction
  named `"PF"` instead of `"PF (Employee)"` silently dropped from the credit
  side, posting an unbalanced voucher that broke the trial balance.
- **The unclassified sweep** — anything unrecognised goes to
  `Employee Deductions Payable` rather than being dropped. Deliberate: the entry
  balances whatever an operator calls a deduction.
- **The balance assertion** (`:237-239`) — `Decimal.equals`, explicitly
  "no epsilon, and deliberately no rounding of either side to force agreement."

Test coverage is strong (316 lines) and encodes exact expected figures
(`PF Payable = 7200`, `ESI Payable = 533 // 100 + 433`). **These tests are the
porting oracle** — reimplement them in JUnit first, then make them pass.

## 3. The conditional-claim concurrency pattern — port this

Both `post-month` and `pay-month` use a compare-and-swap:

```ts
const claimed = await tx.payslip.updateMany({
  where: { id: { in: eligibleIds }, voucherId: null },   // re-assert precondition
  data: { voucherId: voucher.id, status: "PROCESSED" },
});
if (claimed.count !== eligible.length) throw new PayrollAlreadyPostedError(...);
```

The eligibility read happens *before* the transaction; `Payslip.voucherId` cannot
be unique (many payslips share one voucher), so re-asserting `voucherId IS NULL`
in the WHERE turns the write into a CAS and the row count detects the loser.
Throwing unwinds voucher + entries + ledger movements.

Same pattern guards the work-order completion and the stock decrements. **This
idiom should become a documented standard in the Java port** — it is how this
codebase achieves correctness without explicit locking, and it maps cleanly onto
JDBC `executeUpdate()` row counts.

Integration tests exist for both (`payroll-double-post.test.ts`,
`payroll-double-payment.test.ts`) and assert exactly-once semantics on the
voucher, the ledger, and the bank balance.

## 4. The payroll sequence

```
1. /payroll/calculate                          pure compute, no persistence
2. /payroll/calculate?action=generate-payslips persists DRAFT payslips
3. /payroll/post-month   Dr Salaries & Wages / Cr Salaries Payable + statutory
                         payslips → PROCESSED
4. /payroll/pay-month    Dr Salaries Payable  / Cr Bank|Cash
                         payslips → PAID
```
`pay-month` refuses partial payment of a period (409) — "refusing is safer than
silently double-paying." Note `Payslip.voucherId` always points at the **posting**
JV, never the disbursement voucher.

## 5. Attendance → payroll: a live status-vocabulary bug

Three vocabularies disagree:

| Source | Values |
|---|---|
| Schema comment | `PRESENT, ABSENT, HALF_DAY, LEAVE, HOLIDAY, WEEKEND` |
| Zod (write path) | `PRESENT, ABSENT, HALF_DAY, LATE, ON_LEAVE, HOLIDAY, WEEK_OFF` |
| Payroll (read path) | present: `PRESENT, HALF_DAY, LATE`; excluded: `HOLIDAY, WEEKEND` |

The API can only ever write **`WEEK_OFF`**, but payroll filters for **`WEEKEND`**
— which therefore never matches. **Weekly offs are counted as working days**,
inflating `workingDays` and shrinking the per-day LOP rate. Because the column is
an unconstrained `String`, nothing catches it.

This is a concrete illustration of why the port must use enums + CHECK
constraints. **Port decision: DO NOT REPRODUCE** — one vocabulary, enforced.

Also: `presentDays` is stored on the payslip but **never used in any
calculation** — only `workingDays` and `lopDays` reach `calculateLOP`.
`HALF_DAY` counts as a full present day and contributes nothing to LOP.

## 6. Leave subsystem is decoupled

`LeaveType.annualQuota`, `carryForward`, `maxCarryForward`, `encashable` are
stored but **no code reads them**. There is no leave-balance engine, no quota
enforcement, no encashment. An `APPROVED` leave does **not** create an
`Attendance` row and does **not** affect LOP.

`Leave` has **zero indexes** — not even on `employeeId`.

**Port implication:** leave management is essentially new feature work.

## 7. Expense claims — no accounting integration at all

Reimbursing a claim creates **no voucher**, moves **no ledger**, and touches
**no bank balance**. `ExpenseClaim` has no `voucherId`. There is no status state
machine (`REIMBURSE` can be applied to a `PENDING` or even `REJECTED` claim),
and `action` is read from the raw body with no validation — an unrecognised
action produces a silent 200 no-op.

Claim numbering uses the unsafe `findFirst + 1` pattern against a **globally
unique** `claimNumber` (not org-scoped) — so two organizations independently
reaching `EXP-000042` collide.

**Port decision:** expense claims need a real design — GL posting, a state
machine, and org-scoped numbering.

## 8. Banking — two competing reconciliation implementations

**Both are live and they disagree.** A port must pick one.

**(a) `services/banking/reconcile.ts`** — the good one. Matches unreconciled bank
transactions against `Payment`/`Receipt` (status COMPLETED) in a ±10-day window,
with a scoring model:
- amount is a hard gate, tolerance **±0.05** (5 paise clearing tolerance);
- date proximity: same day +5, 1 day +3, ≤3 days +1, else reject;
- reference substring match +3;
- party token overlap +1 each, capped +3, with a stop-list of corporate
  suffixes (`ltd`, `pvt`) and payment rails (`neft`, `rtgs`, `imps`, `upi`);
- `MIN_MATCH_SCORE = 5`; ambiguity guard rejects when best − second < 1.

Reconciliation state is **three columns on the transaction row**
(`isReconciled`, `reconciledAt`, `matchedVoucherId`). The `BankReconciliation`
model is **not touched by this service at all**.

**(b) `bank-reconciliation/route.ts` `auto-match`** — the legacy one. Matches
against `VoucherEntry` rows on the bank ledger, tolerance 0.01, ≤3 days, takes
the **first** `.find()` with no scoring and no ambiguity check. Its `reconcile`
action is the only place `BankReconciliation` rows are created, and it computes
book balance with **`Number()` float math** including **DRAFT** vouchers.

**Port decision:** implement one reconciler, based on (a)'s scoring model, and
give `BankReconciliation` a real role as the period-close record.

### Banking defects
- **`BankTransaction.matchedVoucherId` has no FK, no relation, and no unique
  constraint** — a dangling string that can reference a deleted or foreign-tenant
  voucher, and two transactions can claim the same voucher.
- The already-matched exclusion in `reconcile.ts:214-221` adds **voucherIds** to
  sets that are checked against **payment/receipt ids** — different id spaces, so
  the exclusion is effectively a **no-op**. Re-running reconcile can re-match an
  already-matched payment.
- Manual `match` writes `voucherId` straight from the request body with **no org
  ownership check** — a cross-tenant write primitive.
- **Bank balance is stored twice**: `BankAccount.currentBalance` *and* the bank
  ledger's `currentBalance`, updated independently. They can drift.

### Statement import
- **CSV only**, despite a comment claiming Excel — the route calls
  `file.text()`, so a real `.xlsx` yields binary garbage. 10 MB cap.
- Five layouts (HDFC/ICICI/SBI/AXIS/GENERIC) with a column-alias table and
  header auto-discovery (banks pad statements with summary rows).
- Handles Indian lakh separators (`1,23,456.78`).
- **Bug:** the amount cleaner uses `/[,\s₹INR]/gi` — a **character class**, so it
  strips any `I`, `N`, or `R` character anywhere in the string, not the token
  "INR".
- Dedup is a 5-part key (date|debit|credit|ref|normalized-description) checked in
  application code with **no backing unique index** — concurrent imports of the
  same file both insert.

**Port decision:** support CSV properly plus at least one bank-neutral format;
back the dedup key with a real unique index; parse amounts with a correct
sanitizer.

## 9. Postgres-specific items in these domains

- Migration 17 is heavily Postgres-specific: `CREATE TEMP TABLE ... ON COMMIT DROP`,
  `gen_random_uuid()::text`, `UPDATE ... FROM ... JOIN`, a comma-join workaround,
  `ALTER COLUMN ... SET NOT NULL`, and post-hoc `ADD CONSTRAINT ... FOREIGN KEY`.
  SQLite cannot alter nullability or add FKs in place — both need table rebuilds.
  **This confirms migrations must be hand-authored for SQLite, not translated.**
- `onDelete: Cascade` on Employee→(attendance, leaves, payslips, claims) and
  BankAccount→(transactions, reconciliations) silently no-ops in SQLite unless
  `PRAGMA foreign_keys = ON` is set on every connection.
- `{ increment: delta }` compiles to atomic `SET col = col + ?`. Must stay
  SQL-side arithmetic in Java, never read-modify-write.
