---
name: project-state
description: Current phase, what is built, what is next, and the immediate blockers
metadata:
  type: project
---

# Project state — read this first

**Updated:** 2026-10-24

## Where we are
**Phases 0–6 COMPLETE. Phase 7 IN PROGRESS (started 2026-10-09).**
B-1 decided 2026-10-09: **periodic 5a** (target = traders/SMEs).
P7 design approved: `docs/phases/PHASE_7_DESIGN.md`, decisions P7-1…P7-6 all
as recommended (tax-ready no GST until P9 · one ledger per party · allocation
settlement · negative stock refused · invoice moves stock · sub-phases a→e).

Sub-phases: **P7a** parties + `ModuleTransactions` port + `Scale.QUANTITY` →
P7b inventory (first real FeatureModule, wire guard in prod, carried P4 tests) →
P7c invoices/bills/CN/DN → P7d receipts/payments/allocation/aging → P7e orders.

Baseline before P7 code: **373 passing, 0 failing, 3 UI skipped**.

Findings 2026-10-09: guard NOT wired in production (`Database` defaults to
permitAll; nothing calls `authorizeWith` outside tests) → fix in P7b.
New reference defects R-5 (party ledger bound by name), R-6 (1:1 settlement),
R-7 (cached amountPaid), R-8 (BOTH party silently AR).

**PHASE 7 COMPLETE · PHASE 8 COMPLETE (2026-10-10).**
P8 = periodic 5a valuation: `Costing` (inventory) replays an ITEM across all
warehouses from a date; valuation_entry/fifo_layer/fifo_consumption derived;
`Inventory.postValuation(date)` posts Dr Stock-in-Hand / Cr Change in Stock
(paise-rounded, P8-6); runs go STALE on backdated movements. Movements carry a
cost source (documents pass it) so inventory never reads document tables.

**PHASE 9 IN PROGRESS — P9a DONE (2026-10-10).** B-4 decided: per-line GST
half-up to paisa, totals/returns = sums of line figures. P9-2 item carries GST
slab (+cess), every line explicit category. P9-3 scope domestic+RCM+composition.
P9-4 signed rule packs (own dev keys; PRIVATE KEYS in ~/.accubook-dev/, never
committed; sign with `java tools/SignRulePack.java`). `accubook-tax` kernel:
RulePack (text format), SignedRulePack, RulePacks (app.db, re-verify on load),
Gstin (Mod-36), GstRegistrations (company migration 10).
Bundled pack 2025.10.1 UNVERIFIED — V-1 release gate (CA verification).
R-17 reference TDS table stale; GST 2.0 from 2025-09-22 (12/28 out, 40 in).
**P9b DONE (2026-10-12).** Decisions P9-5 (POS from party; counter sale =
company state), P9-6 (inclusive prices later), P9-7 (15 GST ledgers: GST_OUTPUT_*,
GST_INPUT_*, GST_RCM_* x CGST/SGST/UTGST/IGST/CESS), P9-8 (no document without a
gst_registration row; existing test fixtures register UNREGISTERED "27" from
2017-07-01). Migration 11 (item_tax, document GST basis/taxable/tax, line
category/rates/hsn, rebuilt tax_line, basis triggers). Scale.TAX_RATE (_e4 %).
Engine: Supplier<RulePack> in DocumentEngine + facades (convenience ctors use
RulePacks.bundledPack(); app must pass CompanyService::rules). Bundled pack now
**2025.10.2** (composition special states). Composition eligibility in
GstRegistrations. Fixed wasNull() ordering bug (party credit days).
OPEN: **P9-9** composition limit crossed mid-year (ask before P9d).

