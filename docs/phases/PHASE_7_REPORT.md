# Phase 7 Report — Parties, Inventory Masters, Sales & Purchase Documents

**Status:** IN PROGRESS — P7a ✅ · P6-4 screen ✅ · P7b ✅ · **P7c ✅** · P7d–P7e not started
**Design:** `PHASE_7_DESIGN.md` (decisions P7-1…P7-6 approved 2026-10-09)

---

## P7a — Parties, module storage port, quantity scale ✅ (2026-10-09)

**Tests:** 431 passing · 0 failing · 3 UI skipped · `BUILD SUCCESS`
**Baseline before P7a:** 373 (+58)

### Implemented

| Item | Where |
|---|---|
| `Scale.QUANTITY(4, "_e4")` — approved under B-3, never added | `accubook-platform` |
| `ModuleTransactions` port: guarded writes + unwritable reads, so feature modules never `requires accubook.persistence` | `accubook-module-api`, impl `accubook-app` `DatabaseModuleTransactions` |
| Migration 4: `party` table, ledger link immutable, ledger↔party mirror triggers, party ledger undeletable | `Schemas` |
| `Parties` service, `Party`, `PartyRole`, `PartyProfile`, `PartyException` | `accubook-accounting` `…accounting.party` |
| Chart refuses moving a party's ledger | `ChartOfAccounts.moveToGroup` |

### Defect found in our own code — the write guard failed open

`GuardedStatements` classified a statement by its **first word only**. Through a
guarded unit of work these all passed unchecked:

| Bypass | Example |
|---|---|
| leading comment | `/* x */ INSERT INTO stock …` |
| CTE write | `WITH n AS (…) INSERT INTO stock …` |
| DDL | `DROP TABLE stock` |
| escape hatches | `ATTACH`, mid-work `COMMIT`, `PRAGMA query_only = 0`, `PRAGMA foreign_keys = OFF` |

Fixed: comments stripped before classification, CTEs searched for their write,
statements a guarded unit of work may never run are refused, and **unrecognised
verbs are refused** so the guard fails closed. Ten test fixtures that created
tables through the guarded path were moved to `inUnguardedTransaction`, the path
migrations use. No production code issued any of these statements.

This matters now because P7b gives the guard its first real job.

### Finding — the guard is not installed in production

`Database` defaults to `WriteAuthorization.permitAll()`, and nothing outside
tests calls `authorizeWith(ModuleWriteAuthorization)`. Harmless while no
optional module exists; **P7b must wire it in the composition root**.

### Reference defects recorded

| ID | Finding |
|---|---|
| **R-5** | Party ledgers bound by display name (`upsertLedgerByName`, `ON CONFLICT (name) … RETURNING id`): a party named like an existing ledger is silently attached to it. Added to `WEB_APP_DEFECT_REPORT.md`. |
| R-6 | Settlement is 1:1 (`Receipt.invoiceId`). → P7d |
| R-7 | `amountPaid`/`amountDue` cached and recomputed by hand. → P7d |
| R-8 | `BOTH` parties silently treated as customers. → fixed by P7-2 |

### Tests added (+58, per suite measured against the baseline commit)

| Suite | Count |
|---|--:|
| `GuardedStatementsTest` (comments, CTEs, forbidden statements) | +22 |
| `WriteGuardTest` (bypass attempts end to end) | +5 |
| `MoneyMapperTest` (quantity test, plus an existing per-`Scale` test now covering `QUANTITY`) | +2 |
| `DatabaseModuleTransactionsTest` | 5 |
| `PartyRoleTest` | 5 |
| `PartiesIT` | 19 |
| `SchemaMigrationSafetyIT` — future-migration numbering made relative | (changed) |

### Not done / risks
- **Master-data changes are not audited.** Neither chart governance (P6) nor
  parties write `audit_log`; only postings do. The audit writer is sealed inside
  the posting package. Recommend a kernel master-data audit path before P16.
- GSTIN / PAN / state code are stored but not validated — P9.
- No UI.

---

## Carried from P6 — trial balance screen (P6-4-UI) ✅ (2026-10-09)

**Tests:** 446 passing · 0 failing · 9 UI skipped (default build) ·
**455 passing, 0 failing with UI tests on Xvfb** · UI suite stable across 4 runs.

