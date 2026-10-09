# Phase 1 — Design

**Status:** DESIGN (pre-implementation) · **Date:** 2026-08-31
**Rule:** research/design → implement → unit + integration + UI tests →
regression → report. **No commits.**

---

## 1. Verified environment

| Item | Verified |
|---|---|
| JDK | **AWS Corretto 17.0.20.1**, `JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto` |
| `jlink` / `jpackage` / `jmod` / `javac` / `jar` | **all present** |
| Build tool | **Maven 3.8.7** · Gradle **not installed** |
| JavaFX | **not bundled** with Corretto — must come from Maven (expected) |
| Dev OS | Ubuntu 24.04.4 LTS |
| `dpkg-deb`, `fakeroot` | present → **`.deb` packaging works here** |
| `rpmbuild` | **missing** → `.rpm` designed for, produced on a CI runner |

**Decision N-1 resolved: Maven.** It is installed, works with jlink/jpackage, and
adding a Gradle dependency for no functional gain would be unjustified. Recorded
in OPEN_DECISIONS.

---

## 2. Scope

### In scope
- Maven multi-module skeleton with the JPMS layout from `DESKTOP_ARCHITECTURE.md`
- `FeatureModule` SPI **stub** (contract only; registry lands in P4)
- SQLite connection factory with the verified pragma set
- Two-pool model (writer size 1 + reader pool), `BEGIN IMMEDIATE`, explicit
  rollback
- **`MoneyMapper`** — *gated on B-3*
- **Migration runner** — *gated on B-6*
- Minimal JavaFX shell + one trivial vertical slice
- jlink + jpackage pipeline, all three OSes
- Test harness: unit + integration + UI, with the concurrency invariants that
  Rule 3 says start here

### Explicitly out of scope
Accounting domain · entitlement enforcement (P4) · licensing (P3) · real schema
(P2). P1 proves the *machinery*, not the product.

---

## 3. Module layout

```
accubook-desktop/                     (parent POM, packaging=pom)
├── accubook-platform/                shared kernel — no deps on anything below
│     Money, Scale, ids (UUIDv7), Result, typed errors, clock abstraction
├── accubook-persistence/             SQLite: connection factory, pools,
│                                     unit-of-work, migration runner
├── accubook-module-api/              FeatureModule SPI (stub in P1)
├── accubook-app/                     use-case layer (one trivial use case in P1)
├── accubook-ui-shell/                JavaFX shell, navigation, theming
└── accubook-launcher/                composition root; jlink/jpackage target
```

**Dependency direction is strictly downward.** `accubook-platform` depends on
nothing; `accubook-ui-shell` never imports a feature module (P4 requirement,
enforced from day one so it cannot rot).

### JPMS module names
```
accubook.platform · accubook.persistence · accubook.module.api
accubook.app · accubook.ui.shell · accubook.launcher
```
Real `module-info.java` from the start — retrofitting JPMS later is painful, and
the SQLite driver is a genuine explicit module (`org.xerial.sqlitejdbc`), so the
module path works end to end.

---

## 4. Dependencies (pinned)

| Dependency | Version | Why |
|---|---|---|
| `org.openjfx:javafx-*` | 17.0.x LTS | matches Corretto 17 |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | Apache-2.0 + BSD-2; real JPMS module; Online Backup API |
| JUnit 5 | 5.10.x | unit + integration |
| TestFX | 4.0.x | JavaFX UI tests |
| AssertJ | 3.25.x | readable assertions |
| jqwik | 1.8.x | **property-based tests** for the money round-trip |

Deliberately **not** adding: Spring/Guice (a composition root is enough and DI
frameworks fight JPMS), Hibernate/JPA (explicit SQL is required — see N-2),
Lombok (obscures the money-boundary code that must be reviewable).

---

## 5. The two gated deliverables

### 5.1 `MoneyMapper` — gated on **B-3**

Design is settled by evidence; only the scale table needs sign-off.

```java
public enum Scale {
    MONEY(4), RATE(2), FX(8), CONVERSION(6), DAYS(1);
}
```

Boundary contract (verified working in research):
```java
// write — throws ArithmeticException if the value exceeds the column's scale
long stored = amount.movePointRight(scale).longValueExact();
// read
BigDecimal value = BigDecimal.valueOf(stored, scale);
```

Rules encoded in the type system, not in review comments:
- `BigDecimal` in every signature; `long` **only** inside the mapper
- no `divide` without an explicit `RoundingMode`
- column naming `_e4` / `_e8` / `_bp` so the encoding is visible in every query
- **never** scale back in SQL (`amt/10000.0` is float division)
- **never** multiply money by a rate in SQL (overflows INT64) — do it in Java

**If B-3 is not settled when P1 reaches this point**, the rest of P1 continues;
this is the only item that stalls.

### 5.2 Migration runner — gated on **B-6**

```
open → read schema_version
  version == expected → proceed
  version >  expected → REFUSE (never open a newer file)
  version <  expected → backup → verify → migrate → validate → commit
                        on ANY failure: restore snapshot, keep the failed copy
```

