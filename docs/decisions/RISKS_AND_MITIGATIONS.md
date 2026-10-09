# Risks & Mitigations

Status: LIVE. Scored Likelihood × Impact. Ordered by overall severity.

---

## R-1 — Scope is far larger than "port a web app" (near-certain, severe)

The reference is ~300k lines of TS, 74 models, 122 API routes, ~18k lines of
services, covering accounting, inventory, manufacturing, sales, purchase, GST
(5 return types + portal formats), TDS/TCS, banking, payroll, HR, approvals,
OCR and Tally import. Reproducing that in JavaFX **plus** licensing, subscription,
modular builds, backup, an admin portal, updates and cross-platform packaging is
a multi-year effort at small-team scale.

Compounding it: several features that *appear* to exist are not actually
implemented (D-9, D-17) — cost centres, projects, budgets, batch consumption,
leave balances, SO→invoice conversion, expense-claim accounting. A schema-driven
estimate would badly undercount.

**Mitigation:** phase ruthlessly (see roadmap); define a genuinely minimal v1
(core accounting + GST + sales/purchase); treat inventory/manufacturing/payroll
as later phases; get the plan/module matrix (C-2) decided early so the first
sellable build is small. **Do not attempt feature parity in v1.**

## R-2 — Money precision errors (moderate likelihood, catastrophic impact)

SQLite silently stores `DECIMAL(18,4)` as floating-point `real` — proven
experimentally: `0.1 + 0.2 != 0.3` in a column *declared* decimal. Books that
look right and reconcile wrong are the worst possible failure in an accounting
product, and the corruption is silent and cumulative.

**Mitigation:** scaled INTEGER storage; `BigDecimal` everywhere in code; **all
tables STRICT** so a float cannot even be written; conversion confined to one
mapper; column names carry the encoding (`*_minor`); property-based tests that
round-trip and assert exactness; trial-balance-equals-zero asserted after every
migration and backup.

## R-3 — Statutory tax rules drift out of date (near-certain, severe)

Rates, thresholds, HSN data, return formats and due dates change annually. The
reference hardcodes them in TypeScript with **no effective-date dimension** —
recomputing a prior year today would apply today's rates. On desktop, customers
may run an old version for years.

**Mitigation:** statutory rules become **versioned, effective-dated data**, not
constants; a signed rule-pack channel independent of app updates; historical
periods always compute with the rules in force then. Note D-5: return *formats*
may not be fully data-driven — that limit must be understood early.

## R-4 — Data loss during update or migration (low likelihood, catastrophic)

An update that corrupts a customer's books is an existential event for a
commercial accounting product.

**Mitigation:** verified backup before every migration (including a
trial-balance check, not just `integrity_check`); each migration step
transactional; automatic rollback on failure; refuse to open newer-schema files;
migrate per company file on open; test migration from every shipped version on
realistic data, including interrupted runs.

## R-5 — Reproducing reference defects into the new product (high likelihood, high impact)

21 defects are catalogued in DEFECTS_IN_REFERENCE_APP. Several are subtle enough
to port by accident — the `openingBalanceType` `DEBIT`/`DR` mismatch, the
attendance `WEEK_OFF`/`WEEKEND` mismatch, cash flow's name-matched cash ledgers,
the fabricated IRN, the unsafe SO/PO numbering.

**Mitigation:** the defects document is required reading before each domain is
implemented; each defect has an explicit reproduce/don't-reproduce decision; the
"do not reproduce" ones get a regression test asserting the *corrected* behaviour.

## R-6 — Fabricated IRN is a compliance misstatement (present in reference, high impact)

The live e-invoice route generates a **fake IRN** with a 32-bit hash plus
`Date.now()` — despite a comment claiming SHA-256 — and stamps
`eInvoiceStatus: "GENERATED"` with no NIC round-trip.

**Mitigation:** never fabricate an IRN. Build the payload (the good
`einvoice.ts` implementation does this properly); treat real IRN generation as an
integration requiring NIC credentials; until then show status "not generated".
**Also worth telling the web-app owners** — this affects live users now.

## R-7 — Licensing locks out a paying customer (moderate likelihood, severe)

Over-strict machine binding or clock checks blocking a legitimate accountant
mid-filing is a support and reputation disaster.

