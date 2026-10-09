# Blocking Decisions — Resolution Worksheet

**Purpose:** turn the 8 🔴 BLOCKING decisions into a sequenced, answerable set so
Phase 1 can proceed in parallel while they are resolved.
**Status:** ALL OPEN. Nothing has been decided.

---

## The key insight: they are not all equally urgent

Only **two** decisions gate the next phase after P1. The rest can be resolved
while implementation proceeds.

| Decision | Gates | Needed by |
|---|---|---|
| **B-3** money encoding | P2 | **Before P2 starts** |
| **B-6** database topology | P2 | **Before P2 starts** |
| B-7 plan → module matrix | P4 | Before P4 |
| B-8 dormant read access | P4 | Before P4 |
| B-2 year-end approach | P11 (design in P5/P6) | Before P5 |
| B-1 perpetual vs periodic | P8 (affects P7 masters) | Before P7 |
| B-4 GST rounding | P9 | Before P9 |
| B-5 payroll rounding | P15 (deferred) | Before P15 |

**Phase 1 can start today.** Two caveats worth being precise about:
- The **packaging pipeline, JPMS skeleton, JavaFX shell and code signing** are
  genuinely gated by nothing.
- The **`MoneyMapper`** (needs B-3's scales) and the **migration runner** (needs
  B-6's topology) are P1 deliverables that do depend on the Tier-1 decisions.

So: begin P1 immediately, and settle B-3 + B-6 before that work reaches those two
items. Since both are evidence-led confirmations rather than open debates, this
should not cause a stall.

---

## Tier 1 — Needed before Phase 2 (the only true near-term blockers)

### B-3 · Money encoding
**Question.** Scaled INTEGER minor units, or TEXT decimal, or something else?

**Recommendation: scaled INTEGER + `BigDecimal` in code + `STRICT` tables.**

**This one is effectively settled by evidence, not preference.** Measured:
- A column declared `DECIMAL(18,4)` stores as `real`; `sum(a) = 0.3` → **FALSE**.
- **`SUM()` over a TEXT column silently returns `real`** — TEXT forfeits SQL
  aggregation, which is exactly what every report needs.
- TEXT sorts lexicographically: `-100 | -5 | 10 | 2`. Wrong.
- Non-numeric TEXT (`'Rs 500'`, `'1,234.56'`) silently sums to **0**.
- INTEGER at scale 4 covers ±₹922 lakh crore (~3× India's GDP) and aggregates
  exactly. A 90,000-line trial balance summed to **exactly 0**.

**What you are actually confirming:** the per-class scales —
money 4 · rate 2 (basis points) · FX 8 · UoM conversion 6 · days 1.
A single global scale is **not workable**: scale 8 everywhere caps money at
₹9,223 crore; scale 4 everywhere makes FX unusable.

**If you disagree,** the fallback is TEXT + all arithmetic in Java, forfeiting
SQL aggregation. I do not recommend it.

---

### B-6 · Database topology
**Question.** One `company.db` per company + `app.db`, or one shared database?

**Recommendation: per-company files + `app.db`.** (Already stated as confirmed
in your architectural state — this entry exists to record the sign-off formally.)

**Evidence.** The reference has **zero** tenant enforcement at the ORM or DB
level (0 `$use`, 0 `$extends`, 0 RLS policies). Isolation is hand-written
`organizationId` filters across 110 route files, and there is already a live
cross-tenant write primitive in the reconciliation route. Per-file separation
makes isolation **structural** rather than conventional.

**What you are confirming alongside it:**
- **Self-containment rule** — a company file holds everything needed to
  interpret its own books (its own voucher types, units, roles), so "hand it to
  your CA" and "restore on a new machine" work.
- Posted documents store **resolved** tax figures **plus** the rule-pack version,
  so history never needs recomputation.

---

## Tier 2 — Needed before P4 (module system)

### B-7 · Plan → module matrix
**Question.** Which concrete modules are in BASIC / PROFESSIONAL / ENTERPRISE?

**This is purely commercial — I have no basis to recommend it.** What the
architecture needs is a table of this shape:

| Module | BASIC | PROFESSIONAL | ENTERPRISE |
|---|---|---|---|
| Accounting core (CoA, ledgers, vouchers) | ✅ | ✅ | ✅ |
| Sales & purchase | ? | ? | ? |
| Inventory + costing | ? | ? | ? |
| GST / taxation | ? | ? | ? |
| Banking & reconciliation | ? | ? | ? |
| Manufacturing | ? | ? | ? |
| Multi-company | ? | ? | ? |
| Cloud backup | add-on? | add-on? | ✅? |
| HR / payroll | ? | ? | ? |

**Sub-question that materially affects the design:** are modules sold **à la
carte** (e.g. PROFESSIONAL + backup) in addition to fixed plans? The architecture
supports it; pricing and the admin portal must too.

**Practical note:** accounting core, company management and reporting cannot be
optional — they are the product. The realistic variables are inventory,
manufacturing, payroll, multi-company and backup.

---

### B-8 · Dormant-module read access
**Question.** After downgrade the module's **code is physically absent**. How is
its historical data viewed?

| Option | What the user gets | Cost |
|---|---|---|
| **(a) Generic read-only viewer** *(recommended)* | Records viewable in a table/detail view + export | Modest — engines are the bulk, not renderers |
| (b) Export-only | CSV/PDF only; no in-app view | Lowest |
| (c) Ship all code, gate at runtime | Full original UI, read-only | **Contradicts physical packaging** |

**Recommendation: (a).** It satisfies both requirements — the payroll *engine* is
genuinely absent (so the feature cannot be used), while history stays viewable
and exportable.

**What this decision really settles:** what you promise a downgrading customer.
"View your historical payroll records" (a) versus "export your historical payroll
records" (b) are different commitments.

---

## Tier 3 — Needed before the relevant domain phase

### B-2 · Year-end approach — *before P5*
**Recommendation: Approach A, real closing voucher.**

Needed before P5 because the posting core must know whether a period can be
locked. The derived alternative cannot reproduce a filed statement, permits
unrestricted back-dating, and reads **every voucher since inception** on each
balance-sheet request.

**Sub-decisions that come with it (I-16):** who may reopen a closed year, how
often, and whether prior-period adjustments post to the prior year (on reopen) or
as current-year adjusting entries. Both are defensible; the choice must be
explicit and consistent.

---

### B-1 · Perpetual vs periodic — *before P7*
**Recommendation: perpetual with persisted cost layers.**

Needed before P7 (not P8) because inventory **masters** must be shaped to carry
cost layers even though the engine lands in P8.

**Sub-decisions:** costing methods (recommend weighted-average default + FIFO;
**exclude LIFO** — prohibited under AS 2 / Ind AS 2); cost granularity
(recommend per item **per warehouse**); scrap policy (absorb into good units,
or post to a scrap-loss account — recommend the latter, or make absorption an
explicit configurable choice).

---

### B-4 · GST rounding — *before P9*
**Question.** Reproduce the reference's `Number(x).toFixed(2)` (IEEE-754,
effectively half-even), or `BigDecimal` with explicit `HALF_UP`?

**Recommendation: `BigDecimal` + HALF_UP**, with test vectors deliberately
re-baselined and the divergence documented.

**This is a compliance decision and it is genuinely yours.** The two options
produce **different portal JSON** on exact-half cases. Option (a) matches returns
already filed by existing web-app customers; option (b) is defensible under
Rule 34 CGST. If the desktop product will ever need to reproduce a web-app
customer's historical return byte-for-byte, that argues for (a).

---

### B-5 · Payroll rounding — *before P15 (deferred)*
Not urgent, since P15 is deferred. Recorded so it is not forgotten.

**Recommendation:** `BigDecimal` with each existing `Math.round` transcribed as
an explicit `setScale(0, HALF_UP)` at the same point, **validated against a real
payroll run** before go-live.

---

## Suggested resolution order

1. **B-3 and B-6 now** — both are evidence-led, and both gate P2. Realistically a
   confirmation rather than a debate.
2. **B-7 next** — purely commercial, needs no engineering input, and has the
   longest lead time because it touches pricing.
3. **B-8, B-2, B-1** — during P1/P2/P3, before their respective phases.
4. **B-4** — before P9; involves a CA/tax view.
5. **B-5** — before P15.

## What can proceed regardless

- **All of Phase 1** — depends on none of the eight.
- **Code-signing certificate procurement** — longest lead item on the roadmap;
  gates the P1 exit criteria and nothing else.
