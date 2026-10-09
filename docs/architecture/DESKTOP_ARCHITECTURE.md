# Desktop Architecture

Status: PROPOSED — awaiting sign-off
Target: Java + JavaFX (OpenJFX) on AWS Corretto, SQLite, offline-first,
Windows / Linux / macOS.

Verified locally this session: AWS Corretto **17.0.20.1** and SQLite **3.50.6**
are installed on the development machine; Maven is present, Gradle is not.

---

## 1. Layering

The brief proposes Presentation → Application → Domain → Infrastructure and asks
whether it is right. For this product it is, with one important adjustment: the
**posting engine must be a sealed subsystem**, not merely a layer.

```
┌─────────────────────────────────────────────────┐
│ Presentation (JavaFX)                           │
│   views, view-models, formatting, i18n          │
│   knows: application services + DTOs            │
├─────────────────────────────────────────────────┤
│ Application / Use Cases                         │
│   transaction boundary, orchestration,          │
│   entitlement checks, audit emission            │
├─────────────────────────────────────────────────┤
│ Domain                                          │
│   accounting rules, tax engines, valuation,     │
│   payroll, invariants. NO framework, NO SQL.    │
│   ┌───────────────────────────────────────────┐ │
│   │ Posting core (sealed)                     │ │
│   │  the ONLY path that mutates ledger state  │ │
│   └───────────────────────────────────────────┘ │
├─────────────────────────────────────────────────┤
│ Infrastructure                                  │
│   repositories, migrations, backup, licence     │
│   client, update client, file system            │
├─────────────────────────────────────────────────┤
│ SQLite (per company) + app.db (installation)    │
└─────────────────────────────────────────────────┘
```

**Why the posting core is sealed rather than just "in the domain".**
The reference app's single greatest asset is that *every* posting path funnels
through `applyLedgerEntries`, which enforces debits == credits. That is currently
guaranteed by convention — any new service could bypass it. In Java this can be
guaranteed **structurally**: ledger-mutating repository methods are
package-private to the posting package, so no other package can compile a call
to them. A new posting path is *forced* through the invariant check.

This directly addresses the brief's first priority (accounting correctness) and
converts the reference app's best-practice into an enforced property.

## 2. Module system

