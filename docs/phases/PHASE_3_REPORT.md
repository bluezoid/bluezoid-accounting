# Phase 3 — Implementation Report

**Date:** 2026-09-03 · **Status:** COMPLETE, pending review
**Decisions:** P3-1 … P3-8 ✅ all applied
**Committed:** NO · **Reference app modified:** NO · **P4 started:** NO

---

## 1. Implementation completed

New module **`accubook-licensing`**, which no feature module may depend on.

| Component | Purpose |
|---|---|
| `LicenceKey` | 16-char key, Crockford alphabet (no I/L/O/U), position-weighted check character |
| `EntitlementToken` | The signed statement of entitlement |
| `TokenCodec` | Canonical line-oriented serialisation — the exact bytes signed |
| `SignatureVerifier` | Ed25519, multi-key, ordered |
| `TrustedKeys` | **Two** public keys compiled in from v1 |
| `MachineFingerprint` | 5 hashed signals, 3-of-5 matching |
| `TimeAnchor` | Monotonic clock; backward jumps cannot defer expiry |
| `SubscriptionState` | 6 states; only 2 permit mutation |
| `Entitlement` | Immutable, verified once, implements `ModuleStateProvider` |
| `EntitlementLoader` | The **only** place a signature is verified |
| `ModuleState` / `ModuleStateProvider` | In `accubook-module-api` — the entire P4 surface |

---

## 2. State machine (P3-1 clarified)

The review correctly identified that my design was ambiguous about grace timing.
**Resolved: the 14 days sit BEFORE the end date, not after.**

```
UNLICENSED ─activate─► ACTIVE ─(end−14d)─► RENEWAL_WINDOW ─(past end)─► EXPIRED
                          │                (still writable)             (READ-ONLY)
                          ├─ admin ──► SUSPENDED (READ-ONLY)
                          └─ bad token ──► INVALID (READ-ONLY)
```

| State | Mutate | Read | Export |
|---|---|---|---|
| `ACTIVE`, `RENEWAL_WINDOW` | ✅ | ✅ | ✅ |
| `EXPIRED`, `SUSPENDED`, `INVALID` | ❌ | ✅ | ✅ |
| `UNLICENSED` | ❌ | ❌ | ❌ |

`RENEWAL_WINDOW` is a **warning period, not extra time**. At the end date the
application becomes read-only immediately — pinned by a test that checks 20
consecutive days after expiry. Full spec: `PHASE_3_STATE_MACHINE.md`.

---

## 3. Token structure

Line-oriented `key=value`, fixed field order, module ids sorted. Chosen over
JSON because the signed bytes must be reproducible byte-for-byte on both sides,
and JSON offers too many ways to differ invisibly (key order, whitespace,
escaping, number formatting).

```
v · licenceId · customerId · plan · modules · subscriptionStart
subscriptionEnd · renewalWindowDays · machineFingerprint · suspended
issuedAt · tokenId
```

Dates are `LocalDate`: a subscription runs to the end of a calendar day, not an
instant. Stored form is three lines — key id, base64 signature, canonical body.

---

## 4. Cryptographic design

**Ed25519 from the JDK.** Measured: 44-byte public key, 64-byte signature, no
third-party dependency, nothing extra in the jlink image.

**Verified once at load.** Measured cost is ~1.5 ms; running it per operation
would put a third of a second behind a 200-line posting screen. The resolved
`Entitlement` is immutable and answers from memory — verified by a test running
100,000 `moduleState()` calls in well under one verification's time.

---

## 5. Key rotation (P3-4)

Two keys compiled in from v1, tried in order. A build can only verify signatures
from keys it already knows, so if only one shipped and it were compromised,
every installation in the field would be unfixable — an update signed with the
compromised key is worthless.

Tested: both keys verify; the server can switch to the secondary and
already-shipped builds keep working; dropping a key from the next build stops
its tokens.

---

## 6. Machine fingerprint (P3-2)

Five hashed signals, **3-of-5**: install UUID, OS machine id, CPU signature,
default-route MAC, OS signature.

**Deliberately excluded**, both measured this session:
- **DMI serials** (`product_uuid`, `board_serial`) — `AccessDeniedException` for
  a non-root process on Linux. An accounting app must not require root.
- **Enumerating all MACs** — `NetworkInterface.isVirtual()` reports `docker0` as
  `false`, so installing Docker, a VPN or VirtualBox would change the
  fingerprint and lock out a customer who did nothing.

Only hashes are stored: a MAC address is personal data.

---

## 7-8. Activation, revalidation, offline

Verification is entirely local — nothing in the load path opens a socket.
Revalidation is on load and every 24 h **when online**, never per operation
(P3-6). Offline operation for the full subscription period is the design
premise, not a fallback.

