# Project Overview

**AccuBook Desktop** — a commercial, offline-first, cross-platform accounting
and ERP application for Indian businesses.

## Purpose

Deliver the accounting capability of the existing AccuBook web application as a
desktop product that works without an internet connection, is sold under
per-plan licences with physically modular builds, and keeps customers' books
safe across updates, plan changes and machine replacement.

## Target customer (first commercial release)

Decided 2026-10-09 (B-1): **traders, wholesalers, distributors, retailers,
service businesses and mixed SMEs** — businesses that file periodically and
routinely enter transactions out of order. Inventory-led manufacturers needing
live per-invoice margin are not the primary target; this is why inventory is
periodic (perpetual quantities, periodic valuation).

## Target platforms
Windows, Linux, macOS — with a bundled Java runtime, so customers never install
Java themselves.

## Technology
Java · JavaFX (OpenJFX) · AWS Corretto · SQLite · JPMS modules ·
jlink + jpackage.

## Relationship to the web application

`/home/achyut-pal/Desktop/accubook` is the **functional reference and source of
truth for accounting behaviour** — and nothing more. It is never modified, and
its implementation is not copied uncritically: the analysis identified 21 defects
and a number of features that exist only as schema. Each is decided explicitly
(see `docs/analysis/DEFECTS_IN_REFERENCE_APP.md`).

Notably, the desktop product is **not** multi-tenant SaaS. It is a single-user
desktop application that may hold several companies' books, which changes the
database, isolation and concurrency design substantially.

## Commercial model

- Per-installation **licence key**, bound to one machine.
- **Plans** (BASIC / PROFESSIONAL / ENTERPRISE) determine which modules are
  packaged and entitled.
- **Subscriptions** are time-bounded; after expiry and a grace period the
  application becomes read-only — never data-destroying.
- Optional **cloud backup** module, encrypted so that we cannot read customer
  books.
- An internal **admin portal** manages customers, licences, machines, plans and
  subscriptions.

## Guiding priorities (in order)

1. Accounting correctness
2. Customer data safety
3. Data isolation between companies
4. Offline reliability
5. Database integrity
6. Upgrade safety
7. Security
8. Licensing and subscription correctness
9. Modularity
10. Testability and maintainability

**The overriding rule:** customer accounting history is never destroyed by a
downgrade, an expiry, an update, a module removal or an installer change.
