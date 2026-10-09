# Accounting Core — Verified From Existing AccuBook

Status: VERIFIED FROM EXISTING ACCUBOOK (read directly, first-hand, this session)
Source of truth: `/home/achyut-pal/Desktop/accubook` (read-only reference; never modified)

This document records accounting semantics read directly out of the reference
codebase, not inferred. Anything uncertain is marked explicitly.

---

## 1. The single double-entry choke point

File: `src/backend/utils/posting.ts` → `applyLedgerEntries(tx, organizationId, entries)`

Every posting path in the application funnels through this one function:
invoices, bills, payments, receipts, payroll, manufacturing, the Tally import,
and hand-written vouchers. It enforces two invariants:

1. **Debits equal credits.** Sum of `debitAmount` vs sum of `creditAmount`;
   mismatch throws `UnbalancedEntriesError`. The code comment documents this as
   a real production incident: `buildPayrollJournal` matched payslip deduction
   components by *display name*, so a component named "PF" instead of
   "PF (Employee)" was silently dropped from the credit side. A voucher with
   totalDebit 36,800 / totalCredit 35,000 posted without complaint and the
   trial balance was out from that month onward.

2. **Ledger tenant scoping.** Ledgers are re-fetched filtered by
   `organizationId`, not merely by id. Unresolved ids throw `LedgerScopeError`
   rather than being skipped — an earlier `continue` produced vouchers that
   balanced on paper while only some ledger effects landed.

**Port implication:** this design is excellent and must be preserved. In the
Java port the equivalent must be the *only* way to touch ledger balances, and
should be enforced structurally (package-private ledger mutation, single
`PostingService`), not by convention.

## 2. Ledger balance direction (natural-direction storage)

`Ledger.currentBalance` is a **denormalized running balance**, stored in the
*natural* direction of the owning group's `nature`:

- `ASSETS` / `EXPENSES` → debit-natured: `delta = debit - credit`
- `LIABILITIES` / `INCOME` / `EQUITY` → credit-natured: `delta = credit - debit`

So balances display positive in the direction users expect.

**Port implication / RISK:** a denormalized balance can drift from the journal.
The reference app has no visible reconciliation job that re-derives
`currentBalance` from `voucher_entries`. For a desktop product I recommend
treating the journal as the sole source of truth and either (a) computing
balances by aggregation, or (b) keeping the cache but adding a verifiable
"rebuild balances" integrity routine. Recorded in OPEN_DECISIONS.

## 3. Money representation

File: `src/backend/utils/money.ts`

All money math goes through `Prisma.Decimal` helpers (`D`, `sum`, `mul`, `cmp`).
The module explicitly forbids JS float arithmetic. `toNumber` is documented as
display-only.

Schema precision census (from `prisma/schema.prisma`):

| Precision | Count | Use |
|---|---|---|
| `Decimal(18, 4)` | 139 | money amounts |
| `Decimal(5, 2)`  | 20  | percentage rates (tax, TDS) |
| `Decimal(4, 1)`  | 5   | (small rates/quantities) |
| `Decimal(18, 8)` | 4   | exchange rates |
| `Decimal(18, 6)` | 1   | (unit cost) |

**Port implication — this is the single largest portability issue.** SQLite has
no decimal type; its `NUMERIC` affinity silently degrades to IEEE-754 double.
Storing money as a float in an accounting product is unacceptable. Java must use
`BigDecimal` end to end. Storage strategy is a required decision (see
DATABASE_ARCHITECTURE / OPEN_DECISIONS): scaled INTEGER minor units vs TEXT
canonical decimal. Whichever is chosen must be applied uniformly and enforced by
the persistence layer, never by individual call sites.

## 4. Settlement status derivation

`deriveSettlementStatus()` is deliberately **total** — every input maps to
exactly one output, so the result never depends on the previous status. The
comment records the bug this fixed: reversing a payment left a document reading
PAID with the full amount outstanding (a bounced cheque silently closed an
invoice while the ledger side reversed correctly).

Lifecycle-locked statuses are never moved by payment activity:
- Invoice: `DRAFT`, `CANCELLED`
- Bill: `DRAFT`, `PENDING_APPROVAL`, `CANCELLED`

Order of evaluation:
1. `totalAmount <= 0` → the type's unpaid resting status
2. `amountDue <= 0` → `PAID`
3. `amountPaid > 0` → `PARTIAL`
4. `dueDate < now` → `OVERDUE`
5. else → unpaid resting status (invoice `SENT`, bill `APPROVED`)

**TDS subtlety (important):** `recomputeBillStatus` nets TDS out of the
obligation — `amountDue = totalAmount − tdsWithheld − amountPaid`, and the
status is derived against `totalAmount − tdsWithheld`. The comment explains that
without this, AP drifts from the vendor ledger by exactly the TDS withheld and
the bill never closes. This must be reproduced exactly in the port and pinned by
a test.

## 5. Document/voucher numbering — CONCURRENCY HOTSPOT

`nextNumber(tx, organizationId, scope)` uses a `NumberCounter` upsert with
`{ increment: 1 }`, relying on **Postgres row-level serialization**. Voucher
scope is `VOUCHER:<voucherTypeId>:<fiscalYearId>`, so each (type, FY) has its
own counter. Format: `<prefix>-NNNNNN` (zero-padded to 6).

**Port implication:** the Postgres concurrency guarantee does not transfer. In
SQLite with a single writer this is simpler, not harder — but it must be done
inside the same write transaction as the row insert, and the port must keep the
`@@unique(organizationId, scope)` counter row plus a unique constraint on the
resulting document number as a backstop.

