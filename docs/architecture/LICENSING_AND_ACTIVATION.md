# Licensing, Activation, Subscription & Machine Binding

Status: PROPOSED — awaiting sign-off
Addresses brief §10 (licensing), §11 (subscription), §13 (activation flow),
§14 (machine binding), §30 (machine replacement).

There is **no reference implementation** for any of this — the web app has no
licensing. Everything here is new design.

---

## 1. Threat model — state it before designing

Being honest about what is achievable matters more than claiming strength.

**Who we are defending against, and how well:**

| Adversary | Capability | Can we stop them? |
|---|---|---|
| Honest customer | Uses one machine as agreed | N/A — do not inconvenience them |
| Casual sharer | Copies the installer + data to a second machine | **Yes** — machine binding |
| Clock manipulator | Sets system date back to extend a subscription | **Yes, in practice** — monotonic time-anchor |
| Config editor | Edits a licence file / registry to grant entitlement | **Yes** — signature verification |
| Determined cracker | Patches the binary to skip verification | **No.** Accept this. |

**The last row is the important one.** Any client-side check can be patched out
by someone willing to modify the binary; Java bytecode is especially easy to
decompile. Designing as if this were preventable leads to elaborate obfuscation
that costs real engineering effort, degrades reliability for legitimate
customers, and still fails.

**The realistic goal is therefore:** make casual copying, config editing and
clock rollback ineffective; make cracking require deliberate, knowing
circumvention (which is a legal matter, not a technical one); and never let the
protection break a paying customer's access to their own books.

**Non-negotiable safety rule:** a licensing failure must **never** destroy,
lock away, or corrupt customer accounting data. Worst case is read-only. Data
export must work even in an unlicensed state — the customer's books are theirs.

## 2. Cryptographic core

**Asymmetric signatures. The private key never leaves the licence server.**

- Server holds an **Ed25519** private key (small, fast, no parameter pitfalls).
- The application embeds only the **public key**.
- The server issues a signed **entitlement token** — the client verifies, and can
  never mint one.

This satisfies the brief's §21 rule that the client holds no server secrets. A
symmetric scheme (HMAC with a shared secret) would be fatal: the shared secret
sits in a decompilable jar, so anyone could forge entitlements.

### Licence key vs entitlement token — two different things

**Licence key** — `ACB7-X92K-PQ41-M8ZT`. Human-transcribable, 16 chars in 4
groups. It is an **identifier and activation credential**, not a capability. It
carries no entitlement and grants nothing on its own.

Design notes:
- Use a **Crockford Base32-style alphabet excluding `I`, `L`, `O`, `U`** to avoid
  transcription errors and accidental words.
- Include a **check character** so typos are caught locally before hitting the
  API (better error messages, less support load).
- ~16 usable chars ≈ 80 bits of entropy if random — far beyond guessing.
- Keys are generated server-side, stored hashed, and looked up by prefix.

**Entitlement token** — signed by the server at activation and at each renewal.
This is what the app actually enforces. Contents:

```
licence_id, customer_id, plan
modules[]                    entitled modules
subscription_start, subscription_end
machine_binding_hash         which device this is valid for
issued_at, token_id (nonce)  replay protection
grace_period_days
schema/format version
— Ed25519 signature over all of the above —
```

Stored locally. Verified at startup and before any mutating operation.
Tampering breaks the signature; copying to another machine fails the binding
check; expiry is inside the signed payload so it cannot be edited.

## 3. Machine binding — practical, not brittle

**The failure mode to avoid is punishing legitimate customers.** A single
hardware value (e.g. disk serial) changes on a disk swap, an OS reinstall, or a
motherboard repair, and the customer is locked out of their own accounts on a
Monday morning.

**Recommendation: a composite fingerprint with fuzzy matching.**

Collect several weakly-stable signals, score the match, and re-bind
automatically when it is *close enough*:

| Signal | Stability | Notes |
|---|---|---|
| Machine GUID (Windows `MachineGuid`, Linux `/etc/machine-id`, macOS `IOPlatformUUID`) | High | Changes on OS reinstall |
| Primary MAC address | Medium | Changes with NIC/dock; virtual adapters are noisy |
| CPU model + core count | High | Changes only on real hardware change |
| System disk serial | Medium | Changes on disk replacement |
| OS install identifier | Medium | |
| **App-generated install UUID** | Persistent | Written at first run, survives everything except reinstall |

- Store the **hash** of each signal, never raw values (privacy — a MAC address is
  personal data, and we should not hold hardware inventories of customer machines).
- Match if **≥ K of N** signals agree (e.g. 3 of 5). A disk swap or a NIC change
  still matches; a different computer does not.
- If the score is below K but the licence is otherwise valid, do not hard-fail:
  enter a grace state, prompt the user, and let the app **silently re-bind on
  next online contact** (server-side rate-limited, e.g. N re-binds per year).
  Legitimate hardware drift is thereby invisible to the customer, while a second
  concurrent machine is detected server-side.

**VMs and cloned installations.** A cloned VM reproduces the install UUID and
most signals. Detect it server-side instead: the same `machine_binding_hash`
checking in from **two different network origins in overlapping time windows**
is the signal that matters. Handle it as a support/commercial issue (flag for
review), not an automatic lockout — false positives here are very expensive.

## 4. Clock manipulation — the subscription problem

Local system time is attacker-controlled and cannot be trusted for expiry.

**Defence: a monotonic time anchor with tamper detection.**