`store()` writes a verified token; the activation/renewal transport is the
licence server's side and belongs to **P13**. A test harness signs with
throwaway keys so the whole client path is testable now.

---

## 9. Expiry / read-only

At the end date: `EXPIRED`, mutation refused, **reading and export still work**.
Every state except `UNLICENSED` permits reading — asserted for all six states.

---

## 10. The P4 contract

```java
ModuleState moduleState(String moduleId);   // ACTIVE | READ_ONLY | DORMANT | ABSENT
```

That is the entire surface. Expiry, downgrade and suspension all reach a module
identically, as `READ_ONLY` — the module does not learn why, and should not.

**Enforced by the build, not by review:** the isolation test lives in
`accubook-module-api`, which has **no dependency on `accubook-licensing`**. If a
licensing type ever leaked into the contract, that module would stop compiling.

---

## 11. Security verification

| Threat | Result |
|---|---|
| Edited entitlement | ✅ Signature check fails → `INVALID`, read-only |
| Forged signature | ✅ Untrusted key rejected |
| Unknown/revoked key | ✅ Rejected |
| Copied to another machine | ✅ Binding fails |
| Clock rolled back a year | ✅ Expiry still enforced |
| Corrupt/empty file | ✅ `INVALID`, no crash |
| Post-dated token | ✅ Refused, not treated as active |
| Licence key in logs | ✅ `toString()` redacts |
| **Patching the binary** | ❌ **Accepted** — stated in the threat model |

---

## 12. Test results — 196 passing, 0 failing, 3 skipped

**New in P3: 92 tests.**

```
SignatureVerifierTest        10 ✅   LicenceKeyTest              11 ✅
TimeAnchorTest                6 ✅   SubscriptionLifecycleTest   13 ✅
MachineFingerprintTest       26 ✅   EntitlementLoaderIT         12 ✅
ModuleIsolationTest           8 ✅   LicensingDataSafetyIT        6 ✅
```

**Mandated security test — licensing failure never touches customer data.**
Each case hashes `company.db` with SHA-256 before and after and asserts the
bytes are **identical**, not merely that the file exists:
missing entitlement ✅ · corrupt entitlement ✅ · wrong machine ✅ ·
books still openable and balanced afterwards ✅ · entitlement stored outside the
company directories ✅ · no state permits writing while forbidding reading ✅.

**Mandated architectural test** — 8 tests proving a feature module resolves its
state through `moduleState(id)` with no licensing import, including a structural
assertion that `accubook.module.api` does not read `accubook.licensing`.

**Behaviour scenarios:** no internet ✅ · clock change ✅ · corrupted file ✅ ·
entitlement replaced ✅ · key rotation ✅ · Docker/VPN fingerprint change ✅.

---

## 13. P1 + P2 regression: all green

104 pre-existing tests still pass. 3 UI tests skipped (no display — unchanged).
Installer rebuilt (70 MB) and the packaged app verified: unlicensed →
`READ_ONLY`, while the books open, seed 4 ledgers and balance.

---

## 14. Files

**Created (18):** 11 main sources in `accubook-licensing`, 2 in
`accubook-module-api`, 5 test suites + 2 test-support classes.
**Modified (5):** parent `pom.xml`, `accubook-app` pom + `module-info`,
`accubook-module-api` pom, `build.sh`.

---

## 15. Remaining risks and decisions

| Item | Status |
|---|---|
| **Development keys are compiled in** | `TrustedKeys` holds real Ed25519 keys generated for testing. **The release process must fail if they are still present.** Not yet automated — worth a build check in P16 |
| Licence server + admin portal | **P13.** P3 defines and consumes the protocol; a test harness stands in |
| 24 h revalidation timer | Policy and state implemented; the scheduler belongs with the app lifecycle |
| Rebind counting (P3-3, max 2/year) | **Server-side policy** — the client cannot enforce a limit it could rewrite. Client honours whatever entitlement it is issued |
| Windows/macOS machine id | Linux path implemented and tested; Windows `MachineGuid` and macOS `IOPlatformUUID` need per-platform providers |
| Carried from P1/P2 | Windows/macOS packaging, `.rpm`, code signing, UI tests |

**A packaging bug was found and fixed:** `build.sh` had a hardcoded module list,
so the new licensing module was silently omitted and jlink failed to resolve.
The script now discovers modules, so P4 cannot hit the same problem.

---

## 16-18. Confirmations

- **`/home/achyut-pal/Desktop/accubook` not modified** — `git status` clean.
- **Nothing staged or committed** — working tree only.
- **P4 and later not started** — no module registry, no per-plan build
  selection, no entitlement enforcement interceptor. `ModuleStateProvider`
  exists as the contract P4 will consume; nothing consumes it yet.
