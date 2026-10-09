# Module Architecture

**Status:** RECOMMENDED — **Module Code + Universal Schema + Signed Entitlement**
**Expands:** ADR-002

---

## 1. The question posed

> Is `Module Code + Universal Schema + Signed Entitlement` the safest model?

**Yes — and the alternative is genuinely dangerous.** The reasoning below is the
justification, not an assertion.

## 2. Three things that must never be conflated

| Concern | Varies by | Changes when | Governs |
|---|---|---|---|
| **CODE** | build/plan | new installer | what the app *can do* |
| **SCHEMA** | app version only | app update | what data *can exist* |
| **ENTITLEMENT** | licence | server refresh | what may be *mutated* |

Collapsing any pair produces a specific failure:

- **Code = Schema** → downgrade must drop tables → **data loss**. Forbidden.
- **Schema = Entitlement** → expiry would alter the schema → **data loss**.
- **Code = Entitlement** → a wrong installer silently grants entitlement →
  **revenue loss and inconsistent state**.

## 3. Why universal schema, specifically

The intuitive design — "install a module's tables only when the module ships" —
fails on five counts:

1. **Downgrade would require dropping tables**, destroying accounting history.
   This is prohibited outright.
2. **Migrations become combinatorial.** With N optional modules there are up to
   2ⁿ installed schema shapes, and every future migration must be correct
   against all of them. With 10 modules that is 1,024 shapes.
3. **Cross-module foreign keys break.** A payroll voucher references a ledger and
   a fiscal year in core. Remove payroll tables and either the FK dangles or the
   voucher must be deleted.
4. **Re-upgrade cannot restore dropped data.**
5. **Historical reports fail** for periods when the module was active.

Universal schema costs a few hundred kilobytes of empty tables. It buys lossless
downgrade, lossless re-upgrade, and a **single linear migration path**. The trade
is not close.

## 4. Module runtime states

| State | Code | Schema | Read | Write | Cause |
|---|---|---|---|---|---|
| `ACTIVE` | ✅ | ✅ | ✅ | ✅ | entitled + packaged |
| `READ_ONLY` | ✅ | ✅ | ✅ | ❌ | downgraded, or subscription expired |
| `DORMANT` | ❌ | ✅ | via viewer/export | ❌ | not in this build |
| `ABSENT` | ❌ | ✅ (empty) | n/a | ❌ | never used |

**Invariant: reads are never gated by entitlement.** History is always viewable
and exportable — after downgrade, after expiry, always. This single rule is what
makes downgrade safe, and it should be stated in the code as an assertion.

## 5. The downgrade walkthrough

```
Customer on PROFESSIONAL:  A B C D E F G H I   (has payroll data)
                    ↓ downgrades to BASIC
Installer:                 A B C D             (no payroll CODE)
Database:                  A B C D E F G H I   (ALL tables, ALL data intact)
Entitlement:               A B C D = ACTIVE
                           E F G H I = DORMANT (read-only)
```

Nothing is deleted. The payroll *engine* is physically absent from the build, so
the feature genuinely cannot be used — satisfying "physically packaged" — while
the data survives untouched.

### Read access when the code is gone — the honest problem

If payroll code is absent, the app **cannot render the payroll UI**. Claiming
otherwise would be dishonest architecture. Three options:

- **(a) Generic read-only viewer + per-module view definitions** *(recommended)*.
  The base build carries a small table/record viewer plus declarative view
  metadata for every module; it does **not** carry the engines (no payroll
  calculation, no GST return generation, no posting). Data is viewable and
  exportable. Cost is modest because engines are the bulk, not renderers.
- **(b) Export-only.** Simplest: dormant module data is reachable only through
  CSV/PDF export. Lowest cost; weakest UX.
- **(c) Always ship all code, gate at runtime.** Guarantees full read access but
  **contradicts the physical-packaging requirement**.

**Recommendation: (a).** **DECISION REQUIRED** — this defines what "view
historical data" means commercially.

