# Build, Packaging & Distribution

Status: PROPOSED. Addresses brief §22 (build & distribution), §8 (module builds).

---

## 1. Requirement: the customer must not install Java

`jlink` produces a trimmed runtime image containing only the modules the app
needs; `jpackage` wraps it into a native installer. The customer installs one
package and runs one application.

```
sources → compile → jlink (custom runtime) → jpackage (native installer)
                                              ├── Windows  .msi / .exe
                                              ├── macOS    .dmg / .pkg
                                              └── Linux    .deb / .rpm / AppImage
```

Both ship with the JDK (AWS Corretto 17 is installed locally and is a supported
choice; Corretto 21 LTS is worth evaluating for a longer support horizon —
**DECISION REQUIRED**).

## 2. Per-plan builds

```
./build.sh --plan PROFESSIONAL --modules accounting,inventory,gst,payroll
```

Selects the module set for the composition root, runs jlink/jpackage, and embeds
build metadata (plan, modules, version, build id, commit) into the artifact so
support can identify exactly what a customer is running.

The **licence remains authoritative over the build** — a PROFESSIONAL build
activated with a BASIC licence degrades to BASIC. Otherwise shipping the wrong
installer would silently grant entitlement.

## 3. Native dependency: the SQLite JDBC driver

`sqlite-jdbc` bundles native libraries per platform. This must be verified for
every target — Windows x64, macOS x64, **macOS arm64**, Linux x64, Linux arm64 —
and confirmed to work inside a jlink image. **RESEARCH REQUIRED (B-4).** Getting
this wrong is discovered late and is painful.

## 4. Code signing — start procurement now

| Platform | Requirement | Consequence if unsigned |
|---|---|---|
| Windows | Authenticode certificate (OV or EV) | SmartScreen warns; EV builds reputation faster |
| macOS | Apple Developer ID + **notarization** + stapling | Gatekeeper **blocks** by default |
| Linux | GPG-signed repo metadata (convention) | Warnings in some tooling |

macOS notarization is not optional in practice — an unsigned app effectively
cannot be opened by a normal user. **These are long-lead items requiring paid
enrolment; begin immediately** (D-2). Antivirus false positives on unsigned Java
installers are common and damage commercial credibility.

## 5. CI/CD

Build all three platforms per release (macOS builds require macOS runners;
Windows require Windows runners). Pipeline: build → unit → integration →
package → sign → smoke-test install on a clean VM → publish with hashes and a
signed update manifest.

**Reproducible builds** are worth aiming for: the same inputs should yield the
same artifact hash, which matters when verifying what a customer actually has.

## 6. Application data locations

| OS | App data |
|---|---|
| Windows | `%LOCALAPPDATA%\AccuBook\` |
| macOS | `~/Library/Application Support/AccuBook/` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/accubook/` |

**Program files and application data are strictly separate**, so an uninstall or
reinstall cannot touch company databases (brief §18). Installers must never write
to the data directory beyond first-run creation, and uninstallers must never
delete it without explicit, separate confirmation.

---

## 7. Verified toolchain findings

Researched and hands-on verified against `org.xerial:sqlite-jdbc:3.53.4.0` on
AWS Corretto 17.0.20.1.

### 7.1 SQLite JDBC driver — resolved (was OPEN in Phase 0)

**`org.xerial:sqlite-jdbc:3.53.4.0`** (bundles SQLite 3.53.4).
**License: Apache-2.0 + BSD-2-Clause** (original Zentus code); the bundled SQLite
amalgamation is public domain. **Clean for a closed-source commercial product** —
ship both licence texts. Zero mandatory runtime dependencies (SLF4J optional).

**Native binaries: 23 in a ~12 MB jar** — Windows (x86, x86_64, armv7, aarch64),
macOS (x86_64, **aarch64**), Linux (x86, x86_64, arm, armv6, armv7, aarch64,
ppc64, riscv64), Linux-musl (Alpine), FreeBSD. **All five of our targets are
covered, including Apple Silicon.**