**P9c DONE (2026-10-12).** P9-10 TDS both ways + TCS shell (test pack only),
P9-11 paisa, P9-12 catch-up on annual crossing (R-18), P9-13 TDS_PAYABLE,
TDS_RECEIVABLE, TCS_PAYABLE. `Pan`, `Withholding` (tax). Migration 12:
withholding_entry (kernel), documents withholding_section/tds_e4/tcs_e4 and
GENERATED settle_e4 = total - tds + tcs (outstanding/allocations use it),
settlement tds_e4/tds_section. DraftRequest.withWithholding("194C");
SettlementRequest.withTds(amount, section). SettlementEngine has a
Supplier<RulePack> ctor too.
**P9d-1/2 DONE (2026-10-12).** P9-9 (composition invoice over limit refused),
P9-14 (filed return = JSON snapshot + sha256, locks period via triggers; unlock
with reason), P9-15 (portal JSON only), P9-16 (UQC warn -> OTH, missing HSN on
taxable line blocks filing). Pack 2025.10.3 (UQC list). Migration 13 (unit/line
uqc, gst_return). New Maven/JPMS module `accubook-taxation-returns`
(com.accubook.returns: GstReturns, Gstr1Builder, Gstr3bBuilder, *Json, Json,
ReturnPeriod). R-19/20/21 reference defects.
**P9d DONE (2026-10-13).** P9-17..24. Migrations 14 (gstr2b_import/document),
15 (registration composition_category, gst_return voucher links, composition
ledgers), 16 (tds_challan(+_entry), TDS_INTEREST ledger). Pack 2025.10.4
(composition rates). returns module: JsonReader, Gstr2bFile/Reconciler, Cmp08*,
Gstr9*, TdsInterest, TdsDeposits (26Q/16A as RPU data), uses posting core.
register(COMPOSITION) now needs a CompositionCategory. Tan API in tax module.
R-22..R-24.
**PHASE 9 COMPLETE (2026-10-24).** P9e: CompanyIdentity (tax), rule-pack
[eway-intra-state] (empty), migration 17 (einvoice_applicability,
einvoice_record, eway_bill_record), EInvoices (IRP 1.1 + EWB payloads, import
responses checked vs document, never generated), IRN 24h cancel window in
DocumentEngine. Decisions P9-25..28. Open: P9-29 (194C(6) transporters).
Exit criterion met in substance (134 GST + 40 TDS tests written, not ported).
**PHASE 10 COMPLETE (2026-10-24).** P10-1..5. Migration 18 (bank_account w/o
balance, bank_statement_import/line unique (ledger_id, identity), bank_match(+_line,
_entry) with active flags). accubook-banking: StatementParser (R-26 fix),
Banking (import, suggestions, confirm, confirmAllCertain, unmatch,
postFromLine, statement=BRS). R-26, R-27 in defect report.
**PHASE 11 COMPLETE (2026-10-24).** B-2 (closing voucher + lock), I-16
(reopen latest, reason), P11-1 (opening voucher), P11-2 (direct cash flow).
Migrations 19 (year triggers, fiscal_year_event, ledger opening cols retired),
20 (cash_flow_activity). accounting: `year/` FiscalYears, `report/`
FinancialStatements, Registers, Table, TableExport (CSV defuses = + - @).
Kernel postings use FiscalYears.ACCOUNTING. Order entries by v.rowid, not id.
**PHASE 11S (screens) IN PROGRESS** - user put screens before P12. Decided
I-14 (customer-only key), S-1..S-4 (keyboard-first, PDFBox, no login, English
+ Indian formats). Design `docs/phases/PHASE_11S_DESIGN.md` (S1..S6).
**S1 DONE**: `accubook-ui-api` (ScreenProvider/NavigationEntry/ScreenContext,
AmountField/DateField/Keyboard, UiThreadCheckedTransactions), shell =
CompanyPicker + MainWindow + ScreenCatalogue + ShellScreenContext +
KernelScreens (trial balance, company settings). Feature screens go in
`accubook-ui-<feature>` (build.sh packages ui-X with X). UI tests: build the
screen with a real `ShellScreenContext` (see CompanySettingsIT); create JavaFX
nodes in start(), never in field initialisers.
Dormant viewer moved to P14 (views live in module code - gap).
**S2 DONE (2026-10-27)**: S2-1..5 decided (sub-groups only; debtors via parties;
replace opening while yr1 open; opening BILLS per party; Stock-in-Hand computed
from OPENING stock movements). Migration 21 = sales/purchase_opening_bill +
_opening_allocation (own tables, NOT a document kind - keeps GST queries clean);
SettlementEngine routes an AllocationRequest whose id is an opening bill.
`FiscalYears.replaceOpening/openingPosition`, `ChartOfAccounts(ModuleTransactions)`
+ createGroup/createLedger, `Banking.openAccount/accounts`. UI modules:
`accubook-ui-inventory`, `accubook-ui-banking`, `accubook-ui-trade` (sales AND
purchase screens, side-parameterised, 2 providers; ALWAYS_INCLUDED). Feature
screen tests: `accubook-integration-tests/.../ui/FeatureScreenITBase` (built via
provider); kernel screen tests: `ScreenITBase` in ui-shell. CSS ids: no spaces.
`ScreenContext.rules()` reads storage - background only. **NEXT: S3 vouchers.**
Mutation runs: use `mvn clean` on the test module (IDE trap struck again).
NOTE: roadmap has NO phase for screens - biggest remaining item (told user).

