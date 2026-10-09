# Phase 6 Report — Accounting Modules

**Date:** 2026-09-03 · **Status:** ✅ **COMPLETE / READY FOR P7**
**Tests:** 373 passing · 0 failing · 3 UI skipped · `BUILD SUCCESS`
**Baseline before P6:** 333 (+40)

---

## 1. Exit criterion — met

> company → CoA → ledgers → voucher type → posting → trial balance →
> reversal → trial balance still correct

`BookkeepingCycleIT.fullBookkeepingCycle` posts capital introduced, verifies the
trial balance, reverses it, and verifies again. All 8 cycle tests pass.

## 2. Implemented

| File | Purpose |
|---|---|
| `AccountNature` | five natures; which side increases a balance |
| `LedgerAccount` | identity / governance / label kept separate |
| `TrialBalanceRow`, `TrialBalance` | the report and its self-check |
| `ChartOfAccounts` | reads, governance, trial balance |
| `ChartException` | refusals aimed at the person who attempted them |
| migration 3 | `ledger.is_system`, 6 triggers, index |

Provisioning now sets `is_system = 1` on seeded ledgers. **No new seed data** —
P2-1 holds: 18 groups, 4 ledgers, 8 voucher types, unchanged.

## 3. Three defects found — two mine, one latent in the codebase

### 3.1 The trial balance was never fiscal-year scoped — my bug, caught by A-10

The query filtered the year on a `LEFT JOIN voucher`:

```sql
LEFT JOIN voucher_entry e ON e.ledger_id = l.id
LEFT JOIN voucher v ON v.id = e.voucher_id
     AND v.is_posted = 1 AND v.fiscal_year_id = ?     -- WRONG
```

`SUM` reads `voucher_entry`, which is joined **unconditionally**, so entries from
every year were summed while the filter merely nulled the `v` columns. The
report silently covered all time.

It went unnoticed until the cross-year reversal test (A-10), where the original
and its next-year contra cancelled to zero in both years. Fixed with an `EXISTS`
on the entry join. **This is exactly the test the design added because the
behaviour "must be tested rather than discovered".**

### 3.2 `ServiceLoader` would have thrown at startup — latent

**Testing lesson worth preserving:** *supplying a provider list does not prove
`ServiceLoader` discovery works.* P4's isolation test passed
`discover(Iterable<FeatureModule>)`, which never touches the JPMS service
machinery, so the missing `uses` clause was invisible to it. The two code paths
need separate coverage.


`accubook.module.api` declared no `uses` clause, so
`ModuleRegistry.discover()` raises `ServiceConfigurationError: does not declare
'uses'` rather than returning an empty list.

P4 measured the empty-result behaviour with an **explicit provider list**, which
does not exercise this path. Nothing in production calls `discover()` yet, so it
was latent — but it is the intended startup path and would have failed the first
time it was wired in. Found by the carried table-ownership test.

### 3.3 The packaged image did not contain the accounting module — found by the P6 exit verification

`build.sh` reported `included: accubook-accounting`, and it was on the module
path. **It was absent from the linked runtime image.**

```
$ target/runtime/bin/java --list-modules | grep accubook
accubook.app  accubook.launcher  accubook.licensing
accubook.module.api  accubook.persistence  accubook.platform  accubook.ui.shell
                                            # no accubook.accounting
```

`jlink` resolves transitively from `--add-modules accubook.launcher`. Nothing
`requires accubook.accounting` yet — the launcher will not until a use case wires
it in, which is P7 — so jlink pruned it. **Putting a module on the module path is
necessary and not sufficient.**

This is the second packaging defect of its kind: P5's `build.sh` omitted the
module from the path entirely, and fixing that surfaced this deeper one. Both
would have shipped an installer whose application could not post.

Fixed by naming every selected AccuBook module explicitly in `--add-modules`.
Verified by listing the image's modules and running exported P6/P5 API from
inside it.

**Only the packaged-artifact verification found this.** The full 373-test suite
passes either way, because the Maven reactor resolves modules from
`target/classes` and never consults the linked image.

### 3.4 A stale-jar debugging detour — now a written procedure

Five governance tests failed because `accubook-app` had not been reinstalled, so
the accounting module tested against provisioning code without `is_system`. A
clean `mvn verify` resolved it; no code was wrong.

This is the **second** occurrence of the class — P1 lost real time to a stale jar
in `target/deps` producing a phantom nested-transaction failure. Twice is a
process gap, not bad luck, so it is now written down rather than left as tribal
knowledge:

> **When an integration test exercises the installed application or runtime,
> reinstall the current build before interpreting a failure as an implementation
> defect. Never weaken a test to accommodate a stale artifact.**

Recorded in `docs/DEVELOPMENT_PROCEDURES.md`.

## 4. Reference research — four governance defects

| ID | Finding |
|---|---|
| **R-1** | `isSystem` is set at provisioning and **never checked** — 0 occurrences across both ledger routes |
| **R-2** | A system ledger with no postings is **hard-deletable**; on a fresh company that is all of them |
| **R-3** | PATCH passes `groupId` straight through, so a ledger can move across natures after posting, flipping `isDebitNatured` against its own history |
| **R-4** | `post-invoice.ts:207` resolves Round Off **by display name** via `getOrCreateNamedLedger`; renaming creates a second control account and splits the balance |

