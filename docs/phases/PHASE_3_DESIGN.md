# Phase 3 — Design & Research Report

**Status:** DESIGN ONLY — no code written · **Date:** 2026-09-02
**Phase:** 3 — Licensing, machine binding, subscription lifecycle
**Blocks:** P4 (physical modules) depends on the entitlement model defined here

> Per protocol: research → design → **report → STOP**. Implementation begins only
> after this report is reviewed and its decisions answered.

---

## 1. Why this phase comes before the modules it gates

P4 packages modules physically and enforces entitlement at three layers. If P3
defines entitlement loosely, every module ends up carrying its own licensing
logic — which is precisely the drift the reference application demonstrates,
where permissions were per-handler and **55 of 71 mutating routes forgot the
check**.

So the deliverable is not only "activation works". It is **one entitlement
object that P4 can consume without any module knowing what a licence is.**

---

## 2. Research findings (measured this session)

### 2.1 Ed25519 is native to Corretto 17 — no third-party crypto

| Property | Measured |
|---|---|
| `Signature.getInstance("Ed25519")` | **available** |
| `KeyPairGenerator.getInstance("Ed25519")` | **available** |
| Public key, X.509 encoded | **44 bytes** (60 chars base64 — embeddable in source) |
| Signature | **64 bytes** (86 chars base64url) |
| Tampered payload | correctly **rejected** |

No BouncyCastle, no shaded crypto, nothing extra in the jlink image.

### 2.2 🔴 Verification costs ~1.5 ms — so it cannot run per operation

Warmed measurement: **1.547 ms per verify**. That is far too slow to sit in front
of every mutating use case; a screen posting 200 lines would spend a third of a
second on signature checks alone.

**Design consequence.** Verify **once at load**, then hold an immutable,
in-memory `Entitlement` value object. Use-case checks read a field — nanoseconds.
Re-verify only when the token changes (activation, renewal, refresh) or on a
periodic revalidation timer, not per call.

This is not a weakening: an attacker who can patch memory has already lost us the
game (see §5). What we defend against is a *tampered file*, and a file is only
read when it is loaded.

### 2.3 🔴 Machine fingerprint: hardware serials need root on Linux

| Signal | Non-root readable? |
|---|---|
| `/etc/machine-id` | ✅ (33 bytes) |
| `/var/lib/dbus/machine-id` | ✅ |
| MAC addresses | ✅ |
| CPU model, core count, arch | ✅ |
| **`/sys/class/dmi/id/product_uuid`** | ❌ **AccessDeniedException** |
| **`/sys/class/dmi/id/board_serial`** | ❌ **not readable** |

An accounting application must not require root. **The fingerprint therefore
cannot depend on DMI hardware serials on Linux.** Windows `MachineGuid` and macOS
`IOPlatformUUID` are readable without elevation, so the composition differs per
platform — which the design must accommodate rather than assume away.

### 2.4 🔴 `NetworkInterface.isVirtual()` is unreliable

Measured on this machine:

```
docker0    virtual=false  up=false  mac=52A13D387217
wlp3s0     virtual=false  up=true   mac=90324B682937
```

**`docker0` reports `virtual=false`.** Java's own flag does not identify virtual
adapters, so a fingerprint over "all MAC addresses" would change the moment
Docker, VirtualBox, VPN or WSL is installed or removed — locking out a customer
who did nothing but install a tool.

**Design consequence.** Do not enumerate all MACs. Prefer the OS machine id;
where a MAC is used, take only the interface backing the default route, and treat
it as **one weak signal among several**, never as a required match.

---

## 3. The entitlement model — the part P4 depends on

```
LICENCE KEY      ACB7-X92K-PQ41-M8ZT   identifier + activation credential.
                                        Grants nothing on its own.
        │
        ▼ activation (online, once)
ENTITLEMENT      signed by the server, stored locally, verified on load.
  ├── plan               BASIC | PROFESSIONAL | ENTERPRISE | CUSTOM
  ├── modules[]          what may be MUTATED
  ├── subscription       start, end, state, grace days
  ├── machineBinding     which device this is valid for
  └── issuedAt, tokenId  replay protection
        │
        ▼ resolved at load into
Entitlement (immutable value object)
  ├── moduleState(moduleId) -> ACTIVE | READ_ONLY | DORMANT | ABSENT
  ├── isMutationAllowed()
  └── subscriptionState()
```

**The contract P4 consumes is `moduleState(moduleId)` and nothing else.** No
module imports a licence type, knows what a signature is, or calls a server. That
is what stops per-module licensing logic from appearing.

`effective = build_modules ∩ entitled_modules`. **The licence is authoritative**:
a PROFESSIONAL build activated with a BASIC licence degrades to BASIC. Otherwise
shipping the wrong installer silently grants entitlement.

---

## 4. Subscription state machine

```
PENDING ──activate──► ACTIVE ──T-30──► EXPIRING ──T──► GRACE ──+N days──► READ_ONLY
             ▲            │                                                    │
             └── renew ───┴────────────────────────────────────────────────────┘
                          │
                          ├── admin suspends ──► SUSPENDED ──reactivate──┐
                          └────────────────────────────────────────────◄─┘
```

| State | Create/edit/post | Read | **Export** |
|---|---|---|---|
| ACTIVE / EXPIRING / GRACE | ✅ | ✅ | ✅ |
| SUSPENDED / READ_ONLY / CLOSED | ❌ | ✅ | ✅ |

