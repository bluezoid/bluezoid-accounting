# ADR-002: Module Packaging, Entitlement, and Safe Downgrade

Status: **RECOMMENDED — awaiting sign-off**
Addresses brief §8 (module architecture), §9 (downgrade), §18 (upgrade/downgrade
installers), §19 (schema strategy), §28 (plan/module/licence separation).

---

## The central insight

The brief asks for modules to be *physically* excluded from a build, and also for
a downgraded customer's historical data to survive and stay viewable. Those two
requirements pull in opposite directions, and the resolution is:

> **Separate the three things that a naive design collapses into one:
> the CODE that is packaged, the SCHEMA that exists, and the ENTITLEMENT that
> is granted.**

- **Code** is physically packaged per build. BASIC installers genuinely do not
  contain payroll classes.
- **Schema is ALWAYS complete.** Every installer's database has every module's
  tables, at the current schema version, regardless of plan.
- **Entitlement** is signed data from the licence server that says what may be
  *mutated*.

Schema is the cheap part (empty tables cost bytes) and the dangerous part to get
wrong. Code is the expensive part and safe to omit. Optimising the wrong one is
what turns a downgrade into data loss.

## Why "install module tables only when the module is present" must be rejected

It is the intuitive design and it fails badly:

1. **Downgrade would require dropping tables** — i.e. destroying accounting
   history, which the brief forbids absolutely (§9, §40).
2. **Migration becomes combinatorial.** With N optional modules there are up to
   2^N installed schema shapes. Every future migration must be correct against
   every shape. This is the "migration nightmare" §19 warns about.
3. **Cross-module foreign keys break.** A payroll voucher references a ledger and
   a fiscal year in core. Remove payroll tables and either the FK dangles or the
   voucher must be deleted.
4. **Re-upgrade could not restore data** that was dropped.
5. **Reports over historical data would fail** for periods when the module was
   active.

Universal schema costs a few hundred KB of empty tables. It buys the ability to
downgrade and re-upgrade losslessly, and a single migration path. That trade is
not close.

## The model

```
PLAN          what was purchased            (BASIC / PROFESSIONAL / ENTERPRISE)
MODULE        a packaged capability         (accounting, inventory, gst, payroll…)
ENTITLEMENT   what may be mutated NOW       (signed, server-issued, time-bounded)
LICENCE       identity of the installation
MACHINE       device bound to the licence
SUBSCRIPTION  the time window
```

Every module is in exactly one of four **runtime states**:

| State | Code present | Schema | Read | Write |
|---|---|---|---|---|
| `ACTIVE` | yes | yes | ✅ | ✅ |
| `READ_ONLY` | yes | yes | ✅ | ❌ downgraded, or subscription expired |
| `DORMANT` | **no** | yes | via export only | ❌ not in this build |
| `ABSENT` | no | yes (empty) | n/a | ❌ never used |

`DORMANT` is the case the brief describes: the customer downgrades to BASIC, gets
a BASIC installer with no payroll code, but their payroll history still exists.

### Handling DORMANT — the honest answer

If payroll code is physically absent, the application **cannot render a payroll
UI**. Claiming otherwise would be dishonest architecture. Two viable options:

- **(a) Ship read-only viewers for downgraded modules.** The BASIC build includes
  a small, generic "historical records" viewer plus per-module read-only view
  definitions, but not the module's engines (no payroll calculation, no posting).
  Data is viewable and exportable. Code size is modest because the *engines* are
  what is large, not the table renderers.
- **(b) Always ship all code; gate at runtime.** Simplest and guarantees full
  read access — but contradicts the brief's explicit requirement that modules be
  physically excluded.

**Recommendation: (a).** It satisfies both requirements: the payroll *engine* is
physically absent (so the build genuinely differs and the feature cannot be
used), while history remains viewable and exportable through a generic viewer.

**DECISION REQUIRED** — this is a product decision about what "view historical
data" must mean after downgrade. If the customer only needs *export*, a data-only
export path is simpler still and (a) can be reduced to a CSV/PDF exporter.