## 5. What P6 enforces

| Rule | Where |
|---|---|
| System ledger never deletable | service + trigger |
| System ledger never deactivatable | service |
| `system_code` immutable on system rows | trigger |
| Rename always allowed (P6-2) | service — safe because nothing resolves by name |
| No nature change once posted (R-3) | service |
| User account deletable only when unposted | service |
| System group never deletable (A-9) | service + trigger |
| Group with ledgers or sub-groups never deletable (A-9) | service + 2 triggers |
| Deactivated account with a balance stays reported (A-6) | trial balance query |
| REVERSED still counts | `is_posted` filter |

## 6. Tests added — 40

| Suite | Count |
|---|--:|
| `AccountNatureTest` | 7 |
| `LedgerAccountTest` | 3 |
| `ChartGovernanceIT` | 18 |
| `BookkeepingCycleIT` | 8 |
| `TableOwnershipTest` (carried from P4) | 4 |

### Adversarial results — all refused
Delete a system ledger (service **and** direct SQL) · deactivate a system ledger ·
clear `system_code` · delete a system group · delete a group with ledgers ·
delete a group with sub-groups by direct SQL · move a posted account across
natures · delete a posted account.

### Accounting-correctness results
Fresh company balances at 0 = 0 (A-8, a legitimate *report*, unlike A-1's
degenerate voucher) · 13 postings balance · reversal nets to zero · cross-year
reversal leaves each year internally balanced (A-10) · deactivated account with a
balance still reported and flagged inactive (A-6) · rename does not create a
duplicate (R-4).

## 7. Carried items

| Item | Status |
|---|---|
| Table ownership — every non-kernel table has one owner | ✅ **done**, `TableOwnershipTest` |
| Module smuggling end to end | ⏳ **still carried** — needs a real optional module; none exists yet |
| Downgrade SHA-256 build comparison | ⏳ **still carried** — needs two builds differing by a module |

Both remaining items need a genuine optional feature module. P7 is the first
phase that creates one.

## 8. P6-4 DEVIATION — recorded, not erased

> **P6-4 deviation: the minimal trial-balance JavaFX screen is DEFERRED because
> the current CI/runtime environment cannot execute JavaFX/TestFX UI tests.**

P6-4 was approved as a product requirement and remains outstanding. What was
delivered: the governance use cases and the trial-balance *computation*, fully
tested. What was not: any JavaFX screen.

Adding one here would create an **untested accounting UI surface** — UI tests
cannot run in this environment (3 skipped; no display; TestFX/Monocle fails on
JavaFX 17).

**Prerequisite for closing this deviation:** working UI test infrastructure
(Xvfb in CI, or a TestFX/Monocle combination that runs on JavaFX 17). The screen
should be implemented only once its tests can execute — not to satisfy a
checkbox.

Tracked as **P6-4-UI** in `OPEN_DECISIONS.md`.

## 9. Files

**Created (8):** `AccountNature`, `LedgerAccount`, `TrialBalanceRow`,
`TrialBalance`, `ChartOfAccounts`, `ChartException`, `PHASE_6_DESIGN.md`,
`PHASE_6_REPORT.md`
**Test files created (5):** `AccountNatureTest`, `LedgerAccountTest`,
`ChartFixture`, `ChartGovernanceIT`, `BookkeepingCycleIT`, `TableOwnershipTest`
**Modified (6):** `Schemas.java` (migration 3), `CompanyProvisioning.java`,
`module-info.java` (accounting exports, module-api `uses`),
`SchemaMigrationSafetyIT`, `PostingTestFixture`, `OPEN_DECISIONS.md`

## 10. Status

Reference app untouched · nothing staged, committed or pushed.

### Exit verification — from the packaged artifact, not the reactor

```
clean build → 373 passing, 3 skipped
reinstall all artifacts → identical results (no stale-artifact skew)
package → 99 MB image, 70 MB .deb
```

Checked **inside the linked runtime image**:

| Check | Result |
|---|---|
| `accubook.accounting` in the image | ✅ (after fixing §3.3) |
| P6 chart + P5 posting classes loadable | ✅ |
| `module-api` declares `uses FeatureModule` | ✅ true |
| `ModuleRegistry.discover()` | ✅ `[]` — returns empty, does not throw |
| `posting` exported | ✅ true |
| `posting.internal` exported | ✅ **false** — the seal holds in the shipped artifact |

## 11. Remaining risks

| Item | Phase |
|---|---|
| No UI screen — trial balance is computed but not displayed | P6 follow-up, gated on UI test environment |
| Module smuggling + downgrade SHA-256 tests | P7 |
| Opening balances are stored but not yet applied to the trial balance | P11 (year-end owns opening/closing) |
| B-1 still open | before P7/P8 |

**No decision belonging to B-1, B-2, B-4 or B-5 was made.**

---

## Addendum 2026-10-09 — P6-4 deviation closed

The deferred trial-balance screen was built once UI tests could run (Xvfb). See
`OPEN_DECISIONS.md` P6-4-UI and `PHASE_7_REPORT.md`. Two related defects were
found on the way: the UI tests had never run (Monocle forced even on a display),
and `CompanyService.ledgerBalances` repeated the §3.1 join defect for draft
vouchers. Both fixed.