There is **no pure-Java fallback** — a missing native throws
`NativeLibraryNotFoundException`.

Feature support **verified working**: STRICT tables ✅, `RETURNING` ✅, UPSERT ✅,
generated columns ✅, JSON1/JSONB ✅, FTS5 ✅, window functions ✅,
`MAX_ATTACHED=125`.
The **Online Backup API is exposed** via `org.sqlite.core.DB.backup(...)`,
including a throttling overload — `org.sqlite.core` is an exported package.

### 7.2 JPMS and jlink — it works, with two specific gotchas

**The driver is a real explicit module** (`org.xerial.sqlitejdbc`), not an
automatic one — delivered as a **multi-release jar** with
`META-INF/versions/9/module-info.class`. A jlink image was built and ran queries
successfully.

> Blog posts claiming "sqlite-jdbc is an automatic module, unusable with jlink"
> are **stale** — they predate the fix.

Two gotchas that will cost a day each if unknown:

1. **jlink needs `--bind-services`**, because the driver is a
   `provides java.sql.Driver` service. Without it, `DriverManager` reports
   "No suitable driver". (Alternative: instantiate `org.sqlite.JDBC` /
   `SQLiteDataSource` directly and skip service loading.)
2. **Shading can silently drop `META-INF/versions/9/`**, reverting the driver to
   automatic-module behaviour. If a shade step is introduced, this must be
   preserved and tested.

### 7.3 Native extraction — preinstall it

Default behaviour extracts the native library to a temp directory at runtime
(UUID-suffixed, so concurrent JVMs no longer race). Remaining failure modes are
exactly the ones a deployed accounting app will hit:

- **`noexec /tmp`** (common on hardened Linux)
- read-only or full temp
- **antivirus quarantining a freshly-written DLL on Windows** — the most likely
  to bite in the field
- stale files after hard kills

**Recommendation:** ship the single correct native in the jpackage image and set
`-Dorg.sqlite.lib.path=...`. Verified to write **zero** files to temp,
eliminating all four failure modes, and using `-without-natives` saves ~11 MB per
installer.

This also means each platform's installer carries only its own native — cleaner
and smaller than shipping all 23.

### 7.4 Encryption dependency (if adopted)

`io.github.willena:sqlite-jdbc:3.53.4.0` is a drop-in fork (same package names,
one-line dependency swap) adding SQLite3MultipleCiphers, ~16 MB with all natives.
Risk: single-maintainer fork, currently tracking upstream in lockstep.

**If adopted, ensure stock `org.xerial:sqlite-jdbc` is excluded from the
dependency tree** — two SQLite libraries in one process can make cipher pragmas
**silently no-op**, which would mean shipping an "encrypted" product that is not.

### 7.5 Complementary libraries

- **JDBI 3.54.0** — a good thin complement if a mapper is wanted (see N-2).
- **HikariCP** — works; set no `connectionTestQuery` (the 2015 objection is
  obsolete). Value here is leak detection and lifecycle discipline, not pooling
  performance.
- **Avoid `sqlite4java`** — dead since 2014, **no aarch64**, so no Apple Silicon,
  despite older articles recommending it.
- Java 22+ FFM bindings are not landed and are unavailable on 17 regardless.

### 7.6 Build checklist per platform

| Step | Windows | macOS | Linux |
|---|---|---|---|
| Runtime | jlink `--bind-services` | same | same |
| Native | preinstalled, `org.sqlite.lib.path` | same | same |
| Package | `.msi` / `.exe` | `.dmg` / `.pkg` | `.deb` / `.rpm` |
| Sign | Authenticode (OV/EV) | Developer ID + **notarize + staple** | GPG repo metadata |
| Verify | clean-VM install, no system Java | same, **both arm64 and x64** | same |

macOS notarization is **not optional in practice** — an unsigned app effectively
cannot be opened by a normal user. Certificate procurement is the longest lead
item on the whole roadmap and gates the Phase 1 exit criteria.