**Export works in every state the application opens at all.** The customer's
books are their property; withholding them is wrong, and it is also the main
reason someone would crack the software.

---

## 5. Threat model — stated honestly

**A determined attacker with local admin on their own machine can patch the
binary and defeat any client-side check.** Java bytecode decompiles cleanly. This
is not solvable, and pretending otherwise leads to obfuscation that costs real
engineering, breaks crash diagnostics, and trips antivirus.

| Defended | Not defended |
|---|---|
| Editing the entitlement file (signature) | Patching the binary |
| Replaying an old entitlement (nonce + `issuedAt`) | Memory editing |
| Copying the install to another machine (binding) | Decompilation |
| Rolling the clock back (monotonic anchor) | |

**Non-negotiable:** no licensing failure may destroy, lock away or corrupt
accounting data. Worst case is read-only, and export always works.

---

## 6. Machine binding — composite, fuzzy, and forgiving

Given §2.3 and §2.4, a single hardware value is not viable. Recommended: collect
several weak signals, store **hashes** (never raw values — a MAC address is
personal data), and match on **K of N**.

| Signal | Stability | Notes |
|---|---|---|
| **App install UUID** | highest | written at first run; survives everything but reinstall |
| OS machine id | high | `/etc/machine-id`, `MachineGuid`, `IOPlatformUUID` |
| CPU model + core count | high | changes only on real hardware change |
| Default-route MAC | medium | one interface only, per §2.4 |
| OS install identifier | medium | |

**Match if ≥ K of N agree.** A disk swap or NIC change still matches; a different
computer does not. Below K but otherwise valid → grace state and a silent
re-bind on next contact, rate-limited server-side.

**"One licence = one machine permanently" is preserved**: the *server* enforces
one active binding per licence. Fuzzy matching only prevents a customer being
locked out because they changed a network card.

**Machine replacement** (your locked requirement): admin disables the old
binding, frees the slot, customer activates on the new machine with the same key.
Audited, reason recorded, rate-limited so repeated "replacements" are not licence
sharing in disguise.

---

## 7. Clock tampering

Expiry is evaluated against `max(system_now, last_known_good_time)`, where
`last_known_good_time` advances monotonically in tamper-evident local state. A
backward jump is detected and **cannot defer expiry**. Accumulated runtime from a
monotonic source provides a second floor.

**Deliberately not doing:** a mandatory "phone home every N days". Offline
operation for the whole subscription period is a core promise, and accountants
work in places with poor connectivity.

---

## 8. Scope

**In scope:** licence key format + validation · entitlement token format,
signing, verification · `Entitlement` value object and `moduleState()` ·
composite machine fingerprint · monotonic time anchor · subscription state
machine · activation/renewal **client** with an offline fallback path ·
`app.db` tables (`licence`, `activation`, `entitlement`, `machine_binding`,
`time_anchor`) · read-only enforcement at the use-case boundary.

**Out of scope:** the licence **server** and admin portal (P13) · module
packaging (P4) · cloud backup credentials (P12). P3 defines and consumes the
protocol; the server that speaks it is built later. Until then a local test
harness signs tokens with a development key.

---

## 9. Testing plan

Unit: key format + check character · token parse/verify · tampered payload
rejected · expired/replayed tokens · state machine transitions · K-of-N matching
· time-anchor monotonicity.
Integration: activation → offline operation → expiry → read-only → renewal ·
entitlement survives restart · corrupt entitlement → re-activation, **data
untouched** · clock rolled back a year → expiry still enforced · **export works
in every state**.
Security: every row of `THREAT_MODEL.md` §8.
Regression: all 104 P1/P2 tests still green.

---

## 10. Decisions required

| # | Question | Recommendation |
|---|---|---|
| **P3-1** | Grace period after expiry | **14 days fully functional.** Renewals are late for ordinary reasons; locking an accountant out mid-filing over a late invoice is a support disaster |
| **P3-2** | K and N for fingerprint matching | **K=3 of N=5.** Survives a NIC or disk change; a different machine fails |
| **P3-3** | Automatic re-binds per year before manual approval | **2**, then support |
| **P3-4** | Embed **two** public keys from v1? | **Yes.** Older builds embed only the old key, so rotation is impossible to retrofit. Cheap now, unfixable later |
| **P3-5** | Offline activation/renewal path in v1? | **Yes.** Some customers have no internet on the accounting machine at all |
| **P3-6** | Revalidation interval while running | **On load + every 24h if online.** Never per operation (§2.2) |
| **P3-7** | Behaviour if the entitlement file is missing/corrupt | **Re-activation prompt; company data untouched and still exportable** |
| **P3-8** | Does the licence bind to the installation or to each company? | **Installation.** Companies are books; the licence is for the software |

**P3-1, P3-2 and P3-3 are commercial policy.** P3-4 is technically urgent — it
cannot be added retroactively.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| Locking out a paying customer | Fuzzy matching, grace, silent re-bind, export always works, manual support override |
| Signing key compromise | HSM/KMS, isolated signing, **two public keys from v1** |
| Verification cost in hot paths | Verify once at load; use-case checks read a field |
| Fingerprint drift from Docker/VPN | Do not enumerate all MACs (§2.4); K-of-N |
| Server unavailable at renewal | Grace period; offline path; state unchanged |
| P4 coupling | Modules see only `moduleState(id)` — no licence types leak |