Suite: **940+ passing, 0 failing** with UI on Xvfb (UI tests skipped by default).
Worktree trick to verify a committed intermediate state: `git worktree add
--detach <scratch> HEAD` then `mvn -o -q clean verify` there, then remove.
UI: `xvfb-run -a -s "-screen 0 1280x1024x24" mvn verify -Daccubook.ui.tests=true`.
No CI pipeline exists. Run FULL `mvn -o clean install` before every commit
(a partial run let 93e5654 break accounting tests).

IDE TRAP: VS Code's JDT compiles into target/ and overwrites Maven classes
when tests use modules their JPMS module doesn't read → random
NoClassDefFoundError with UNQUALIFIED class name. Cross-module tests go in
`accubook-integration-tests` (classpath, no module-info).

Key P7 structure: features get `ModuleTransactions` (guarded) via
`CompanyService.openCompanyTransactions()`; `Parties(ModuleTransactions)` works
too; posting from features via `TransactionalPosting`; stock via
`StockPort.installed()`; documents/settlement in `accubook-trade`
(`DocumentEngine`, `SettlementEngine`); allocations soft-removed with a date.
`TableOwners` must list every new table. `ChartOfAccounts` still needs Database.

### Built so far (8 Maven/JPMS modules)
platform (money, ids) · persistence (pragmas, BEGIN IMMEDIATE, migration runner,
locks, location refusal, **GuardedConnection** write guard) · module-api
(FeatureModule SPI, registry, ModuleState, PlanCatalogue) · licensing (P3) ·
accounting (**sealed PostingService** P5 + ChartOfAccounts / trial balance P6) ·
app (company service, provisioning, Schemas migrations 1–3,
ModuleWriteAuthorization) · ui-shell (P1 walking skeleton only) · launcher.

### Carried / deferred items
- **P6-4-UI** trial-balance JavaFX screen — deferred: no Xvfb on this machine,
  UI tests cannot run.
- Module-smuggling end-to-end test + downgrade SHA-256 build comparison — need
  the first real optional module (P7).
- Opening balances stored but not applied to trial balance → P11.
- B-1 doc inconsistency: OPEN_DECISIONS original B-1 text recommends perpetual;
  the 2026-09-03 brief recommends periodic-with-perpetual-quantities (5a).

### Phase 1–3 historical detail
## Roadmap: 16 phases (baseline, confirmed 2026-08-31)
P1 foundation+packaging · P2 database/migrations · P3 licensing · P4 modules +
entitlement enforcement · P5 posting core · P6 accounting modules · P7
sales/purchase + inventory masters · P8 perpetual costing · P9 taxation ·
P10 banking · P11 year-end + reporting · P12 backup · P13 admin portal ·
P14 upgrade/downgrade · P15 HR/payroll (deferred) · P16 production certification.

Full detail: `docs/ROADMAP.md`. Sequencing rationale:
`docs/decisions/ROADMAP_SEQUENCING_ANALYSIS.md`.

## Phase 1 delivered
6-module Maven/JPMS skeleton · `MoneyMapper` · per-company SQLite topology ·
pragmas + `BEGIN IMMEDIATE` + explicit rollback · migration runner with
backup/verify/rollback · JavaFX walking skeleton · jlink+jpackage with
parameterised signing.

**53 tests passing, 0 failing, 3 UI tests skipped (no display).**
Linux `.deb` built and verified end-to-end. Report: `docs/phases/PHASE_1_REPORT.md`.

## Phase 4 — BUILT (B-7, B-8 resolved 2026-09-01); design notes below
Authoritative documents: `docs/decisions/P4_MODULE_ARCHITECTURE.md`,
`P4_PLAN_MODULE_MATRIX.md`, `P4_UPGRADE_DOWNGRADE.md`, `P4_DEPENDENCY_RULES.md`.