Java Platform Module System (JPMS) modules, one per feature, built as a Maven
multi-module project (Maven is what's installed; see BUILD notes).

```
accubook-platform      shared kernel: Money, ids, result types, errors, events
accubook-persistence   connection factory, migration runner, unit-of-work
accubook-domain-core   CoA, ledgers, vouchers, posting core, fiscal year
accubook-domain-*      inventory, sales, purchase, tax-in, payroll, banking …
accubook-app-*         use-case services per feature
accubook-ui-shell      JavaFX shell, navigation, module registry
accubook-ui-*          per-feature JavaFX views
accubook-licensing     activation, entitlement verification, machine binding
accubook-backup        backup/restore + CloudStorageProvider SPI
accubook-updater       update check, download, verify
accubook-launcher      composition root; assembles a build
```

Feature modules are discovered at runtime through `ServiceLoader` against a
`FeatureModule` SPI. The shell renders navigation from whatever modules are
present. Consequences:

- Excluding a module from a build removes its code, its UI, and its use cases —
  a physical exclusion, satisfying the brief's requirement that this is not a
  feature flag.
- The shell does not import feature modules, so it compiles and runs with any
  subset.
- **Schema is NOT excluded** — see ADR-002 and MODULE_ARCHITECTURE. This is the
  key insight that makes downgrade safe.

## 3. Concurrency and the transaction boundary

SQLite is single-writer. That is a simplification, but it must be designed for
rather than discovered:

- **All writes go through a single serialized writer** (one connection, or a
  strict write lock). Background work — backup, import, update checks — must
  never contend with interactive posting.
- **Reads use a separate connection pool**; WAL lets readers run concurrently
  with the writer.
- **The UI thread never touches the database.** JavaFX has one UI thread;
  blocking it on a query freezes the application. Use cases run on a background
  executor and publish results back via `Platform.runLater`.
- `busy_timeout` is set so a checkpoint cannot surface as a `SQLITE_BUSY` error
  during a user action.

**Port note on concurrency idioms.** The reference app achieves correctness with
*no explicit locking* — it relies on unique constraints, `ON CONFLICT` upserts,
and conditional-claim `UPDATE ... WHERE <precondition>` + row-count checks.
14 integration test files deliberately interleave real transactions to prove it.
Under SQLite the failure mode changes from constraint-violation to `SQLITE_BUSY`,
so **those tests are the specification the port must re-satisfy under different
failure semantics.** The conditional-claim idiom should be kept — it maps
cleanly onto JDBC `executeUpdate()` row counts and remains correct.

## 4. What to drop from the reference architecture

These exist purely for serverless multi-tenant HTTP deployment and must **not**
be translated:

- `withDbRetry` / Neon cold-start handling, pool caps (`max: 3`), raised
  interactive-transaction timeouts
- Upstash Redis rate limiting; cron-secret auth; Vercel Blob storage
- CSRF origin checks; JWT revocation polling; NextAuth session machinery
- The `organizationId` column on ~40 tables (per ADR-001, one file per company)

## 5. What to keep and strengthen

| Keep | Strengthen how |
|---|---|
| Single posting choke point | Enforce with package-private access, not convention |
| `writeAudit` inside the same transaction | Same rule; make audit append-only at the DB level |
| Conditional-claim concurrency idiom | Document as the standard; test under `SQLITE_BUSY` |
| Provisioning content (18 groups, 23 ledgers, HR masters) | Resolve by stable `system_code`, not display name |
| Generic `(entityType, entityId)` approval framework | Add the missing INVOICE / PURCHASE_ORDER promotion branches |
| Permission model | Drop the two legacy compatibility shims; emit one shape |
| Free-then-paid OCR escalation | Keep the design; OCR is an optional module |

## 6. Cross-cutting decisions

**Money.** `BigDecimal` in domain and UI; scaled `long` only inside the
persistence mapper. One `MoneyMapper`, one place that knows scales. See
SQLITE_MONEY_STORAGE.

**Dates.** Accounting dates are `LocalDate` — no instants, no zones. This removes
an entire bug class the reference app suffers from (report FY math in
server-local time; the Form 16A 31-March boundary). Timestamps that are genuinely
instants (audit `created_at`) use UTC `Instant`.

**Enums.** Java enums + SQLite `CHECK` constraints, replacing the reference app's
unconstrained `String` columns. This alone would have prevented the
`WEEK_OFF`/`WEEKEND` payroll bug and the `DEBIT`/`DR` opening-balance bug.

**IDs.** Application-generated, as in the reference (no DB sequences). UUIDv7 is
preferable to cuid for index locality.

**Errors.** Domain invariant violations are typed exceptions carrying the
offending values (as `UnbalancedEntriesError` does), never generic failures.

## 7. Statutory rule data must be updatable independently of the app

Tax rates, thresholds, HSN data and return formats change on the Finance Bill
cycle, and customers will be running older application versions when that
happens. Therefore statutory rules are **versioned, effective-dated data**, not
compiled constants, and must be shippable as a signed rule-pack without a full
application update. This is a first-class architectural requirement — see
TAXATION_VERIFIED §8 and UPDATE_AND_MIGRATION_ARCHITECTURE.

## 8. Open architectural questions

- **JDBC driver**: xerial `sqlite-jdbc` is the default choice, but needs
  verification for STRICT-table support, Online Backup API exposure, native
  bundling across 3 OSes × 2 architectures, and jlink/jpackage compatibility.
  **RESEARCH REQUIRED.**
- **Persistence approach**: hand-written SQL/JDBC or a light mapper (JDBI,
  MyBatis)? A heavy ORM is a poor fit given the explicit money mapping and the
  need for exact control of SQL. Leaning hand-written SQL + a thin mapper.
  **DECISION REQUIRED.**
- **Reporting strategy**: the reference computes all four statements **in
  memory in JavaScript** (`O(ledgers × entries)`). With scaled-INTEGER money,
  SQLite can aggregate exactly with `GROUP BY` — far better on desktop hardware.
  Recommend SQL aggregation. **DECISION REQUIRED** (affects report parity tests).
- **Build tool**: Maven is installed; Gradle is not. The brief mentions Gradle.
  Either works with jlink/jpackage. **DECISION REQUIRED.**
