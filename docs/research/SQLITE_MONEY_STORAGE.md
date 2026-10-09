# Research: Money Storage in SQLite

Status: RESOLVED BY EXPERIMENT (run locally this session, SQLite 3.50.6)
Decision owner: engineering. Affects every monetary column in the product.

## The problem

The reference application stores money as PostgreSQL `Decimal(18,4)` (139
columns) and does all arithmetic in `Prisma.Decimal`, explicitly avoiding JS
floats. SQLite has **no decimal type**. Its type system is dynamic with column
"affinity", so a column declared `DECIMAL(18,4)` does not do what an accountant
would assume.

## The experiment

```sql
CREATE TABLE t (a NUMERIC, b DECIMAL(18,4), c TEXT, d INTEGER);
INSERT INTO t VALUES ('0.1','0.1','0.1',1);
INSERT INTO t VALUES ('0.2','0.2','0.2',2);
```

Results:

| Query | Result |
|---|---|
| `sum(a)` (NUMERIC) | `0.3` |
| `sum(b)` (declared `DECIMAL(18,4)`) | `0.3` |
| `typeof(a)` | **`real`** |
| `typeof(c)` (TEXT column) | `text` |
| `sum(a) = 0.3` | **`0`  (FALSE)** |
| `sum(d)` (INTEGER minor units) | `3` (exact) |
| `999999999999999999 + 1` | `1000000000000000000` (exact) |

**Findings:**

1. Declaring a column `DECIMAL(18,4)` is *cosmetic*. SQLite applies NUMERIC
   affinity and silently stores an IEEE-754 `real`. The declaration provides no
   precision guarantee and no error.
2. The stored value *prints* as `0.3` while comparing unequal to `0.3`. This is
   the worst possible failure mode for accounting: books that look correct,
   reconcile incorrectly, and produce penny drift that is very hard to trace.
3. `INTEGER` is a signed 64-bit integer and holds 18-significant-digit values
   exactly — comfortably covering `Decimal(18,4)`'s range when scaled.

## Options

### Option A — scaled INTEGER (minor units)
Store `amount * 10^scale` as INTEGER; map to/from `BigDecimal` in Java.

- **Exact.** 64-bit signed range is ±9.22e18; at scale 4 that is ±9.2e14
  currency units — about 922 trillion, far beyond any real book.
- Arithmetic, `SUM()`, comparisons, and ordering are all exact **in SQL**, so
  report aggregation can be pushed into the database.
- Indexes and range queries work naturally.
- Costs: every column needs a known, fixed scale; a scale change is a data
  migration; raw SQL inspection shows `1234500` rather than `123.45`.
- Rates need a different scale from amounts (`Decimal(18,8)` exchange rates,
  `Decimal(5,2)` percentages).

### Option B — TEXT canonical decimal
Store a zero-padded, fixed-scale decimal string; parse to `BigDecimal`.

- Exact and human-readable; arbitrary precision; scale is self-describing.
- **`SUM()` in SQL is impossible** without a custom aggregate — every total must
  be computed in Java after fetching rows. For a trial balance over a large
  journal that is a serious performance and memory regression versus letting
  SQLite aggregate.
- Correct sorting/range queries require careful fixed-width zero-padded
  formatting including negatives (which is error-prone for signed values).

### Option C — REAL
Rejected outright. Demonstrated above to be silently lossy.

### Option D — SQLite `decimal` extension
SQLite ships an optional `decimal` extension providing exact decimal arithmetic
via text. Requires loading a native extension in every deployment (three OSes)
and is not available by default in the common JDBC drivers.
Adds native-library risk to the most safety-critical part of the product for a
benefit Option A already provides.

## Recommendation

**Option A — scaled INTEGER minor units**, with these rules:

1. **Java holds `BigDecimal` everywhere.** `long` appears only inside the
   persistence mapping layer, never in domain or UI code.
2. **Scale is fixed per column class and declared centrally**, mirroring the
   reference schema:
   - money amounts → scale 4 (`Decimal(18,4)`)
   - percentage rates → scale 2 (`Decimal(5,2)`)
   - exchange rates → scale 8 (`Decimal(18,8)`)
3. **Conversion happens in exactly one place** (a `MoneyMapper` / custom JDBC
   type handler). No call site converts by hand — that is how scale bugs get in.
4. **Column naming makes the encoding visible**, e.g. `total_amount_minor`, so
   nobody reads a raw value as rupees.
5. **`RoundingMode` is explicit and centrally defined** — never
   `BigDecimal.divide` without a rounding mode (throws) and never an implicit
   default.
6. A schema-level guard: monetary columns are declared `INTEGER NOT NULL` with
   `CHECK` constraints where a sign is known to be invalid.

## Consequences to carry forward

- Trial balance / P&L / balance sheet aggregation can run as SQL `SUM()` and
  stay exact — this preserves the option of database-side reporting, which
  matters for large books on modest desktop hardware.
- The GST and payroll rounding rules from the reference app must be re-expressed
  as explicit `BigDecimal` + `RoundingMode` operations and pinned by tests
  ported from the existing suite.
- Division (tax back-calculation, allocation, unit cost) is where minor units
  can still lose money to rounding. Every division site needs a defined rounding
  rule and, where the result is split across lines, a documented residual
  allocation strategy so the parts sum exactly to the whole.