Decided (pending sign-off): generic records viewer for DORMANT modules ·
manifest is **diagnostic-only and unsigned** · DORMANT and READ_ONLY stay
separate · reporting splits into core + extended · invert accounting↔banking ·
split sales into invoicing (inventory-optional) and orders (inventory-required).

**B-7 resolved 2026-09-01** (see OPEN_DECISIONS).

**Measured facts that constrain implementation:**
- `ServiceLoader` returns `[]` for an absent module — no crash. Verified in a
  jlink image.
- A jlink image **cannot** be extended by copying in a module jar.
- **But `--module-path` at launch CAN smuggle a module in** — so the manifest
  must never be an authorization source. Smuggling yields `READ_ONLY`.
- **JPMS is defeatable by reflection**, so the persistence guard is
  load-bearing, not belt-and-braces.
- Three non-UI write paths exist in the reference (scheduler, OCR importer,
  Tally importer) — a UI-only guard is insufficient.
- `InvoiceItem.itemId` is nullable but `SalesOrderItem.itemId` is not, so
  invoicing is inventory-optional while orders are not.

## The P4 contract (already built)
```java
ModuleState moduleState(String moduleId);  // ACTIVE | READ_ONLY | DORMANT | ABSENT
```
Lives in `accubook-module-api`, which has **no dependency on
`accubook-licensing`** — enforced by the build. Expiry, downgrade and suspension
all reach a module identically as `READ_ONLY`.

**Licensing constraints to preserve:**
- Verify **once at load** (Ed25519 costs ~1.5 ms) — never per operation.
- Fingerprint must not use DMI serials (root-only on Linux) or enumerate all
  MACs (`isVirtual()` reports `docker0` as false).
- **Two public keys ship from v1** — rotation cannot be retrofitted.
- Licensing failure must never touch company data; proven by SHA-256 comparison.

**For P16:** the release must fail if the development keys in `TrustedKeys` are
still compiled in.

## Deferred from Phase 2 (do not assume these exist)
- **User-facing backup/restore workflow** → P12. Migration-time backup *is*
  implemented and tested; the user-facing feature is not.
- **Audit hash-chaining logic** → P5. Table, columns and triggers exist.
- **Number allocation** → P5. `number_counter` table exists.

## Key SQLite hazard confirmed in P2
**`DROP TABLE` cascade-deletes children when `foreign_keys=ON`.** A naive table
rebuild silently destroys accounting data. Both the hazard and the safe
procedure (`foreign_keys=OFF` before `BEGIN`) are pinned by
`TableRebuildSafetyIT`.

## Open blockers (as of 2026-10-09)
Resolved: B-3, B-6, B-7, B-8, P5-1…P5-4, P6-1…P6-4.
Open: **B-1 gates P7/P8** · B-2 gates P11 · B-4 gates P9 · B-5 gates P15 ·
P6-4-UI (needs UI test infra). See `docs/decisions/OPEN_DECISIONS.md`.

## Long-lead item
**Code-signing certificates** (Windows Authenticode, Apple Developer ID +
notarization). Gates the P1 exit criteria and nothing else — start early.

## Not yet verified
Windows/macOS packaging (need CI agents) · `.rpm` (no `rpmbuild`) · UI tests
(need Xvfb) · code signing (no certificates).

## P5 — BUILT 2026-09-02 (P5-1…P5-4 approved); design findings below

Sealed posting core designed. Key facts:

- Reference has **ten** voucher-creating call sites, not one choke point.
- **F-2**: `applyLedgerEntries` validates entries handed to it, never that they
  match the separately-written voucher header (`post-wo-journal.ts:92`).
- **F-3**: API layer gates balance with a ₹0.01 epsilon (`closeEnough`) while
  the core is exact.
- **A-1**: `CHECK (total_debit = total_credit)` is satisfied by 0 = 0 — an
  entry-less voucher passes. Balanced is not the same as valid.
- **A-2**: `status` and `is_posted` are independent → CANCELLED + posted.
- Entitlement is checked on the **originating module**, never the ledger:
  ledger tables are kernel-owned so the trial balance survives any downgrade.
- Baseline is **281 tests**, not 196.

P5-1…P5-4 all approved and implemented.