## 6. Cross-module dependency rules

Derived from the actual coupling in the reference:

> **A module owns its engines and its own tables. It never owns columns on
> another module's tables, and it never owns posted GL entries.**

Applied:

| Coupling | Rule |
|---|---|
| Payroll → `Voucher` | **Once posted, a transaction is CORE.** Payroll JVs stay fully visible in the trial balance, P&L and balance sheet after a payroll downgrade. Only payslip *detail* becomes read-only. This is also the accounting-correct answer — you cannot hide posted entries without unbalancing the books. |
| Inventory → P&L / BS | **Valuation ships in CORE**, not the inventory module. The inventory module owns item/stock *management*. Otherwise a downgrade would silently change the financial statements. |
| GST → invoice lines | **Tax data on documents is CORE**; the GST *return engines* are the module. Losing the GST module must not stop invoices carrying their historical tax columns. |
| Approvals → any entity | Approval framework is CORE; module-specific routing rules travel with the module. |

## 7. Enforcement — where the gate lives

Three layers, because one is not enough:

1. **Use-case interceptor** — every mutating use case declares its module; a
   single interceptor checks state and fails with a typed, explainable error.
2. **Persistence guard** — the write path refuses inserts/updates to tables owned
   by a non-`ACTIVE` module. Belt and braces: a missed annotation would otherwise
   be a silent hole.
3. **UI affordance** — dormant/read-only features are visibly disabled with an
   explanation, never merely hidden.

Layer 2 matters because of a proven failure mode in the reference: permissions
were originally per-handler, and **55 of 71 mutating routes forgot the check**
until enforcement was centralised. Entitlement will drift the same way if it
relies on developers remembering.

## 8. Build system

One codebase; module selection at assembly time.

```bash
./build.sh --plan professional
./build.sh --plan custom --modules accounting,inventory,gst,backup
```

- The build resolves plan → module list (from a single source of truth shared
  with the licence server), selects Maven/Gradle modules, and assembles the
  launcher's composition root.
- `jlink` trims the runtime; `jpackage` produces the native installer.
- **Build metadata is embedded** (plan, module list, version, build id, commit)
  and displayed in-app, so support knows exactly what a customer runs.

### The licence is authoritative over the build

A PROFESSIONAL build activated with a BASIC licence **must degrade to BASIC**.
Otherwise shipping the wrong installer silently grants entitlement. The build
defines the *ceiling* of what is possible; the licence defines what is *allowed*.

```
effective_modules = build_modules ∩ entitled_modules
```

Any module in `entitled − build` is reported to the user as
"included in your plan — please install the correct build".

## 9. Module registration (JPMS + ServiceLoader)

Each feature module provides a `FeatureModule` service implementation declaring
its id, display name, navigation entries, use cases, owned tables, and view
definitions. The shell discovers modules via `ServiceLoader` and renders
navigation from what it finds — the shell **never imports a feature module**, so
it compiles and runs with any subset.

## 10. Upgrade / re-upgrade

```
BASIC → PROFESSIONAL → BASIC → PROFESSIONAL
```
Because schema is universal and data is never deleted, re-upgrade is: install the
build, refresh entitlement, modules return to `ACTIVE`, all historical rows
exactly where they were. No data migration, no restoration step.

**Must be tested explicitly:**
- Data written before downgrade is intact and mutable after re-upgrade.
- Document number counters were not reset or reused while dormant.
- A module dormant across a schema migration still had its tables migrated
  (guaranteed by universal schema — assert it).
- Derived/cached state is consistent for the dormant period (an argument for
  computing balances rather than caching them).

## 11. Open decisions

- **C-1** Dormant-module read access: viewer / export-only / ship-all-code.
- **C-2** The concrete plan → module matrix. **Blocks the build system.**
- Whether "modules" are sold à la carte (e.g. PROFESSIONAL + backup) in addition
  to fixed plans — the model supports it, but pricing and the admin UI must too.
