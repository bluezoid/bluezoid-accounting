# Daily Log

Records only work actually performed. Newest first.

---

## 2026-08-27 — Phase 0: Analysis, Research & Architecture

**Phase:** Architecture Research (Phase 0)
**Objective:** Analyse the existing AccuBook web application, research the
desktop/SQLite/licensing/packaging problem space, and produce the architecture
documentation and memory system. **No implementation** — per brief §38.

### Research performed

**Reference application analysis** (read-only; `git status` verified clean
before and after — no files in `/home/achyut-pal/Desktop/accubook` were modified).

Read directly:
- `prisma/schema.prisma` (core accounting models, enums census, header)
- `src/backend/utils/posting.ts` (the posting engine, in full)
- `src/backend/utils/money.ts` (in full)
- `src/backend/utils/tenant-scope.ts` (in full)
- `src/backend/database/client.ts` (in full)
- `src/backend/services/organization/provision.ts` (company bootstrap)
- `package.json`, test layout, API route inventory (122 routes)
- Postgres-specific surface scan (raw SQL, arrays, Json, `mode: insensitive`,
  Decimal precision census)

Seven parallel exploration agents covered: core accounting; sales/purchase/
parties/payments; inventory/manufacturing; Indian taxation; banking/payroll;
platform/multi-tenancy; and schema/migrations/test infrastructure.

**Original experiments run locally** (not assumptions):
- SQLite decimal behaviour — proved `DECIMAL(18,4)` is stored as `real` and that
  `0.1+0.2 != 0.3` in a column declared decimal, while INTEGER holds 18 digits
  exactly. This settled the money-storage decision with evidence.
- SQLite engine behaviour — confirmed `PRAGMA foreign_keys` defaults **OFF**,
  STRICT tables reject type mismatches, CHECK constraints enforce, and
  `ON CONFLICT DO UPDATE ... RETURNING` returns the winning row (so the
  reference's ledger-upsert pattern ports directly).
- Toolchain check: AWS Corretto 17.0.20.1, SQLite 3.50.6, Maven present,
  Gradle absent.

### Key findings

- The reference's **posting engine is excellent** — one choke point enforcing
  debits == credits, with comments documenting the real production bugs it
  fixed. Preserve it, but enforce structurally rather than by convention.
- Its **reporting layer has real defects** and needs redesign, not
  transliteration. Cash flow finds no cash at all on a default-provisioned org.
- **There is no year-end close.** Retained earnings are re-derived on every
  balance-sheet request; the statement only balances because of it.
- **Tenant isolation is manual convention across 110 route files** — no
  middleware, no RLS. Decisive input to the one-DB-per-company recommendation.
- **Payroll is the only float-money path** (JS `number` + `Math.round`), and
  `post-month` deliberately re-derives with the same float math to keep JV
  totals byte-identical.
- **GST has essentially no rounding** until a single `Number(x).toFixed(2)` at
  export — through IEEE-754. A `BigDecimal` port will differ on half-cases.
- The live **e-invoice route fabricates IRNs** with a 32-bit hash plus
  `Date.now()`, despite a comment claiming SHA-256, and marks them `GENERATED`.
- **21 defects catalogued**; several features exist only as schema.
- Postgres-specific surface is **narrower than expected**: 3 raw-SQL sites, 2
  array columns, 23 Json columns, no enums/triggers/sequences/views/RLS.

### Decisions made (all PROPOSED, awaiting sign-off)

- ADR-001: one SQLite file per company + a separate `app.db`.
- ADR-002: universal schema + modular code + signed entitlement; downgrade
  removes code, never data.
- Money as scaled INTEGER + `BigDecimal`; STRICT tables; CHECK-constrained enums.
- `LocalDate` for accounting dates.
- Statutory tax rules as effective-dated, independently shippable data.
- Installer-based signed updates; per-company migrate-on-open.
- Ed25519 entitlement tokens; composite fuzzy machine binding; monotonic time
  anchor; grace period then read-only with export always available.

### Deliverables created

`MEMORY.md`, `DAILY_LOG.md`, and under `docs/`: `PROJECT_OVERVIEW.md`,
`ROADMAP.md`; `analysis/` (4 documents); `architecture/` (6); `decisions/`
(ADR-001, ADR-002, OPEN_DECISIONS, RISKS_AND_MITIGATIONS); `research/` (2);
`testing/TESTING_STRATEGY.md`.

### Problems discovered

- SQLite silently degrades declared decimal columns to floating point — the
  single highest correctness risk in the port.
- Reference migration 17 uses Postgres-only constructs SQLite cannot express
  in place (`ALTER COLUMN`, post-hoc FK), confirming migrations must be
  hand-authored rather than translated.
- The brief's "physically exclude modules" and "keep downgraded data viewable"
  requirements conflict; resolved by separating code / schema / entitlement, but
  the read-access question (C-1) still needs a product decision.

### Problems solved

- Money storage: settled by experiment rather than assertion.
- Downgrade safety: universal schema removes the 2^N migration problem and makes
  re-upgrade lossless.
- Multi-company isolation: converted from a discipline problem into an
  architectural property.

### Remaining work

Phase 0 is complete. Blocking decisions (⚠️ in `OPEN_DECISIONS.md`) are with the
user — chiefly A-1 (year-end close), A-2/A-3 (inventory costing), A-4 (GST
rounding), A-5 (payroll rounding), B-1/B-2 (database and money), C-1/C-2
(module read access and the plan matrix).

### Next recommended work

**Phase 1 — walking skeleton + signed packaging pipeline.** It is independent of
every blocking decision, and it retires the two risks that most often derail this
kind of project when found late (cross-platform packaging, migration safety).

**Start code-signing certificate procurement immediately** — the longest lead
time of anything on the roadmap, and it gates the Phase 1 exit criteria.
