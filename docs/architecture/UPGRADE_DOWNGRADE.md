# Upgrade & Downgrade

**Status:** PROPOSED — awaiting sign-off
**Depends on:** MODULE_ARCHITECTURE.md (universal schema) ·
SUBSCRIPTION_AND_ENTITLEMENTS.md

**The governing rule: customer accounting data is NEVER deleted because of a
plan change.** Everything here follows from it.

---

## 1. Three independent axes

A plan change touches only two of the three, and never the data:

| Axis | Upgrade | Downgrade |
|---|---|---|
| **Code** | new installer adds modules | new installer removes modules |
| **Entitlement** | more modules ACTIVE | fewer modules ACTIVE |
| **Data** | **unchanged** | **unchanged** |

## 2. Downgrade: PROFESSIONAL → BASIC

```
Before                          After
──────                          ─────
Code:        A B C D E F G H I  →  A B C D
Entitlement: all ACTIVE         →  A B C D ACTIVE
                                   E F G H I DORMANT
Database:    all tables + data  →  ALL TABLES + ALL DATA (identical)
```

### Sequence

```
1. Admin changes the plan in the portal            (audited)
2. Customer refreshes entitlement (or next check)
   → E..I become READ_ONLY immediately
   → data remains fully visible; new transactions blocked
3. Customer receives + installs the BASIC installer
   → module CODE physically removed
   → E..I become DORMANT
   → data STILL present; viewable via the generic viewer / export
4. Uninstall of the old build touches program files ONLY
```

**Step 2 before step 3 is deliberate.** Entitlement takes effect immediately;
the installer follows. That way the customer is never in a state where they can
still create data in a module they no longer pay for.

### What the user sees for a dormant module

- Navigation entry present but visibly disabled, with an explanation
  ("Payroll is not included in your current plan. Your existing payroll records
  remain available to view and export.")
- Historical records reachable through the generic read-only viewer.
- Export always available.
- **Reports that legitimately include dormant-module data still work** — because
  posted GL entries are CORE, not module-owned (see MODULE_ARCHITECTURE §6).

### What must NOT happen

| Forbidden | Why |
|---|---|
| Dropping module tables | Destroys accounting history |
| Deleting module rows | Same |
| Hiding posted vouchers from the trial balance | **Would unbalance the books** |
| Removing module data from prior-period reports | Falsifies filed statements |
| Uninstaller touching the app-data directory | Data loss on a routine reinstall |

That third row deserves emphasis: a payroll JV is a *general ledger* entry. It
must remain in the trial balance, P&L and balance sheet after a payroll
downgrade, or the books stop balancing. Only payroll-specific *detail* (the
payslip breakdown) becomes read-only.

## 3. Upgrade: BASIC → PROFESSIONAL

```
1. Admin changes plan                              (audited)
2. Customer refreshes entitlement
   → E..I entitled but NOT in this build
   → app reports: "Included in your plan — install the PROFESSIONAL build"
3. Customer installs the PROFESSIONAL installer
   → module code present
   → E..I become ACTIVE
4. Any data written before a previous downgrade is immediately usable again
```

Effective capability is always `build_modules ∩ entitled_modules`, so an
entitlement ahead of the build is safe and self-explaining.

## 4. Round trip: PRO → BASIC → PRO

The requirement that makes universal schema non-negotiable.

```
t0  PRO    payroll runs for Apr–Sep, data written
t1  BASIC  payroll DORMANT; Apr–Sep data intact and viewable
t2  PRO    payroll ACTIVE again; Apr–Sep data intact AND mutable
           October payroll can be run, continuing the same series
```

No data migration, no restore step — because nothing was ever removed.

### Round-trip hazards to test explicitly

| Hazard | Mitigation |
|---|---|
| Number counters reset or reused while dormant | Counters live in core; never reset by plan change |
| A schema migration ran while the module was dormant | Universal schema means its tables migrated too — **assert this in a test** |
| Cached/derived state stale for the dormant period | Prefer computing balances from the journal over caching |
| Cross-module FKs orphaned | FKs never dropped; `foreign_key_check` after every migration |
| Reports spanning the dormant period | Posted entries are CORE and always included |

## 5. Interaction with application updates

Plan change and version update are **independent** and may coincide:

```
BASIC v1.2  →  PROFESSIONAL v1.4
```
Handled as: install v1.4 PROFESSIONAL → migrate each company file on open
(v1.2 → v1.4 schema) → refresh entitlement → modules activate.

Order matters: **migrate first, then entitle.** Activating a module against an
unmigrated database could expose tables that do not yet have their current shape.

## 6. Installer obligations

Every installer, on every platform, must:

1. **Never write to the app-data directory** beyond first-run creation.
2. **Never delete the app-data directory** on uninstall without a separate,
   explicit, clearly-worded confirmation — and even then, offer export first.
3. Refuse to install an **older application version** over a newer schema, with a
   clear message (an older binary cannot read a newer company file).
4. Record plan, module list, version and build id in a readable manifest.

These belong in the installer scripts as tested guarantees, not conventions.

## 7. Testing requirements

| Test | Asserts |
|---|---|
| Downgrade preserves data | Row counts identical before/after; trial balance unchanged |
| Dormant module data is readable | Viewer/export returns the same records |
| Dormant module writes are refused | Typed error at the use-case boundary **and** the persistence guard |
| Posted dormant-module vouchers stay in reports | Trial balance still balances; P&L unchanged |
| Re-upgrade restores mutability | Pre-downgrade records editable again |
| Round trip PRO→BASIC→PRO | Zero data loss; counters continue |
| Migration while dormant | Dormant tables migrated; `foreign_key_check` clean |
| Plan change + version update together | Both applied, in the right order |
| Uninstall/reinstall | Company data survives |
| Wrong-plan installer | Licence wins; degrades, does not elevate |

## 8. Open decisions

- **C-1** Dormant-module read access: generic viewer (recommended), export-only,
  or ship-all-code.
- Whether a downgrade should offer an **automatic export** of soon-to-be-dormant
  module data as a courtesy at the point of change (recommended — cheap, and it
  defuses the "will I lose my data?" support conversation).
- Mid-term proration/refund policy (commercial).
