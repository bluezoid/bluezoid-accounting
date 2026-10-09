# Phase 7 Report — Parties, Inventory Masters, Sales & Purchase Documents

**Status:** IN PROGRESS — P7a ✅ complete · P7b–P7e not started
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
