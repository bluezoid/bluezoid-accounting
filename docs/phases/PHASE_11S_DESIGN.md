# Phase 11S Design — Screens

**Status:** APPROVED 2026-10-24 (S-1…S-4 as recommended)
**Inserted before Phase 12** at the user's request (2026-10-24).

---

## 1. What exists, and what it lacks
- Phases 5–11 built and tested the engine: posting core, chart, parties,
  inventory and costing, sales and purchase documents, settlements, GST and
  TDS, returns, e-invoice data, banking, year-end, statements, registers,
  exports. **841 tests**, none of them through a screen beyond two.
- The UI is the Phase 1 skeleton: one window listing companies, a create
  button, and the trial balance (P6-4). A customer can do nothing else.
- `FeatureModule.navigationEntries()` was planned in P4 (§5) and never built;
  the per-feature `accubook-ui-*` modules in `DESKTOP_ARCHITECTURE.md` do not
  exist.
- The reference is a web app (Next.js): its pages give the feature list (about
  60 pages), not the desktop interaction model.

## 2. Architecture

### 2.1 Modules
```
accubook-ui-api           Screen SPI, ScreenContext, shared controls (no feature code)
accubook-ui-shell         main window, navigation, kernel screens (company, chart,
                          vouchers, reports, year-end, GST setup, settings),
                          dormant-records viewer
accubook-ui-<feature>     one per feature module: inventory, sales-invoicing,
                          purchase-billing, sales-orders, purchase-orders,
                          taxation-returns, banking
```
- The shell **never requires** a feature or a feature's UI (P4 rule): it finds
  `ScreenProvider` services through `ServiceLoader`, as it already finds
  `FeatureModule`s.
- A `ui-<feature>` module requires its feature and `ui-api`, nothing else.
- **Packaging:** `build.sh` includes `accubook-ui-X` exactly when it includes
  `accubook-X`. A Basic image therefore contains neither the banking engine
  nor its screens. Tested by building both plans and listing the image modules.

### 2.2 The contract
```java
interface ScreenProvider {                 // ServiceLoader, one per ui module
    ModuleId module();
    List<NavigationEntry> entries();       // section, title, order, shortcut, factory
}
interface ScreenContext {                   // what a screen may use
    ModuleTransactions transactions();      // guarded; refuses calls on the FX thread
    EntitlementGuard guard();  Clock clock();  Supplier<RulePack> rules();
    ModuleState state();                    // ACTIVE or READ_ONLY for this screen
    <T> void background(String what, Work<T> work, Consumer<T> onDone);
    void open(String screenId, Map<String, String> args);   // cross-links
    void notify(String message);  void fail(String what, Throwable error);
}
```
- **The UI thread never touches the database** (architecture rule): the
  context's `transactions()` throws if called on the JavaFX thread. Enforced by
  code and a test, not by convention.
- **Entitlement shown, not hidden:** READ_ONLY opens the screen with editing
  disabled and a banner saying why (`UPGRADE_DOWNGRADE.md`); DORMANT shows the
  module's `historicalViews()` in the generic read-only viewer; ABSENT shows
  nothing. The persistence guard still refuses writes whatever the screen does.
- Errors from the engine (`DocumentException`, `YearEndException`, …) already
  carry plain-language messages; screens show them as they are, never a stack
  trace, and log the detail.

### 2.3 Shared controls (in `ui-api`)
`AmountField` (BigDecimal only, Indian grouping 1,23,456.78, never a double),
`QuantityField`, `DateField` (dd-mm-yyyy, accepts 1/4, 01-04-26, today `t`),
`LookupField` (type-to-search ledgers, parties, items; create-new inline),
`EntryGrid` (line entry with keyboard), `ReportTableView` (renders any
`Table`, export buttons), `ConfirmDialog`, `ReasonDialog` (unmatch, reopen,
cancel), read-only banner. `AmountFormat` moves here from the shell.