### What was wrong with the UI tests
They had never run. `accubook-ui-shell/pom.xml` forced Monocle headless even
on a real display, and Monocle fails on JavaFX 17, so the documented
`DISPLAY=:99 mvn verify -Daccubook.ui.tests=true` could not have passed. Once
they ran, one test failed on a button id renamed in an earlier phase.
Fixed: GTK glass on Xvfb, software rendering, Monocle removed.

### Delivered
| Item | Where |
|---|---|
| `TrialBalanceQuery` — the P6 SQL unchanged, on a caller-supplied connection; `FiscalYear`; default-year rule | `accubook-accounting` |
| `CompanyService.readOpenCompany` — guarded `query_only` read for callers outside the service | `accubook-app` |
| `AmountFormat` — Indian grouping, never rounds | `accubook-ui-shell` |
| `TrialBalanceView` + shell button opens it in its own window | `accubook-ui-shell` |

### Defect found — `CompanyService.ledgerBalances` counted draft vouchers
Same class as P6 §3.1: posted filter on a `LEFT JOIN voucher` while `SUM` read
every entry. Latent (test-only caller; nothing writes DRAFT vouchers yet).
Reproduced by a test (a DRAFT voucher showed ₹100 balances), then fixed.

### Packaged-artifact verification
`./build.sh --plan basic` → 70 MB `.deb`. Image contains
`accubook.accounting`; `accubook.ui.shell` requires it. The linked runtime
started under Xvfb against a fresh data directory with no exception and created
`app.db`.

### Risks
- **No CI pipeline exists** in the repository — UI tests run only when someone
  runs the `xvfb-run` command.
- The end-to-end UI test grants the test JVM `--add-reads
  accubook.ui.shell=accubook.persistence` to seed data; the shell module itself
  does not read persistence.

---

## P7b — Inventory module, enforcement in production ✅ (2026-10-09)

**Tests:** **501 passing · 0 failing · 0 skipped** with UI tests on Xvfb ·
default build 492 passing, 9 UI skipped · `BUILD SUCCESS`
**Before P7b:** 446 (+46 non-UI)

### Implemented

