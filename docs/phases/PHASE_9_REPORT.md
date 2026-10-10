# Phase 9 Report — Indian Taxation

**Status:** IN PROGRESS — **P9a ✅** · P9b–P9e not started
**Design:** `PHASE_9_DESIGN.md` (B-4 and P9-1…P9-4 approved 2026-10-10)

---

## P9a — Rule packs, state codes, GSTIN, GST registration ✅ (2026-10-10)

**Tests:** **657 passing · 0 failing · 0 skipped** with UI on Xvfb (648 + 9 UI)
**Before P9a:** 627 (+30)

### Implemented

| Item | Where |
|---|---|
| **`accubook-tax`** kernel module (in every build, B-7) | new module |
| Rule-pack text format: states, GST slab sets, thresholds, TDS/TCS sections, each effective-dated; threshold types incl. **monthly** (194I) and excess-only (194Q); strict parsing with line numbers; overlaps refused | `RulePack` |
| Signed packs: Ed25519 header over the exact text; own two dev keys, distinct from licence keys (P9-4); private keys only in `~/.accubook-dev/`; `tools/SignRulePack.java` | `SignedRulePack`, `TrustedRulePackKeys` |
| Bundled pack `2025.10.1`, **UNVERIFIED** with cited sources (V-1); signed resource kept identical to the reviewable source by a test | `rulepack/rulepack-2025.10.1.txt` |
| `app.db` migration 2: append-only `rule_pack`; bundled installs at startup; **offline import** of newer signed packs; older/equal refused (no rate rollback); **re-verified on every load** | `RulePacks`, `CompanyService` |
| GSTIN: format + **state from the rule pack** + **Mod-36 check character** (the reference's was never called) | `Gstin` |
| Company migration 10: **effective-dated GST registration** (regular / composition / unregistered), forward-only, never edited, records the pack version | `GstRegistrations` |

### Findings
- **R-17** — the reference's "FY 2025-26" TDS table is stale (194J, 194H,
  194I thresholds; 206C(1H) withdrawn 1 Apr 2025).
- **GST 2.0** — 12% and 28% ended and 40% began on 22 Sep 2025.
- **V-1** release gate: bundled pack data must be verified by a chartered
  accountant; development rule-pack keys must be replaced.
- I-6 and I-7 are payroll questions (P15), not taxation.

### Packaged-artifact verification
`./build.sh --plan basic` → the installed app's first launch installed pack
`2025.10.1` (UNVERIFIED, BUNDLED, key `acb-rulepack-dev-primary`) into `app.db`;
no exception.

### Deferred to P9b
- **Composition eligibility** (turnover limit, special-category states) — needs
  the prior year's turnover and per-state limits; belongs with composition
  documents.
- Validating **party** GSTINs (needed for B2B classification).

### Risks
- Pack content is unverified (V-1). The plumbing is tested; the numbers are not
  authoritative.
- `company_info.gst_no` still exists alongside `gst_registration`; the
  registration table is the source of truth from P9b on.