### 2.4 Window
One main window: company bar (company, fiscal year, GST status), left
navigation by section (Masters, Transactions, Reports, GST, Banking, Year-end,
Settings), tabbed work area, status bar. A company picker on start, as now.

## 3. Sub-phases

| | Scope |
|---|---|
| **S1** ✅ 2026-10-24 | `ui-api`, main window, navigation from providers, company create/open with identity, fiscal start month and GST registration; FX-thread guard; packaging rule. *(Dormant viewer moved to Phase 14 - see §6.)* |
| **S2** ✅ 2026-10-27 | Masters: groups and ledgers, parties, items/units/warehouses/opening stock, bank accounts, opening voucher, opening bills |
| **S3** | Vouchers: journal, payment, receipt, contra; list, view, reverse |
| **S4** | Sales invoices, credit notes, purchase bills, debit notes; receipts and payments with allocation; quotations and orders; print/PDF of a tax invoice |
| **S5** | Reports: trial balance, P&L, balance sheet, cash flow, day book, ledger, registers, outstanding and ageing; export and print |
| **S6** | GST returns (build, review, export JSON, mark filed, unlock), 2B import and reconciliation, TDS deposits, e-invoice/e-way data; banking (import, match, BRS); year-end (close, reopen); settings (rule packs) |

**Exit:** a scripted UI test, on Xvfb, creates a company, registers GST, adds
ledgers, a party and an item, posts an invoice and its receipt, a purchase
bill and its payment, reconciles a bank statement, exports GSTR-1 and GSTR-3B,
and closes the year — **only through the screens** — and the trial balance on
screen matches the engine's.

## 4. Testing
- Unit tests for every control (parsing, formatting, keyboard).
- TestFX tests per screen on Xvfb (`-Daccubook.ui.tests=true`), against a real
  company database; skipped without a display, as now.
- A packaging test: Basic image has no banking or returns screens;
  Professional has them.

## 5. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **S-1** | Interaction model | **Keyboard-first, mouse fully supported.** Enter moves to the next field and saves at the end of a voucher; Esc goes back; Ctrl+S saves; function keys open voucher types as Tally users expect (F4 contra, F5 payment, F6 receipt, F7 journal, F8 sales, F9 purchase); type-to-search in every lookup. Most Indian accountants come from Tally. |
| **S-2** | Print and PDF | **Bundle Apache PDFBox** (Apache-2.0, about 3 MB) to write invoices and reports as PDF files, which are then printed or attached; an A4 GST tax invoice (Rule 46 fields) as the one layout in v1. Without it, output depends on each OS's print-to-PDF and a saved PDF is not guaranteed on Linux. |
| **S-3** | Users and login | **No login in v1**: one user per installation; the data is protected by the operating system account (I-15). A password without encryption at rest only looks like protection. Local users with roles can follow. |
| **S-4** | Language and formats | **English, Indian formats:** lakh/crore grouping, ₹, dd-mm-yyyy; light theme. Hindi and other languages later, with text kept in resource bundles from the start. |

## 6. Progress and findings

**S1 complete 2026-10-24** — 900 tests, 0 failing, with UI on Xvfb.
- `accubook-ui-api`: `AmountInput`/`DateInput` (never round; day first; year
  from the fiscal year), `AmountField`, `DateField`, `Keyboard.enterAdvances`,
  the screen contract, `UiThreadCheckedTransactions`.
- Shell: company picker (fiscal start month and first date, D-32), main window
  (navigation, tabs, shortcuts, Ctrl+W, read-only banner, change company),
  `ScreenCatalogue`, `ShellScreenContext`, trial balance as a tab, company and
  GST settings screen.
- `build.sh`: `accubook-ui-X` packaged exactly with `accubook-X`; Basic image
  built and launched. First tested for real when a feature UI module exists (S2).

**Findings**
- **Dormant viewer cannot work as built.** Each module declares its
  `historicalViews()` in its own code, so a DORMANT module - code absent - has
  no view definitions in the build, and the P4 viewer would show nothing. The
  definitions must live in the base build (the P4 decision says so; the code
  did not follow it). Moved to **Phase 14**, which owns dormant-module viewers.
