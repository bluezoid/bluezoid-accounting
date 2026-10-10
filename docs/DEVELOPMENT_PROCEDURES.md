# Development Procedures

Practices adopted because a specific incident cost real time. Each entry names
the incident, so the rule can be re-evaluated if its cause ever goes away.

---

## 1. Reinstall before believing a test failure

> **When an integration test exercises the installed application or runtime,
> reinstall the current build before interpreting a failure as an implementation
> defect. Never weaken a test to accommodate a stale artifact.**

```bash
cd accubook-desktop
mvn clean install -DskipTests   # refresh every module's artifact
mvn verify                      # then interpret failures
```

**Why.** Three times now, a stale artifact has produced a convincing but false failure:

| Phase | Symptom | Actual cause |
|---|---|---|
| P1 | phantom "cannot start a transaction within a transaction" | a stale jar in `target/deps` shadowed a freshly built module |
| P6 | five governance tests said `is_system` was 0 when the database held 1 | `accubook-app` had not been reinstalled, so the accounting module tested against older provisioning code |
| P7d | a whole integration suite failed with `NoClassDefFoundError: Database` | `./build.sh` had run `mvn clean package` after the last install; a single-module `-pl` run then mixed fresh and installed artifacts |

**Specific rule from P7d:** after running `./build.sh`, run
`mvn -o clean install -DskipTests` before any `-pl` test run.

Once is bad luck; twice is a process gap. In a multi-module Maven build with
JPMS, a module resolves its dependencies from installed artifacts, not from
sibling source directories — so editing module A and testing module B proves
nothing until A is reinstalled.

**The trap to avoid.** Both incidents presented as a *plausible* defect in
freshly written code. The temptation is to change the code, or the test, until
the symptom disappears. That is how a correct test gets weakened to match a
stale binary.

---

## 2. Test both service-discovery paths

> **Supplying a provider list does not prove `ServiceLoader` discovery works.**

`ModuleRegistry` has two entry points:

- `discover(Iterable<FeatureModule>)` — used by tests, bypasses JPMS entirely
- `discover()` — used in production, goes through `ServiceLoader`

**Why.** P4's isolation test used the first and passed. `accubook.module.api` was
missing its `uses com.accubook.module.FeatureModule;` declaration, so the second
would have thrown `ServiceConfigurationError` at startup. The defect stayed
invisible for two phases because no test exercised the production path.

Any facade over a JDK service, resource, or classloader mechanism needs at least
one test that goes the whole way through it.

---

## 3. Verify from the packaged artifact, not the reactor

> **A build script that parses is not a build script that works — and a build
> script whose log says "included" has not shown that anything was included.**

```bash
./build.sh --plan professional --skip-tests
target/runtime/bin/java --list-modules | grep accubook   # what is actually in it
```

**Why.** Two packaging defects, neither visible to the test suite:

| Phase | Symptom | Cause |
|---|---|---|
| P5 | `excluded: accubook-accounting` | `ALWAYS_INCLUDED` in `build.sh` did not know the module existed |
| P6 | log said `included`, image did **not** contain it | `jlink` resolves transitively from `--add-modules`; nothing `requires` the module yet, so it was pruned from the path it was on |

Both would have shipped an installer whose application could not post anything.
The full test suite passes either way, because the Maven reactor resolves modules
from `target/classes` and never consults the linked image.

So the exit check for any phase that adds a module is to **run exported API from
inside the packaged runtime**, confirming the classes load, the production service
discovery works, and unexported packages are still unexported there.

---

## 4. Confirm a new test fails without its fix

> **A test that passes both before and after a fix has not been shown to test
> anything.**

**Why.** Used deliberately twice, and both times it changed what was reported:

- P4: reverting the case-sensitivity fix made `ownershipIsCaseInsensitive` fail,
  confirming the test caught the real defect.
- P5: replacing the atomic voucher-number allocation with a deliberately racy
  read-then-write **still passed every concurrency test**, revealing that the
  safety came from the transaction boundary rather than the allocator's SQL. The
  report says so instead of claiming coverage the tests do not provide.

Where practical, break the fix, watch the test fail, restore it.

---

## 5. Statutory records are append-only in tests too

Test fixtures must not seed states the schema is meant to forbid. P6 found
`CoreSchemaConstraintIT` seeding a zero-total voucher — precisely the degenerate
case finding A-1 identified. The fixture had been demonstrating that an
entry-less voucher was legal.

When a new constraint breaks a fixture, check whether the fixture was encoding a
defect before changing it to comply.
