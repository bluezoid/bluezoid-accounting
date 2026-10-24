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
| **S1** | `ui-api`, main window, navigation from providers, company create/open with identity, fiscal start month and GST registration; FX-thread guard; packaging rule; dormant viewer |
| **S2** | Masters: groups and ledgers, parties, items/units/warehouses/opening stock, bank accounts, opening voucher |
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
