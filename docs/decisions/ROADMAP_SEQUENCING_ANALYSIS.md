# Roadmap Sequencing Analysis

**Status:** ANALYSIS — input to the final roadmap
**Date:** 2026-08-31
**Purpose:** critique the proposed 14-phase sequence against the dependency facts
established in Phase 0, and recommend adjustments.

---

## 1. What the proposed sequence gets right

| Point | Assessment |
|---|---|
| Walking skeleton + signed packaging first | **Correct and important.** Retires the two risks that most often derail this class of project (cross-platform packaging, migration safety). Independent of every blocking decision. |
| Database/storage before everything domain-related | **Correct.** Money encoding and topology are foundational; changing them later is a rewrite. |
| **Splitting module/build (P4) from licensing (P3)** | **Better than my draft**, which merged them. Build packaging is a compile-time concern; entitlement is a runtime concern. Merged, Phase 3 becomes too large to land safely. Adopted. |
| Posting engine as its own phase (P5) before modules (P6) | **Correct and it matters.** The sealed posting core is the single most important correctness asset; it deserves to land and stabilise alone. |
| Year-end + financial reporting together (P9) | **Better than my draft.** I had year-end inside accounting core; pairing it with reporting is right because the closing voucher and the statements are the same design conversation. Adopted. |
| Backup before admin portal | Correct — backup is customer-facing value; the portal is internal tooling. |
| Security/concurrency/recovery testing as a named phase | Correct as a *gate*. See §2.3 for a caveat. |

**Two of your changes are improvements on my draft and I have adopted both.**

---

## 2. Three sequencing problems

### 2.1 🔴 Entitlement enforcement must land with the posting core, not after it

**The problem.** In the proposed order, Phase 4 (module/build architecture)
completes before Phase 5 (posting core) and Phase 6 (accounting modules). But
ADR-002 specifies **three enforcement layers**, and one of them is *inside* the
write path:

> 2. **Persistence guard** — the write path refuses inserts/updates to tables
>    owned by a non-`ACTIVE` module.

If the posting core and the accounting modules are built in Phases 5–6 without
that guard, then entitlement enforcement has to be retrofitted **through the
write path of every already-written feature**.

**Why this is not hypothetical.** The reference application demonstrates exactly
this failure. Permissions were originally checked per handler, and
**55 of 71 mutating route files forgot the check** — a VIEWER could post journal
vouchers and run payroll. It was only fixed by centralising enforcement. Phase 0
recorded this as the reason for layer 2 existing at all.

Retrofitting a cross-cutting guard across finished features is precisely how that
drift happens.

**Recommendation.** Keep Phase 4 where you put it, but **split its deliverable**:
- **Phase 4** delivers the module *system* — registry, `FeatureModule` SPI,
  entitlement resolution, **and the three enforcement layers as working
  infrastructure** — plus the build script.
- The *content* of modules obviously arrives later.

Then Phase 5's posting core is written **against an existing guard**, and every
subsequent feature inherits it by construction rather than by memory.

This is a small change to what Phase 4 contains, not a reordering.

---

### 2.2 🟠 Inventory (P7) before Sales/Purchase (P8) is backwards

**The problem.** The proposed order is Inventory → Sales/Purchases/Taxation.
But under the now-agreed **perpetual costing** model, the dependency runs the
other way for the part that matters:

- COGS is posted **on issue**, and issue is triggered by **dispatch against a
  sales invoice**.
- Returns must be **first-class sign-aware movements linked to credit/debit
  notes** — and credit notes are a sales artifact (this is defect **D-23**).
- The reference's own good design decision — which Phase 0 recommends keeping —
  is that **invoicing and stock relief are deliberately decoupled**, with
  `pending = invoiced − dispatched`. That derivation needs invoices to exist.

Building inventory first means building the cost-layer engine with no document
to relieve stock against, then returning to wire it up. Building sales first
means inventory arrives into a working document flow.

**Counter-argument, fairly stated.** Items and warehouses are masters that
invoices reference, so *some* inventory must exist first. That is real — but it
is the **master data**, not the **costing engine**.

**Recommendation.** Split inventory across the boundary:
- **Inventory masters** (items, categories, units, warehouses, stock movements,
  quantity tracking) land with or just before sales/purchase.
- **Perpetual costing** (persisted cost layers, consumption records, COGS
  posting, returns costing, FEFO) lands *after* sales/purchase documents exist.