Validation is **not** just `integrity_check`. It includes an accounting
invariant — trial balance sums to zero — because a migration can be structurally
valid and still have corrupted the books. In P1 there is no ledger yet, so the
hook exists and is exercised by a **synthetic** balanced/unbalanced fixture.

Backups use **`VACUUM INTO` / Online Backup API**, never a file copy (a live WAL
database cannot be safely file-copied).

---

## 6. Connection management

```java
CompanyContext
├── writeConnection   single, serialized, autoCommit=false, BEGIN IMMEDIATE
└── readPool          2–4 connections, PRAGMA query_only=1
```

Pragmas applied by the factory — never by callers:
```sql
journal_mode = WAL          -- persistent, set once per file
foreign_keys = ON           -- PER CONNECTION, defaults OFF
busy_timeout = 10000        -- 0 loses writes (measured: 900/1200 lost)
synchronous  = NORMAL       -- 63× faster than FULL, safe in WAL
temp_store   = MEMORY
journal_size_limit = 6291456
```

Two behaviours are **architectural** and must be right in P1, because every later
write path inherits them:

1. **`BEGIN IMMEDIATE` on every writing transaction.** `setAutoCommit(false)`
   issues a *deferred* begin, and a deferred transaction upgrading read→write
   returns `SQLITE_BUSY` **instantly regardless of `busy_timeout`**.
2. **Explicit rollback on `SQLException`.** SQLite aborts only the *statement*,
   not the transaction (unlike PostgreSQL). A catch-and-continue would silently
   commit a partial journal entry — exactly the class of bug the reference app
   fought.

---

## 7. The vertical slice

To prove the machinery end to end without touching the accounting domain:

> Create a company · store a `Money` value · read it back · run a schema
> migration · display it in the JavaFX shell.

Chosen because it exercises **every** P1 risk: JPMS wiring, JavaFX startup,
SQLite pragmas, the money boundary, migration + rollback, the UI thread rule,
and the packaged runtime.

---

## 8. Packaging pipeline

```
mvn package
  → jlink   (custom runtime, --bind-services for the JDBC service provider)
  → jpackage
      Windows  .msi / .exe    (Authenticode)
      macOS    .dmg / .pkg    (Developer ID + notarize + staple)
      Linux    .deb           (works on this machine)
               .rpm           (CI runner — rpmbuild absent locally)
```

Two gotchas already identified, handled from the start:
- **`--bind-services`** is required, or `DriverManager` reports "No suitable
  driver" (the driver is a `provides java.sql.Driver` service).
- **Native preinstalled** via `-Dorg.sqlite.lib.path`, avoiding runtime
  extraction to temp — which fails on `noexec /tmp` and gets quarantined by
  antivirus on Windows. Also allows `-without-natives` (~11 MB smaller).

**App data lives outside the install directory**, so an update or uninstall
cannot touch customer books:

| OS | Path |
|---|---|
| Windows | `%LOCALAPPDATA%\AccuBook\` |
| macOS | `~/Library/Application Support/AccuBook/` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/accubook/` |

Created `0700` (dirs) / `0600` (files) on POSIX.

---

## 9. Testing — Rule 3 starts here

| Level | P1 coverage |
|---|---|
| **Unit** | Money scale conversion; **property test**: `BigDecimal → long → BigDecimal` exact at every scale, incl. negatives and boundary magnitudes; path resolution per OS |
| **Integration** | Real SQLite: pragmas actually applied; **FK violation throws** (proves `foreign_keys=ON`); **`BEGIN IMMEDIATE` acquires correctly**; **partial-transaction rollback on constraint violation**; migration forward; migration rollback on injected failure; newer-schema file **refused** |
| **UI (TestFX)** | Shell launches; the slice renders; **UI thread never blocks on the DB** |
| **Concurrency** | Two writers contend → one waits, none lost; leaked reader → WAL growth detected |
| **Build** | Installer builds; launches on a clean VM with **no system Java** |

The FK-enforcement and rollback tests matter disproportionately: both are
per-connection or per-catch behaviours that silently degrade, and both would
corrupt accounting data later.

---

## 10. Exit criteria

1. Signed installer per OS; launches on a clean VM with no system Java
2. Vertical slice works end to end from the packaged app
3. Money property test green at every scale
4. Migration: forward, rollback-on-failure, newer-file-refused — all proven
5. Pragma tests green (FK enforcement, `BEGIN IMMEDIATE`, rollback semantics)
6. Concurrency tests green
7. `jlink --bind-services` verified (driver resolves in the packaged runtime)
8. App data confirmed outside the install dir on all three OSes

**Signing note.** Certificates are a long-lead procurement item. P1 will produce
**unsigned** installers plus a *tested, parameterised* signing step, so signing
becomes a credential swap rather than new work. Criterion 1 is met unsigned and
re-verified once certificates arrive.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| B-3/B-6 unresolved when P1 reaches them | Sequenced last; rest of P1 proceeds |
| JavaFX + JPMS + jlink friction | Prove the packaged runtime in the **first** slice, not at the end |
| macOS/Windows untestable locally | CI runners; `.deb` verified locally |
| Certificates delayed | Unsigned + parameterised signing step |
| `rpmbuild` absent locally | `.rpm` on CI only |
