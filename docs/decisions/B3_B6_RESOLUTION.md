# B-3 and B-6 — Resolution Proposals

**Status:** ✅ **APPROVED 2026-09-01** · **Date:** 2026-08-31
**Why now:** both gate the final third of Phase 1 (`MoneyMapper`, migration
runner). Both are evidence-led, so these are confirmations to approve or reject —
not open debates.

> Nothing here is decided. These are recommendations with the evidence attached.

---

# B-3 · Money and data encoding

## Recommendation

**Scaled INTEGER storage · `BigDecimal` in all code · `STRICT` tables ·
per-class scales.**

## Evidence (measured, not assumed)

| Test | Result |
|---|---|
| `typeof` of a column declared `DECIMAL(18,4)` | **`real`** — the declaration is cosmetic |
| `sum(a) = 0.3` after inserting `'0.1'`,`'0.2'` | **FALSE** |
| `SUM()` over a **TEXT** column | returns **`real`** — silently coerced |
| Non-numeric TEXT (`'Rs 500'`, `'1,234.56'`) in `SUM()` | contributes **0**, no error |
| TEXT `ORDER BY` | `-100 \| -5 \| 10 \| 2` — lexicographic, **wrong** |
| INTEGER `ORDER BY` | correct |
| 90,000-line trial balance in INTEGER | `debits = credits`, **difference exactly 0** |
| STRICT INTEGER column ← `100.5` | **rejected** (code 19) |
| `setBigDecimal("123.4567")` → STRICT INTEGER at scale 4 | **rejected** |

**TEXT is disqualified** not by preference but because `SUM()` silently returns a
float — forfeiting SQL aggregation, which is exactly what every report needs —
and because malformed text sums to zero without error.

## The scale table — what actually needs approving

| Class | Scale | Suffix | Reference basis | Max magnitude |
|---|---|---|---|---|
| **Money** | 4 | `_e4` | `Decimal(18,4)` ×139 | ±₹922 lakh crore |
| **Quantity** | 4 | `_e4` | `Decimal(18,4)` | — |
| **Percentage rate** | 2 | `_bp` (basis points) | `Decimal(5,2)` ×20 | 18% → `1800` |
| **Exchange rate** | 8 | `_e8` | `Decimal(18,8)` ×4 | — |
| **UoM conversion** | 6 | `_e6` | `Decimal(18,6)` ×1 | — |
| **Days** | 1 | `_e1` | `Decimal(4,1)` ×5 | — |

**A single global scale does not work** — verified:
- scale 8 everywhere caps money at **₹9,223 crore** (a ₹10,000 crore turnover
  overflows);
- scale 4 everywhere leaves exchange rates with only 4 dp — unusable.

Money at scale 4 reaches ~3× India's GDP, so overflow is not a practical concern.

## Non-negotiable rules that come with it

1. `BigDecimal` in every signature; `long` **only** inside `MoneyMapper`.
2. `longValueExact()` at the write boundary — a scale violation **throws**
   rather than silently truncating.
3. **Never scale back in SQL.** `amt_e4/10000.0` is float division (verified:
   printed `...477.6000` instead of `...477.5807`).
4. **Never multiply money by a rate in SQL.** `money_e4 × fx_e8` = 8.31e19
   **overflows INT64 by ~9×** and silently degrades to REAL. Do it in Java.
5. All tables `STRICT`; `CHECK` constraints on sign where known.
6. Explicit `RoundingMode` on every division; documented residual strategy so
   split parts sum exactly to the whole.

## Consequences
Raw SQL shows `1234500` rather than `123.45` (mitigated by the `_e4` suffix
convention). A scale change is a data migration. In exchange: exact SQL
aggregation, correct ordering and indexing, and a whole class of silent
corruption made unrepresentable.

## What is NOT settled by this
GST rounding policy (**B-4**) and payroll rounding (**B-5**) remain open — they
are about *when to round*, not *how to store*.

---

# B-6 · Database topology

## Recommendation

**One `company.db` per company + one `app.db` for installation state.**

## Evidence

The reference application has **no tenant enforcement at the database or ORM
level**:

| Check | Result |
|---|---|
| Prisma middleware (`$use`) | **0** |
| Client extensions (`$extends`) | **0** |
| Row-level security / policies | **0** |
| Org-scoped routes using `withOrgAuth` | 110 / 111 |

Isolation rests entirely on developers hand-writing `organizationId` filters in
110 route files. There is already a live cross-tenant write primitive (the manual
reconciliation `match` action writes `voucherId` straight from the request body
with no ownership check).

The same codebase demonstrates how convention-based invariants decay: permissions
were per-handler and **55 of 71 mutating routes forgot the check** — a VIEWER
could post journal vouchers and run payroll.

**Per-file separation converts isolation from a discipline problem into a
structural property.** A query cannot reach another company's rows because those
rows are in a file that is not open.

## Layout

```
<app-data>/
├── app.db                    installation state — NOT customer books
├── companies/<uuid>/
│   ├── company.db            THE BOOKS
│   └── attachments/
├── backups/ · logs/ · tmp/
```

| `app.db` | `company.db` |
|---|---|
| company registry (id, path, schema_version) | chart of accounts, ledgers |
| licence, activation, entitlement, machine binding | vouchers + entries |
| monotonic time anchor | parties, documents, inventory |
| statutory rule packs (effective-dated) | **its own** voucher types, units, roles |
| seed templates | company-level audit |
| backup history · app audit | |

### The self-containment rule
> A company file must contain everything needed to interpret its own books.

This is what makes "hand it to your CA" and "restore on a new machine" work. It
also fixes reference defects **D-10** (globally-unique voucher type codes) and
**D-19** (cross-tenant units).

**Tension resolved:** statutory rules live in `app.db`, but posted documents must
be readable from the company file alone. Therefore **posted documents store the
resolved tax figures *and* the rule-pack version used** — history never requires
recomputation.

## Lifecycle

```
create   → create file → apply schema → validate → THEN register in app.db
open     → lock → pragmas → quick_check → migrate-on-open → read pool
switch   → checkpoint WAL → close pools → release lock → open target
close    → wal_checkpoint(TRUNCATE) → close
```

Ordering on create is deliberate: an orphaned file is recoverable, a registry row
pointing at nothing is not.

**No cross-database transactions.** `app.db` and `company.db` are never written
atomically together.

## Migration strategy
Each company file carries its **own** `schema_version` and is migrated **on
open** — never as a global startup sweep, because a company file may be absent at
update time (external drive, archived, restored later). A file at a **newer**
version than the app is **refused**, never opened.

## Costs, stated honestly
- Migrations run per file, N times.
- Cross-company reporting needs `ATTACH` (driver limit **125**, verified) or
  sequential aggregation — and no core flow may depend on it.
- Company switching is explicit and modal.

These are real, and they are worth paying for structural isolation of statutory
records belonging to different legal entities.

## Consequence for ported code
`organizationId` largely disappears from company tables (~40 tables shed a
column). **Ported logic must therefore be re-read, not transliterated** — dropping
a filter that still means something in another sense would be a bug.

---

# Impact on Phase 1

| Deliverable | Unblocked by |
|---|---|
| `MoneyMapper` | **B-3** — needs the scale table |
| Migration runner | **B-6** — needs per-file topology + `schema_version` |

Everything else in P1 (build skeleton, JPMS layout, JavaFX shell, packaging,
signing) proceeds regardless.