**Mitigation:** fuzzy K-of-N fingerprint matching with automatic silent re-bind;
generous grace period; clock skew tolerance; **export always works, even
unlicensed**; a documented offline renewal path; a manual override support can
apply. Licensing failure must never mean data loss — worst case is read-only.

## R-8 — Signing key compromise (low likelihood, catastrophic)

The Ed25519 private key can forge every licence in the field. Older app versions
embed only the old public key, so rotation may be impossible after the fact.

**Mitigation:** key in HSM/KMS, never in config; isolated signing service;
**embed two valid public keys from v1** so rotation is possible at all; audited
access.

## R-9 — Customer loses their backup passphrase (moderate likelihood, severe *for them*)

Customer-only key custody (required by brief §16) means we genuinely cannot help.

**Mitigation:** unavoidable warning at setup with explicit acknowledgement;
printed recovery sheet; repeated honest messaging. This is a *product* risk
accepted deliberately in exchange for the privacy guarantee — C-6 must be signed
off knowingly.

## R-10 — Cross-platform packaging friction (high likelihood, moderate impact)

Three OSes, two CPU architectures (Apple Silicon and Intel; ARM Linux),
code-signing certificates, macOS notarization, antivirus false positives on
unsigned binaries, and a native SQLite JDBC library that must be bundled per
platform.

**Mitigation:** stand up the packaging pipeline **early** (Phase 1), not at the
end — packaging problems discovered late are schedule-killers. Start certificate
procurement immediately (D-2, long lead time). Test on real machines, not just CI.

## R-11 — JavaFX UI effort is underestimated (high likelihood, moderate impact)

The reference has a large React/Radix UI surface. JavaFX has no equivalent
component ecosystem; complex data grids, filtering and printing are hand-built.
The UI is also the **least-tested** part of the reference (no component tests;
`src/app` excluded from coverage), so there is no oracle for its behaviour.

**Mitigation:** invest in a reusable component library early (data grid, entry
form, date/number fields with correct decimal handling); build one module's UI
end-to-end first to calibrate; treat printing/PDF as its own work item.

## R-12 — Single-writer contention degrades UX (moderate likelihood, moderate)

SQLite allows one writer. Background backup or import colliding with interactive
posting yields `SQLITE_BUSY` at the worst moment.

**Mitigation:** one serialized writer path; separate read pool; WAL;
`busy_timeout`; background work yields to interactive work; never block the
JavaFX UI thread on the database.

## R-13 — Two instances open the same company file (moderate likelihood, high impact)

Users double-click the icon twice. SQLite survives, but cached state and
denormalized balances diverge.

**Mitigation:** app-level single-instance lock plus a per-file lock; prefer
computing balances over caching them (A-10).

## R-14 — Network filesystem hosting of company files (moderate likelihood, high impact)

Accountants will put data on a shared drive so "everyone can see it". WAL relies
on shared memory and behaves badly on SMB/NFS — a genuine corruption path.

**Mitigation:** detect network paths at company-open, warn clearly, and refuse
or fall back to a safer journal mode; document a supported multi-user story
(or state plainly that there isn't one in v1).

## R-15 — Test coverage gaps in the reference mislead the port (moderate, moderate)

Well-tested: GST, TDS/TCS, payroll calc, permissions, posting, concurrency
(~646 tests). **Zero tests:** budgets, approvals, quotations, sales/purchase
orders, cost centres, projects, expense claims, attendance, leave, report
templates, notifications, audit log, multi-currency, GSTReturn persistence, all
master CRUD, and the entire UI.

**Mitigation:** treat untested areas as unspecified — read the code *and* the
user manual, and write tests defining intended behaviour rather than assuming the
reference is correct there. Multi-currency in particular is completely unpinned.

## R-16 — Concurrency semantics differ under SQLite (moderate, moderate)

The reference relies on Postgres behaviours: `ON CONFLICT` racing, constraint
violations **poisoning** the transaction, row-level serialization of counter
upserts. SQLite does **not** poison a transaction on constraint violation, and
serializes writers instead.

**Mitigation:** the 14 concurrency integration test files are the specification;
re-satisfy them under `SQLITE_BUSY` semantics; keep the conditional-claim idiom
(it remains correct and maps to JDBC row counts).