| Item | Where |
|---|---|
| Guard installed on **every company CompanyService opens**, from a module registry + state provider supplied by the composition root | `CompanyService` |
| `openCompanyTransactions()` — the guarded port feature use cases get | `CompanyService` |
| Launcher: `ModuleRegistry.discover()` + `DevelopmentEntitlement`; **release channel refuses to start** (P7-7) | `accubook-launcher` |
| `build.sh --release` — channel baked into the installer and the manifest | `build.sh` |
| Migration 5: `unit`, `item_category`, `item`, `warehouse`, `stock`, `stock_movement` | `Schemas` |
| **`accubook-inventory`** — first optional module; requires only `module-api` + `platform` | new Maven/JPMS module |
| `Inventory` use cases + `StockWriter` (applies a movement in a caller's transaction, for P7c) | `accubook-inventory` |
| `TableOwners` — ownership of every shipped table, independent of what is installed | `accubook-app` |
| `HistoricalView.Kind.QUANTITY` | `accubook-module-api` |

### Enforced by the schema itself
Stock never negative (CHECK) · movement direction per type (CHECK) · movement
log append-only · services have no stock · an item's kind and unit fixed once it
has moved · names unique ignoring case.

### Defect found in our own design — absent modules' tables were writable

Ownership came only from **installed** modules. The schema is universal, so a
BASIC build has the inventory tables but not the module: they had no owner, the
guard treated them as kernel, and anything could write them. Fixed with
`TableOwners`, consulted first; kernel tables can no longer be gated by a module
claiming one; a test asserts every migrated table is classified exactly once.
The PRO→BASIC→PRO test fails without the fix.

### Carried P4 tests — done
- **Smuggling:** inventory discovered, real BASIC `Entitlement` → `READ_ONLY`,
  refused at use case and guard.
- **Downgrade:** history readable and unwritable without the module; the company
  file's **SHA-256 is unchanged** by opening it in the smaller build; PRO again
  finds it intact and writable.

### Verification that the tests can fail
| Mutation | Result |
|---|---|
| guard not installed on open | 2 of 3 enforcement tests fail |
| decrement guard removed | message test fails; schema CHECK still prevents negative stock |
| use-case entitlement check removed | both entitlement tests fail |
| ownership catalogue removed | downgrade test fails |

### Packaged artifacts
| Build | Inventory in image | Discovered at runtime | Launch |
|---|:--:|:--:|---|
| `--plan professional` | ✅ | ✅ `[inventory]` | starts, dev warning |
| `--plan basic` | ❌ | `[]` | starts, dev warning |
| `--plan basic --release` | — | — | **refuses before creating any data** |

### Tests added
`CompanyEnforcementIT` 3 · `LicenceModeTest` 3 · `InventorySchemaConstraintIT` 9 ·
`TableOwnersIT` 4 · `InventoryModuleTest` 6 · `InventoryIT` 19 ·
`InventoryPlanChangeIT` 2.

### Risks / not done
- **Development entitlement** activates every packaged module without a
  licence. Safe only because a release build refuses to start; replaced when
  activation is built (P13).
- A table neither catalogued nor claimed is still permitted as kernel — bounded
  by the classification test, not eliminated.
- Concurrency test proves the invariant under contention but, as in P5, does not
  isolate the guarded UPDATE (writers are serialised); the UPDATE and the CHECK
  are tested directly.
- No inventory screens; no default warehouse or units are seeded (UX for P7c).
- Movement dates are not checked against fiscal years or closed periods (P11).

---

## P7c — Sales and purchase documents ✅ (2026-10-09)

**Tests:** **567 passing · 0 failing · 0 skipped** with UI on Xvfb · `BUILD SUCCESS`
**Before P7c:** 501 (+66)

**Exit criterion met in part:** a credit note **reduces** revenue and
receivables — asserted as a property over random amounts and end to end.
"Invoice → receipt → statement reconciles" needs receipts: P7d.

### Implemented

| Item | Where |
|---|---|
| Inactive/unknown accounts refused for new postings (P6 §5.2, never implemented) | `VoucherWriter` |
| `TransactionalPosting` — post/reverse inside the caller's transaction, no `Database` needed; `PostingService` delegates | `accubook-accounting` |
| `StockPort` service interface; inventory provides it | `module-api`, `accubook-inventory` |
| Migration 6: `sales_*`, `purchase_*` documents, lines, tax lines (empty, P7-1); Sales and Purchases system ledgers seeded for existing companies | `Schemas` |
| Provisioning seeds Sales and Purchases for new companies | `CompanyProvisioning` |
| **`accubook-trade`** library: `DocumentKind`, `LineMath`, `RoundOff`, `DocumentNumbers`, `PostingPlan`, `DocumentEngine` | new module |
| **`accubook-sales-invoicing`**, **`accubook-purchase-billing`** feature modules | new modules |

### Reference defects recorded
R-9 invoice series from today's date with April hard-coded · **R-10 invoice
numbers are 17 characters, Rule 46(b) allows 16** · R-11 unrounded line amounts.

### Defects found in our own code
- **Posting to an inactive account was allowed** — designed in P6, never built.
  A deactivated customer could still be invoiced. Fixed; reversals stay allowed.
- Three P6 tests pinned "4 seeded ledgers"; now relative to the chart.

### Verification that the tests can fail
| Mutation | Caught by |
|---|---|
| credit note posts like an invoice (the reference's D-23) | `DocumentKindTest`, two property tests |
| note limit removed | `DocumentEngineIT$CreditNotes.limited` |

### Packaged artifacts
| Plan | Image | Discovered | Stock port |
|---|---|---|:--:|
| basic | trade, sales-invoicing, purchase-billing | `[purchase-billing, sales-invoicing]` | ✗ — free-text lines only |
| professional | + inventory | `[inventory, purchase-billing, sales-invoicing]` | ✓ |

### Risks / not done
- **No screens** for parties, items or documents.
- **Kernel services still need a `Database`** (`Parties`, `ChartOfAccounts`), which
  only `CompanyService` holds. Tests use a second handle on the file. Before the
  document UI, these must be reachable from the shell the way posting now is
  (`TransactionalPosting`) — a composition task for the UI work.
- No GST (P7-1, P9) and no TDS (P9).
- Credit notes are limited by **amount**, not by quantity per line: a note may
  return more units of an item than the invoice sold, as long as the value fits.
  Recommend a per-item quantity check in P7d or P8 (record as a decision then).
- A document date in a closed year is refused, but nothing closes years until P11.

