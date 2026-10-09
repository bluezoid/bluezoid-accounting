# Phase 4 Report — Physical Modules, Build Variants, Registry & Entitlement Enforcement

**Date:** 2026-09-01
**Status:** Implementation complete, awaiting review
**Tests:** 281 passing · 0 failing · 3 UI skipped (no display) · `BUILD SUCCESS`

---

## 1. What was implemented

### 1.1 Module identity and contract (`accubook-module-api`)

| File | Purpose |
|---|---|
| `ModuleId.java` | Validated value type; kernel constants `core`, `accounting`, `parties`, `reporting-core` |
| `FeatureModule.java` | Extended SPI: `dependsOn()`, `version()`, `requiredSchemaVersion()`, `historicalViews()` |
| `HistoricalView.java` | Declarative record metadata — pure data, no behaviour |
| `ModuleRegistry.java` | ServiceLoader discovery, validation, table ownership |
| `ModuleRegistryException.java` | Reports **every** problem at once, not the first |

### 1.2 Enforcement — two layers, deliberately

**Layer 1 — use case** (`EntitlementGuard`, `ModuleNotWritableException`)
Catches the common case with a message that tells the customer what to do.

**Layer 2 — persistence** (`GuardedConnection`, `GuardedStatements`,
`WriteAuthorization`, `UnauthorizedWriteException`)
Load-bearing, not belt-and-braces. Layer 1 depends on a developer routing a
mutation through it, which is exactly the discipline the reference application
failed at — **55 of 71 mutating routes omitted their permission check**.

`GuardedConnection` wraps the JDBC `Connection` with a dynamic proxy, so there is
no route to the database that skips the check. A hand-written delegate over
`Connection`'s 50+ methods would eventually miss one, and the missed one becomes
the hole.

**Bridge** (`ModuleWriteAuthorization`, in `accubook-app`) — the only place the
module layer and persistence meet. Persistence never learns what a licence is.

### 1.3 Build variants

| File | Purpose |
|---|---|
| `BuildPlan.java` | `basic` / `professional` / `enterprise` — a build target, never a runtime authorization source |
| `PlanCatalogue.java` | The approved B-7 matrix, with transitive dependency closure |
| `PlanResolverTool.java` | CLI entry point so `build.sh` has no second copy of the matrix |
| `build.sh` | `--plan` and `--add-on` now physically select modules; writes a diagnostic manifest |

---

## 2. Design decisions worth stating

### 2.1 Reads are never gated
There is deliberately no `requireReadable`. A customer's books are theirs whatever
their subscription says. Withholding a customer's own history is both wrong and
the main reason people crack software rather than renew it. `ModuleState.allowsRead()`
is true for everything except `ABSENT`.

### 2.2 The guard fails closed
A mutating statement whose table cannot be identified is **refused**, not waved
through. A guard that fails open is not a guard — and an unparseable mutating
statement is precisely what an attacker would construct.

### 2.3 The guard fails *open* in exactly one bounded place
A table no module claims is a kernel table (companies, migrations, settings,
chart of accounts) and is permitted — otherwise the application could not open.
This is bounded by `ModuleRegistry` refusing to start when two modules claim one
table, and asserted in tests rather than left to review.

### 2.4 Transaction control is never filtered
`BEGIN` / `COMMIT` / `ROLLBACK` run on the real connection; only the work body
sees the guarded one. This matters concretely: **SQLite does not poison a
transaction on constraint violation**, so a guard that swallowed a `ROLLBACK`
would commit the surviving half of a voucher — the exact failure the reference
application fought. Test: `rollbackStillWorks`.

### 2.5 Migrations bypass the guard, by name
`Database.inUnguardedTransaction()` exists for migrations and provisioning, which
run before any module or entitlement is resolved. Named so its use is obvious in
review.

### 2.6 The build manifest is diagnostic, never authoritative
Plain text and unsigned. A customer editing it changes nothing but their own
support output, because the entitlement decides what may be used.

---

## 3. Two defects found and fixed during implementation

**3.1 `ModuleId` static initialiser order** — the `VALID` pattern was declared
after the constants that invoke the constructor, giving
`ExceptionInInitializerError`. Caught by the existing P3 `ModuleIsolationTest`.

**3.2 Case-sensitive table ownership (security-relevant)** — `ownerOf()` did a
case-sensitive `contains`, but the guard derives table names from SQL and
lower-cases them. A module declaring `"Payroll_Runs"` would have resolved to *no
owner*, and therefore **would have been writable while the module was READ_ONLY**.

Fixed by normalising ownership at construction. Verified the test genuinely
catches it: reverting the fix fails `ownershipIsCaseInsensitive`, restoring it
passes. Also added duplicate-table-ownership validation, which the bridge's
documentation already assumed existed.

---

## 4. Tests

| Suite | Count | Covers |
|---|--:|---|
| `ModuleRegistryTest` | 16 | Discovery, duplicate ids, missing deps, cycles, self-dependency, broken metadata, table ownership, case-insensitivity |
| `EntitlementGuardTest` | 11 | Every state, read-never-gated, message quality |
| `GuardedStatementsTest` | 21 | Mutation classification, table extraction, transaction control |
| `WriteGuardTest` | 12 | Refusal, permission, rollback integrity, **4 bypass attempts** |
| `EntitlementEnforcementTest` | 9 | End-to-end registry → state → guard against real SQLite |
| `PlanCatalogueTest` | 16 | The approved matrix, nesting, dependency closure, determinism |
| **New total** | **85** | |