This also de-risks the single hardest accounting component by giving it a
working document flow to be tested against.

---

### 2.3 🟠 "Security + concurrency + recovery testing" as Phase 13 is too late

**The problem.** Testing concentrated into a late phase is the classic pattern
that produces a schedule cliff. Three specifics from Phase 0 make this concrete:

1. **`BEGIN IMMEDIATE` and explicit rollback** are *architectural* — SQLite does
   not poison a transaction on constraint violation, so a catch-and-continue
   silently commits partial journal entries. If this is wrong, it is wrong in
   **every** write path written before Phase 13.
2. **Corruption/recovery behaviour** (WAL growth from a leaked reader,
   network-path refusal, restore-over-live-data) is designed in Phase 2 or not
   at all.
3. **Concurrency semantics differ from the reference** — the 14 reference
   integration files that deliberately interleave transactions are a
   *specification to re-satisfy under `SQLITE_BUSY` semantics*, and they apply
   from the first posting code onward.

**Recommendation.** Keep Phase 13, but redefine it as a **certification gate,
not the first occurrence**:
- Concurrency invariants are tested **from Phase 2** (they are a property of the
  storage layer).
- Security tests accompany the feature that introduces the surface — licensing
  tests in Phase 3, entitlement tests in Phase 4, backup-crypto tests in Phase 10.
- **Phase 13 becomes adversarial/penetration review, failure-injection at scale,
  and recovery drills** — validating a system already built to be testable.

The distinction matters: a late testing phase that *discovers* architectural
problems is a rewrite; one that *confirms* them is a gate.

---

## 3. Two smaller observations

### 3.1 Phase 8 is doing too much
"Sales/Purchases/Taxation/Banking/etc." bundles four large domains. Taxation
alone is ~124 GST tests plus ~75 TDS/TCS tests, and carries a blocking decision
of its own (B-4, GST rounding). Banking has its own defect to resolve (D-29, two
reconcilers with the UI calling the untested one).

**Recommendation:** split into Sales/Purchase, Taxation, and Banking as separate
deliverables. They can still run in the same period, but they should not share a
completion gate — otherwise "Phase 8 is nearly done" becomes unmeasurable.

### 3.2 The 12 confirmed points contain one item worth restating precisely
Point 10 describes backup as *"encrypted locally before upload, with cloud
storage when internet becomes available"* — correct. Worth pairing with the
consequence recorded in the architecture: **customer-only key custody means a
forgotten passphrase is permanently unrecoverable**, and support genuinely cannot
help. That is a deliberate trade required by the brief, and it needs explicit
sign-off (decision I-14) rather than discovery at the first support call.

---

## 4. Recommended sequence

Adopting your structure, with the three adjustments:

| # | Phase | Change from proposal |
|---|---|---|
| 0 | Reference analysis + architecture ✅ | — |
| 1 | Desktop foundation + signed packaging | — |
| 2 | Database/storage + migrations **+ concurrency invariants** | testing pulled forward |
| 3 | Licensing + machine binding + subscription | — |
| 4 | Module/build architecture **+ all 3 enforcement layers** | **§2.1** |
| 5 | Core accounting engine / sealed posting core | — |
| 6 | Accounting modules (CoA, ledgers, vouchers) | — |
| 7 | **Inventory masters** + Sales/Purchase documents | **§2.2** |
| 8 | **Perpetual costing** (layers, COGS, returns) | **§2.2** |
| 9 | Taxation (GST/TDS/TCS) — *rule-pack infra first* | **§3.1** |
| 10 | Banking + reconciliation | **§3.1** |
| 11 | Year-end closing + financial reporting | — |
| 12 | Backup / cloud module | — |
| 13 | Admin / licensing portal | — |
| 14 | Upgrade / downgrade / data migration | — |
| 15 | Security, concurrency & recovery **certification** | **§2.3** |
| 16 | Production release certification | — |

Net effect: your 14 phases become 16, because two overloaded phases are split.
**No phase moved earlier or later in principle** — the ordering logic is yours.

---

## 5. What has not changed

- Phase 1 is still the immediate next step and depends on **no** blocking
  decision.
- The 8 🔴 BLOCKING decisions still gate their respective phases.
- Code-signing certificate procurement remains the longest lead item and should
  start now regardless of everything above.