## Enforcement — where the gate lives

Entitlement must be enforced **at the application-service boundary**, not in the
UI, and ideally reinforced at the persistence layer:

1. Every use case declares its module.
2. A single interceptor checks module state before executing a mutating use case;
   `READ_ONLY` / `DORMANT` fail with a typed, explainable error.
3. **Reinforcement:** the write path additionally refuses inserts/updates to
   tables owned by a non-`ACTIVE` module. Belt and braces, because a missed
   annotation on one use case would otherwise be a silent hole — exactly the
   failure mode the reference app hit with permissions (55 of 71 mutating routes
   unguarded until enforcement was centralised).

**Reads are never gated by entitlement.** History is always readable. This is the
rule that makes downgrade safe, and it should be stated as an invariant.

## Cross-module dependencies — the hard cases

The brief asks about foreign keys, shared entities, and reports. From the code
analysis, these are the real ones:

**Payroll → core.** `Payslip.voucherId` → `Voucher`. A payroll voucher is a
*general ledger* voucher; it is core data, not payroll data. **Rule: once posted
to the GL, a transaction belongs to core** and remains fully visible in the trial
balance, P&L and balance sheet after a payroll downgrade. Only the payroll-specific
detail (payslip breakdown) becomes read-only. This is also the accounting-correct
answer — you cannot hide posted entries from the trial balance without unbalancing it.

**Inventory → reports.** `valueClosingStock` feeds **both** the P&L
(`COGS = opening + purchases − closing`) and the balance sheet (Stock-in-Hand).
If inventory becomes READ_ONLY, valuation must still be *computable* over
historical stock, or the financial statements silently change. **Therefore
valuation logic ships in core, not in the inventory module** — the inventory
module owns item/stock *management*, not the valuation used by statements.

**GST → sales/purchase.** Tax breakdown lives on `InvoiceItem`/`BillItem`, which
are sales/purchase tables. Losing the GST module must not prevent invoices from
carrying their historical tax columns. **Rule: tax *data* on documents is core;
the GST *return engines* are the module.**

**General rule this yields:** *the module owns its engines and its own tables; it
does not own columns on another module's tables, and it never owns posted GL
entries.* Applying this rule up front prevents most cross-module downgrade pain.

## Re-upgrade: PROFESSIONAL → BASIC → PROFESSIONAL

Because schema is universal and data is never deleted, re-upgrade is simply:
install the PROFESSIONAL build, refresh entitlement, modules return to `ACTIVE`,
and every historical row is exactly where it was. No data migration, no
restoration step.

**Caveats to test explicitly:**
- Derived/cached state (e.g. any denormalized balance) must be consistent for the
  dormant period — argues for computing balances from the journal rather than
  caching them, or for a verifiable rebuild routine.
- Document number counters must not have been reset or reused while dormant.
- A module dormant across a schema migration must still have had its tables
  migrated — guaranteed by universal schema, and worth an explicit test.

## Build system

One codebase, one build, module selection at assembly time:

```
./build.sh --plan PROFESSIONAL --modules accounting,inventory,gst,payroll
```

The script selects a Maven profile / module list, the launcher's composition root
includes only those feature modules, and `jlink` + `jpackage` produce a
per-OS installer with a bundled runtime (no Java install required by the customer).

Build metadata (plan, module list, version, build id) is embedded in the artifact
and reported by the app, so support can tell exactly what a customer is running.

**Note:** the build's module list and the licence's entitlement are independent
and must be reconciled at activation. A PROFESSIONAL build activated with a BASIC
licence must degrade to BASIC behaviour — the licence is authoritative, never the
build. Otherwise shipping a wrong installer would silently grant entitlement.

## Consequences

- Every installer ships the complete schema and the complete migration chain.
- A module's presence never affects the database — only what may be mutated.
- Downgrade is a licence event, not a data event.
- The combinatorial migration problem is eliminated by construction.