### Bypass attempts, all refused
- `connection.unwrap(Connection.class)` → refused
- `statement.unwrap(Statement.class)` → refused
- unparseable mutating statement → refused (fails closed)
- statement created later in the same transaction → still guarded

### Accounting-safety assertions
- a refused write leaves data untouched (`refusedWriteChangesNothing`)
- a downgrade preserves records and permits reading (`downgradePreservesAndPermitsReading`)
- **posted ledger entries stay writable when payroll is locked** — a payroll
  journal entry is a *ledger* entry; if payroll owned the ledger, a downgrade
  would stop the trial balance being maintained and the books would cease to
  balance (`ledgerIsNotOwnedByPayroll`)

---

## 5. Build verification — actually run, not just parsed

```
./build.sh --plan professional --add-on taxation-returns --skip-tests
  → plan resolves to 10 optional modules
  → runtime image 99M, installer accubook_0.1.0-1_amd64.deb (70M)

./build.sh --plan basic --skip-tests
  → plan resolves to 5 optional modules
```

Both manifests parse as valid JSON and differ correctly. Module *exclusion* was
verified separately with synthetic artifacts (no feature modules exist yet, so a
real build excludes nothing): with `PLAN_MODULES=(inventory banking)`,
`accubook-payroll` and `accubook-manufacturing` were excluded and infrastructure
retained.

---

## 6. What could NOT be tested, and why

| Gap | Why | Risk |
|---|---|---|
| Exclusion in a *real* build | No feature modules exist yet — P5+ | Low; logic verified with synthetic artifacts |
| Module smuggling via `--module-path` into a jlink image | Needs a real optional module to smuggle | **Medium — see §7** |
| UI tests (3 skipped) | TestFX/Monocle fails on JavaFX 17; Xvfb not installed | Pre-existing, unchanged |
| Downgrade SHA-256 comparison | Needs two real builds differing by a module | Deferred to P5 |

---

## 7. Remaining risks

**7.1 The smuggling test is deferred, not passed.** P4 research measured that a
jlink image cannot be extended by dropping in a jar, **but `--module-path` at
launch can smuggle a module in**, and reflection defeats JPMS at runtime. The
persistence guard is the answer — a smuggled module still has to write through a
guarded connection — but this is argued, not yet demonstrated end to end. It
should be proven in P5 as soon as one real optional module exists.

**7.2 Unowned feature tables are writable.** Bounded by registry validation and
asserted in tests, but a *new* feature table that nobody declares in
`ownedTables()` would be permitted. Recommend an architecture test in P5 that
asserts every non-kernel table has an owner, once the table set is known.

**7.3 `GuardedStatements` is regex-based.** Adequate as a second layer over SQL we
write ourselves, and it fails closed. It is not a SQL parser and should not be
relied on as one.

---

## 8. Files created / modified

**Created (15):**
```
accubook-module-api/src/main/java/com/accubook/module/
  ModuleId.java  HistoricalView.java  ModuleRegistry.java
  ModuleRegistryException.java  EntitlementGuard.java  ModuleNotWritableException.java
accubook-module-api/src/main/java/com/accubook/module/plan/
  BuildPlan.java  PlanCatalogue.java  PlanResolverTool.java
accubook-persistence/src/main/java/com/accubook/persistence/guard/
  WriteAuthorization.java  UnauthorizedWriteException.java
  GuardedStatements.java  GuardedConnection.java
accubook-app/src/main/java/com/accubook/app/entitlement/
  ModuleWriteAuthorization.java
docs/phases/PHASE_4_REPORT.md
```

**Test files created (6):** `ModuleRegistryTest`, `TestModule`, `EntitlementGuardTest`,
`PlanCatalogueTest`, `GuardedStatementsTest`, `WriteGuardTest`, `EntitlementEnforcementTest`

**Modified (6):**
```
accubook-module-api/src/main/java/com/accubook/module/FeatureModule.java   id() now returns ModuleId
accubook-module-api/src/main/java/module-info.java                         exports module.plan
accubook-persistence/src/main/java/com/accubook/persistence/connection/Database.java
                                                                           authorizeWith(), inUnguardedTransaction()
accubook-persistence/src/main/java/com/accubook/persistence/connection/UnitOfWork.java
                                                                           control/visible connection split
accubook-persistence/src/main/java/com/accubook/persistence/migration/MigrationRunner.java
                                                                           uses the unguarded path
accubook-persistence/src/main/java/module-info.java                        exports guard
accubook-app/src/main/java/module-info.java                                exports entitlement
build.sh                                                                   --plan / --add-on, manifest
```

**No test was weakened or deleted.** `ModuleIsolationTest` was updated only for
the `id()` signature change; its assertions are unchanged.

---

## 9. Not in P4, as scoped

Accounting transactions · sales · purchase · inventory · GST · banking · payroll ·
manufacturing · OCR · backup · licence server · admin portal.
