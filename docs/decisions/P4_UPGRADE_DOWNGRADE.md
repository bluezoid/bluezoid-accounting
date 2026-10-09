# P4 — Upgrade / Downgrade Model

**Status:** APPROVED ARCHITECTURAL DECISION (pending sign-off)
**Date:** 2026-09-05 · **Extends:** `docs/architecture/UPGRADE_DOWNGRADE.md`

---

## 1. The governing principle

> **Removing a feature from a customer's entitlement must never be equivalent to
> deleting the feature's historical data.**

For accounting software this is not a nicety. A payroll journal entry is a
statutory record; a downgrade is a commercial event. The two must not touch.

## 2. Why the universal schema makes this safe

P2 chose **one schema for every build** (ADR-002). Three properties follow:

1. **A downgrade requires no migration at all.** Nothing is dropped, so there is
   no destructive step to get wrong.
2. **Migrations stay linear.** Plan-specific schemas would give 2ⁿ shapes, each
   needing every future migration to be correct against it.
3. **Re-upgrade is free.** The data was never removed, so it is simply usable
   again.

We are also **not exposed to the reference application's build hazard**: its
`Voucher` model declares seven back-relations (to Payslip, Bill, Invoice,
Payment, Receipt, TDS, TCS — verified). Generating a slim ORM schema would fail
to compile unless those were stripped. Hand-written SQL plus a universal schema
sidesteps this entirely. **Do not reintroduce a schema generator.**

## 3. Professional → Basic

```
1. Admin changes the plan                              (audited, server-side)
2. Customer refreshes entitlement
      → payroll/inventory/manufacturing become READ_ONLY
      → data fully visible; new writes refused at the use-case AND persistence layers
3. Customer installs the Basic build
      → module code physically removed
      → those modules become DORMANT
      → data STILL PRESENT; reachable via the generic records viewer and export
4. Uninstalling the old build touches program files only
```

**Step 2 before step 3 is deliberate.** Entitlement takes effect immediately;
the installer follows. The customer is never in a state where they can still
create data in a module they no longer pay for.

### What must NOT happen

| Forbidden | Why |
|---|---|
| Dropping module tables | Destroys statutory records |
| Deleting module rows | Same |
| **Hiding posted vouchers from the trial balance** | **The books would stop balancing** |
| Recalculating history | A filed statement must stay reproducible |
| Uninstaller touching app-data | Data loss on a routine reinstall |

The third row is the subtle one. **Ten paths post to the GL** — including
payroll and manufacturing. Those vouchers are *general ledger entries*, not
module data. The trial balance reads `voucher_entry` with **no module filter**,
which is exactly what makes modular builds safe. Only module-specific *detail*
(the payslip breakdown) becomes unreachable.

## 4. Basic → Professional

```
1. Admin changes the plan
2. Customer refreshes entitlement
      → modules entitled but NOT in this build
      → reported as DORMANT: "included in your plan — install the Professional build"
3. Customer installs the Professional build
      → modules become ACTIVE
4. Any data written before an earlier downgrade is immediately usable again
```

`effective = build ∩ entitled`, so an entitlement ahead of the build is safe and
self-explaining rather than an error.

## 5. Exact state per scenario

| Scenario | Installed | Entitled | State | Read | Write |
|---|:--:|:--:|---|:--:|:--:|
| Pro build, Pro licence | ✅ | ✅ | `ACTIVE` | ✅ | ✅ |
| Pro build, Basic licence (entitlement refreshed, installer not yet swapped) | ✅ | ❌ | `READ_ONLY` | ✅ | ❌ |
| Basic build, Basic licence, payroll history exists | ❌ | ❌ | `DORMANT` | viewer | ❌ |
| Basic build, Pro licence (upgraded, not yet installed) | ❌ | ✅ | `DORMANT` | viewer | ❌ |
| Basic build, never had payroll | ❌ | ❌ | `ABSENT` | ❌ | ❌ |
| Subscription expired, module installed | ✅ | ✅ | `READ_ONLY` | ✅ | ❌ |
| Module smuggled onto the module path | ✅ | ❌ | `READ_ONLY` | ✅ | ❌ |

## 6. Version compatibility

Five versions, deliberately independent:

| Version | Lives in | Rule |
|---|---|---|
| **App** | binary | informational |
| **Schema** | each database file | **refuse to open a NEWER file** |
| **Module** | manifest | a module refuses an older schema than it needs |
| **Entitlement format** | token | refuse a newer format (P3 already does) |
| **Plan** | entitlement | resolved to a module set |

**The dangerous case — old installer + new `company.db` — is already prevented**
by P2's `SchemaNewerThanApplicationException`, tested. Older code does not know
about newer columns or constraints, so it would write rows that quietly violate
them. The file is refused, unmodified, with an instruction to update.

**Ordering rule:** migrate first, then entitle. A module must never activate
against an unmigrated database.

## 7. Migration and absent modules (for P5+)

A migration may need to touch tables whose module is not installed. Since the
schema is universal, **the tables are always there** — migrations operate on
schema, not on module code, and never call module logic. A migration that needed
a module's business logic to run would be a design error, and the stop-don't-guess
rule from P2 applies: if a data migration cannot determine the correct result, it
**aborts** rather than guessing.