Maintain, in tamper-evident local state (signed/HMAC'd with a key derived from
the device binding, and mirrored into each company DB):

```
last_known_good_time      highest wall-clock time ever observed
last_server_time          server time at the most recent contact
monotonic_usage_counter   accumulated runtime, from a monotonic source
```

Rules:
1. On every start and periodically, if `now < last_known_good_time − skew`, the
   clock has moved **backwards**. Treat as tampering: warn, and continue
   enforcing against `last_known_good_time`, not `now`.
2. Never let expiry be *deferred* by a backward clock jump. Expiry is evaluated
   against `max(now, last_known_good_time)`.
3. Accumulated runtime provides a floor independent of the wall clock: a one-year
   subscription cannot yield more than ~one year of measured usage regardless of
   date games.
4. Small forward drift and legitimate timezone/DST changes must be tolerated —
   use a skew allowance (e.g. a few hours) so ordinary clock correction never
   trips a warning.

**Deliberately not doing:** a hard "must phone home every N days" requirement.
The brief is explicit that normal accounting must work offline for the whole
subscription period, and accountants work in places with poor connectivity. The
anchor above achieves the anti-fraud goal without breaking the core promise.

## 5. Expiry behaviour

```
        T−30 days                T (expiry)        T + grace
────────────┼─────────────────────────┼────────────────┼──────────►
   warnings begin              grace period        READ-ONLY
   "expires in 27 days"        still fully usable
```

- **T−30 → T**: escalating but non-blocking warnings. Never a modal that
  interrupts data entry.
- **T → T+grace** (recommend 7–14 days, signed into the token): fully functional.
  Renewals are often a few days late for ordinary commercial reasons; locking an
  accountant out during a filing deadline over a late invoice is a support
  disaster and a reputational one.
- **After grace**: **READ-ONLY**. View, report, and **export** everything. No
  creates, edits, posts, or deletions.

**Export must always work.** The customer's books are the customer's property.
This is both an ethical position and a practical one — it removes any incentive
to crack the software merely to retrieve one's own data.

## 6. Activation & renewal protocol

```
Activation
  Client → POST /activate { licence_key, machine_fingerprint, product, version }
         (TLS 1.3, certificate pinning)
  Server   validate key exists / active / unassigned or assigned to this machine
           validate subscription active; check machine slot free
           bind machine; record activation; write admin audit
  Server → { signed entitlement token }
  Client   verify signature → store → operate offline

Renewal (customer-initiated: "Check Licence / Renew")
  Client → POST /entitlement { licence_id, machine_fingerprint, token_id }
  Server → { fresh signed entitlement token with extended period }
```

**Replay protection.** Each token carries `issued_at` and a `token_id` nonce.
The client refuses a token whose `issued_at` is older than the one it holds, so
an old (longer-lived or higher-plan) token cannot be re-injected.

**Offline renewal path — required, not optional.** Some customer sites have no
internet on the accounting machine at all. Provide a manual flow: the app emits
a short request code; the admin portal produces a signed response string; the
customer types it in. Without this, a genuinely offline customer cannot renew.

## 7. Machine replacement (brief §30)

```
Customer's laptop is destroyed.
  → Customer contacts support.
  → Admin marks MACHINE A disabled (audited, with reason and operator identity).
  → Admin frees the licence's machine slot.
  → Customer installs on MACHINE B, activates with the SAME licence key.
  → Server binds MACHINE B, issues a fresh entitlement.
  → If the backup module was purchased: restore from encrypted cloud backup.
  → If NOT: the data is gone unless they had local backups.
```

That last line deserves emphasis in customer communication: **without the backup
module, a destroyed machine means destroyed books.** This should be stated
plainly at purchase and periodically in-app, not buried. It is also the single
strongest honest reason for a customer to buy the backup module.

Server-side rate limiting on replacements (e.g. N per year, more requires manual
approval) prevents licence-sharing dressed as repeated "replacements".

## 8. Storage of local licence state

- Entitlement token + time anchor stored in the OS-appropriate app data
  directory, integrity-protected by the device-bound key.
- Where available, use the **OS credential store** (Windows DPAPI/Credential
  Manager, macOS Keychain, Linux Secret Service) for the protecting key — with a
  documented fallback for Linux systems without a Secret Service, which is
  common on servers and minimal desktops. **RESEARCH REQUIRED** on the Linux
  fallback's real-world reliability.
- Deleting local licence state must **not** delete company data. Worst case:
  the customer re-activates.

## 9. Deliberate non-goals

- **No obfuscation/anti-debug arms race.** Cost outweighs benefit; it degrades
  crash diagnostics and can trigger antivirus false positives.
- **No hidden telemetry.** Activation and renewal contact the server; that is
  disclosed. Silent phone-home would be a trust and possibly a legal problem.
- **No hardware dongles.** Cost and support burden are disproportionate.

## 10. Open decisions

- Grace period length after expiry. **DECISION REQUIRED** (recommend 14 days).
- Re-bind allowance per year before manual approval. **DECISION REQUIRED.**
- Whether a licence may cover N machines for larger customers — the brief says
  one licence = one machine, but ENTERPRISE customers will ask. **DECISION REQUIRED.**
- Certificate pinning strategy and the rotation plan if the pinned cert must
  change. **RESEARCH REQUIRED** — a botched pin rotation bricks activation for
  everyone.
- Key rotation: how a compromised signing key is replaced given that older app
  versions embed the old public key. Suggest embedding **two** valid public keys
  from day one to make rotation possible at all. **DECISION REQUIRED.**