- The trial balance relied on a ComboBox action event that does not reliably
  fire while a new tab is built; it sometimes never loaded. Fixed (load on the
  value) and found only because the shell test became a tab test.

## 7. S2 — masters and opening balances (decisions S2-1…S2-5, 2026-10-27)

### Engine gaps found
- `ChartOfAccounts` cannot create a ledger or a group (tests insert them with
  SQL), and takes a raw `Database`, which screens cannot reach.
- `FiscalYears.postOpening` refuses a second opening voucher even after the
  first is reversed (it counts every OPENING event), so a mistaken opening
  could never be corrected - the message tells the user to do what the code
  forbids.
- Party opening bills (P11-1) do not exist in the document engine.

### Design
| Step | Change |
|---|---|
| S2a | `ChartOfAccounts(ModuleTransactions)`; `groups()`; `createGroup` (sub-groups only, S2-1); `createLedger` (refused under Sundry Debtors/Creditors, S2-2) |
| S2b | `FiscalYears.replaceOpening(balances, reason)` (S2-3): the live opening voucher reversed, a new one posted, reason in the event; only while the first year is open. The opening voucher refuses party ledgers (their openings are bills) and checks Stock-in-Hand against the item-wise opening stock (S2-5) |
| S2c | Migration 21: `sales_opening_bill`, `purchase_opening_bill` (party, number, bill date, due date, amount, voucher); allocation tables rebuilt so an allocation targets a document **or** an opening bill. A separate table, not a new document kind, so no GST return, register, TDS or e-invoice query can ever count an opening bill as a supply. Each bill posts Dr party / Cr Opening Balance Equity (sales) or the reverse, dated the first day; the bill keeps its own date for ageing |
| S2d | Settlement engine: allocate to opening bills; outstanding, ageing and unallocated credits include them |
| S2e | Screens: groups and ledgers; parties; units, categories, items, warehouses, opening stock (`accubook-ui-inventory`, the first feature UI module - packaging tested); bank accounts (`accubook-ui-banking`); opening balances with opening bills |

### S2 complete 2026-10-27
**Engine:** `ChartOfAccounts.createGroup/createLedger/groups` over
`ModuleTransactions`; `FiscalYears.replaceOpening/openingPosition` (the
opening could never be corrected before); migration 21 and `OpeningBills`
(sales and purchase), with the settlement engine allocating to them and
counting them in outstanding, ageing and limits; `Banking.openAccount/accounts`.

**Screens:** chart of accounts, customers and vendors (GSTIN/PAN checked),
opening balances (kernel); items with GST and units, warehouses and opening
stock (`accubook-ui-inventory`); bank accounts (`accubook-ui-banking`);
opening invoices and bills (`accubook-ui-trade`).

**Module layout decided in S2:** sales and purchase screens are one
side-parameterised module, `accubook-ui-trade`, with two providers keyed to
sales-invoicing and purchase-billing - the same code for both sides, each
showing with its own module. It is packaged with trade, in every build.
Feature screen tests live in `accubook-integration-tests` (classpath), built
through the provider as the shell builds them.

**Found and fixed**
- The opening voucher could not be replaced after a reversal (S2-3).
- A bank account's ledger was left behind when its details were refused.
- `AmountInput` ignored commas wherever they fell: "900001,00,000" read as
  nine hundred crore. Grouping must now be Indian or western.
- Fields did not select their text on focus, so typing went before the old
  value.
- The company settings screen read the rule pack on the UI thread, which the
  guard cannot see (installation database); `rules()` is now documented as
  background-only.
- A shortcut test pressed a key before its window had focus.

**Limits**
- A credit or debit note always corrects a real document, so none can be
  raised against an opening bill; its unused credit can be applied to one.
- Opening-stock entries cannot be edited on screen; a wrong one is corrected
  by an adjustment movement (stock movements are never edited).
- Income and expense ledgers are not offered on the opening screen: books
  begin on a fiscal year's first day, when they are nil.