## 6. Ledger auto-creation — Postgres-only implementation

`upsertLedgerByName` is raw SQL: `INSERT ... ON CONFLICT (organizationId, name)
DO UPDATE SET name = EXCLUDED.name RETURNING id`. The extensive comment explains
why: `findFirst`-then-`create` is a TOCTOU race under READ COMMITTED, a P2002
constraint violation *poisons the whole Postgres transaction* (so the entire
invoice posting aborts), and catch-and-retry cannot fix it. Prisma's `upsert`
was insufficient because the driver adapter resolves it as select-then-insert.
`tests/integration/ledger-concurrency.test.ts` pins this.

**Port implication:** this specific hazard largely disappears under SQLite's
single-writer model, but the *semantics* (find-or-create a ledger by name within
a company, atomically, inside the posting transaction) must be preserved.
SQLite supports `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` natively.

## 7. Ledgers resolved by NAME — fragility to fix in the port

`provision.ts` documents a load-bearing contract: the posting layer resolves
groups and ledgers **by display name**. `POSTING_CRITICAL_GROUPS`:
`Cash & Bank`, `Sundry Debtors`, `Sundry Creditors`, `Duties & Taxes`.
Renaming one breaks posting at runtime, not compile time.

**Recommendation for the port:** give system groups/ledgers a stable immutable
`systemCode` and resolve on that, leaving the display name freely renameable by
the user (which accountants expect to be able to do). This is a genuine
improvement over the reference behavior — flagged as a deliberate deviation.

## 8. New-company bootstrap (what provisioning creates)

From `src/backend/services/organization/provision.ts` — idempotent, upsert-based:

- **18 default ledger groups** in a hierarchy (Assets → Current Assets →
  Cash & Bank / Sundry Debtors / Stock-in-Hand; Liabilities → Current
  Liabilities → Sundry Creditors / Duties & Taxes; Income → Sales Accounts /
  Other Income; Expenses → Direct Expenses / Indirect Expenses; Capital Account).
- **`affectsGrossProfit`** is set only on **Direct Expenses**. The comment records
  the bug: without it the P&L put purchases below the gross-profit line, so
  buying at 15,000 and selling at 70,000 showed a 100% gross margin instead of
  78.6%. Net profit was correct either way — the gross margin was wrong.
- **23 default ledgers**, including `Opening Balance Equity` (contra for
  pre-books opening balances, so the trial balance is not out by its own
  amount), `Round Off`, GST Input/Output, TDS/TCS/PF/ESI/PT Payable,
  Salaries Payable, Stock-in-Hand, Work in Progress.
- **HR masters per organization** (departments, designations, leave types),
  including statutory Indian leave (maternity 182 days per the Maternity
  Benefit Act) and a Loss of Pay type resolved **by code** by payroll's LOP
  calculation.

## 9. Fiscal year

`fiscalYearBounds(on, startMonth = 4)` — Indian FY, 1 April → 31 March, named
`FY YYYY-YY`. Dates are constructed in **UTC** deliberately: using local-time
constructors on a UTC+5:30 deployment stored 31 March 18:30Z and pushed every
1 April voucher into the previous year.

`FiscalYear` has `isClosed` / `closedAt` / `closedBy`. `getFiscalYearForDate`
throws if no FY covers the voucher date.

**Port implication:** desktop runs in the user's local timezone with no server
zone, which removes the original hazard but introduces a different one. Fiscal
dates should be handled as `LocalDate` (no instant/zone) throughout. Whether
year-end close actually posts closing entries is still being verified.

## 10. Schema-level observations relevant to the port

- **No Prisma enums anywhere.** Status/nature are plain `String` columns with
  comments; there is no database-level constraint. The port should add SQLite
  `CHECK` constraints and Java enums — a strict improvement.
- **`VoucherType.code` is globally `@unique`**, not org-scoped. In a
  multi-company desktop database this is a real problem and must be re-scoped
  per company.
- **`OrganizationUser.branchIds String[]`** and `Party.tags String[]` are
  Postgres arrays — unsupported in SQLite, need junction tables or JSON.
- **24 `Json` columns**, including `Organization.payrollSettings`,
  `Voucher.attachments`, `Voucher.metadata`.
- IDs are `cuid()` generated **client-side**, so the port controls ID generation
  (no DB sequences to emulate).
- Voucher uniqueness: `@@unique([organizationId, voucherTypeId, voucherNumber, fiscalYearId])`.

## 11. Postgres-specific surface — measured, and it is narrow

Scanned across `src` (excluding generated Prisma runtime):

- **Raw SQL in app code: only 3 sites** — `posting.ts` (ON CONFLICT upsert),
  `health/route.ts` (`SELECT 1`, `_prisma_migrations` probe),
  `inventory/batches/route.ts` (`$executeRaw`).
- **46 `mode: "insensitive"`** query usages → Postgres `ILIKE`. SQLite `LIKE` is
  case-insensitive for ASCII only; non-ASCII needs `COLLATE NOCASE` on the
  column or normalized lowercase search columns. Matters for party/item search
  in Indian-language data.
- 2 array columns, 24 Json columns, 169 Decimal columns.
- Serverless-specific concerns (`withDbRetry` for Neon cold starts, pool
  sizing, 20s interactive transaction timeout) are **entirely irrelevant** to
  desktop and should be dropped, not ported.

**Conclusion:** the domain logic is far more portable than the size of the
codebase suggests. The hard problems are Decimal storage, case-insensitive
search, and array/JSON columns — not the accounting logic itself.
