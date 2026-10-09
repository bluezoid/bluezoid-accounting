# Money & Rounding

**Status:** RECOMMENDED — **scaled INTEGER storage + `BigDecimal` in code**
**All claims below were verified by experiment** (SQLite 3.50.6, run locally).

---

## 1. The problem, demonstrated

The reference stores money as PostgreSQL `Decimal(18,4)` (139 columns) and does
all arithmetic in `Prisma.Decimal`. SQLite has **no decimal type**.

```sql
CREATE TABLE t (a NUMERIC, b DECIMAL(18,4), c TEXT, d INTEGER);
INSERT INTO t VALUES ('0.1','0.1','0.1',1);
INSERT INTO t VALUES ('0.2','0.2','0.2',2);
```

| Query | Result |
|---|---|
| `typeof(a)` — NUMERIC column | **`real`** |
| `typeof(b)` — declared `DECIMAL(18,4)` | **`real`** |
| `sum(a)` | `0.3` |
| `sum(a) = 0.3` | **`0` (FALSE)** |
| `sum(d)` — INTEGER | `3` (exact) |

**Declaring a column `DECIMAL(18,4)` is cosmetic.** SQLite applies NUMERIC
affinity and stores an IEEE-754 `real` with no error. The value *prints* as `0.3`
while comparing unequal to `0.3` — books that look right and reconcile wrong,
which is the worst failure mode in accounting because it is silent and
cumulative.

## 2. Options compared — with evidence

### Option 1 — scaled INTEGER (minor units) ✅ RECOMMENDED

Store `amount × 10^scale` as INTEGER; map to/from `BigDecimal` in Java.

**Range (computed):** INT64 max is 9,223,372,036,854,775,807.
At **scale 4** that is **₹922,337,203,685,477** ≈ **92.2 million crore**.
India's entire GDP is roughly ₹300 lakh crore, so a single company's ledger
cannot approach this. **Range is a non-issue.**

**Exactness under aggregation (tested):** 10,000 rows of `100` (i.e. ₹0.01 at
scale 4) summed to exactly `1000000`, `typeof` = `integer`.

**Ordering (tested):** `-1000000 | -50000 | 20000 | 100000` — correct.

**SQL aggregation is exact**, so trial balance / P&L / balance sheet can be
computed with `GROUP BY` in the database rather than materialising every row in
memory. This matters: the reference computes all four statements in JavaScript
at **O(ledgers × entries)** — ~10⁸ comparisons for a mid-size book, on every
request. Scaled integers make SQL aggregation both exact and fast.

**Costs:** every column needs a known fixed scale; a scale change is a data
migration; raw SQL shows `1234500` rather than `123.45`.

### Option 2 — TEXT canonical decimal ❌ REJECTED

Two experiments kill it:

```sql
SELECT sum(amt_text), typeof(sum(amt_text)) FROM t;
→ 1234.8678 | real          -- SILENTLY COERCED TO FLOAT
```
**`SUM()` over a TEXT column produces a `real`.** So TEXT gives no protection the
moment you aggregate in SQL — which is precisely what every report does. Avoiding
that requires fetching every row into Java, reintroducing the reference's
performance defect.

```sql
SELECT v FROM o ORDER BY v;
→ -100.00 | -5.00 | 10.00 | 2.00     -- LEXICOGRAPHIC, NUMERICALLY WRONG
```
Correct ordering needs fixed-width zero-padded formatting **including negatives**,
which is error-prone and easy to get subtly wrong.

TEXT is exact only if every operation happens in Java. That is a real
architecture, but it forfeits SQL aggregation and correct indexing for no gain
over Option 1.

### Option 3 — SQLite NUMERIC ❌ REJECTED
Demonstrated above: silently becomes `real`. Provides no precision guarantee.

### Option 4 — SQLite REAL ❌ REJECTED OUTRIGHT
`0.1 + 0.2 ≠ 0.3`. Unacceptable for statutory records. (Note: a *single*
10,000-row sum of `0.01` happened to come back exactly `100.0` in one test —
which is precisely the danger. Float errors are intermittent and
input-dependent, so testing cannot reliably reveal them.)

### Option 5 — INTEGER + BigDecimal ✅ **This is Option 1 done properly**
`long` at the storage boundary, `BigDecimal` everywhere else. Not a separate
option — it is the correct implementation of Option 1.

### Option 6 — SQLite `decimal` extension ❌ REJECTED for v1
Requires loading a native extension on three OSes and is unavailable in common
JDBC drivers. Adds native-library risk to the most safety-critical part of the
product for a benefit Option 1 already delivers.

## 3. Recommendation

**Scaled INTEGER storage, `BigDecimal` in all code, `STRICT` tables.**

### Scale per column class

One global scale does **not** work — money and quantity share `Decimal(18,4)` in
the reference, but exchange rates need 8 dp and percentages 2 dp.

| Class | Scale | Reference basis | Examples |
|---|---|---|---|
| **Money** | **4** | `Decimal(18,4)` ×139 | amounts, balances, totals, tax amounts |
| **Quantity** | **4** | `Decimal(18,4)` | stock qty, BOM qty |
| **Percentage rate** | **2** | `Decimal(5,2)` ×20 | GST %, TDS %, discount % |
| **Exchange rate** | **8** | `Decimal(18,8)` ×4 | FX |
| **Unit conversion** | **6** | `Decimal(18,6)` ×1 | UoM factor |
| **Days** | **1** | `Decimal(4,1)` ×5 | leave days, present days |

### Non-negotiable rules

1. **`BigDecimal` in domain and UI. `long` only inside the persistence mapper.**
2. **One `MoneyMapper`** owns every conversion. No call site converts by hand —
   that is how scale bugs get in.
3. **All tables `STRICT`** — verified to reject `'notanint'` in an INTEGER column,
   so a float cannot even be written into a money column.
4. **Column names carry the encoding**: `total_amount_minor`, `cgst_rate_bp`.
5. **`RoundingMode` is always explicit.** Never `BigDecimal.divide` without one
   (it throws), never an implicit default.
6. **CHECK constraints** where a sign is known invalid (e.g.
   `CHECK (debit_minor >= 0 AND credit_minor >= 0)`).

## 4. Rounding policy — the compliance decision

### GST: the reference has essentially no rounding

Verified: `computeLineGst` does `taxable × rate ÷ 100` with **no**
`.toDecimalPlaces()`, and there is no `Decimal.set({precision, rounding})`
anywhere. A test pins ₹1234.56 @ 5% intrastate → `cgstAmount = "30.864"` —
**three decimal places, unrounded**. Values carry full precision until a single
step at export:

```ts
function n(value: string): number {
  const num = Number(value);
  return Number.isFinite(num) ? Number(num.toFixed(2)) : 0;
}
```

That goes **through IEEE-754 double**, abandoning the Decimal guarantee exactly
at the compliance boundary, and `toFixed` is effectively round-half-even in
practice (`(1.005).toFixed(2) === "1.00"`).

**So `BigDecimal.setScale(2, HALF_UP)` will produce different portal JSON on
exact-half cases.**

| Option | Consequence |
|---|---|
| **(a) Byte-compatible** — replicate double-then-`toFixed` | Matches already-filed returns; knowingly keeps float at the compliance boundary |
| **(b) Correct** — `BigDecimal` + explicit `HALF_UP` | Diverges from history by ≤ 1 paisa per cell; defensible under Rule 34 CGST |

**Recommendation: (b)**, with the existing test vectors deliberately re-baselined
and the divergence documented. **DECISION REQUIRED** — this is a
compliance/business call.

### Aggregation: sum-then-round, not round-then-sum
The reference aggregates at full precision and rounds once at output. This is
correct for GST (the portal reconciles on totals) and **must be preserved**, or
every return cell drifts by paise.

### Payroll: float math, and it is load-bearing
`payroll-calculations.util.ts` uses **11 × `Math.round` and zero Decimal** — the
only float money path in the system. Worse, `post-month` **deliberately
re-derives** employer contributions with the same float math to keep JV totals
"byte-identical" to the payslip.

Recommendation: `BigDecimal` with each existing `Math.round` transcribed as an
explicit `setScale(0, HALF_UP)` at the same point, **validated against a real
payroll run before go-live**. Statutory filings make silent divergence
unacceptable. **DECISION REQUIRED.**

### Division and allocation
Every division (tax back-calculation, unit cost, allocation across lines) needs:
1. an explicit `RoundingMode`, and
2. a **documented residual strategy** so split parts sum exactly to the whole
   (allocate the remainder to the largest line, or the last, but decide and test it).

### Round-off ledger
`Invoice.roundOff` exists in the reference, is read by e-invoice, and is **never
written by any path** — Section 170 round-off-to-nearest-rupee is unimplemented.
The desktop product should implement it properly, posting the difference to the
`Round Off` ledger.

## 5. Export / import

- Exports (CSV/XLSX) render from `BigDecimal` with an explicit scale and locale
  — never from the raw `long`.
- Imports parse to `BigDecimal` and go through the same mapper; a value that
  cannot be represented at the column's scale is **rejected, not truncated**.
- Portal JSON (GSTR) uses the rounding policy decided above, applied at exactly
  one serialization point.

## 6. Testing

- Property test: `BigDecimal → long → BigDecimal` round-trips exactly at every
  scale, including negatives and boundary magnitudes.
- Property test: a long chain of additions in SQL equals the same chain in
  `BigDecimal`.
- Boundary test: values near INT64 limits are rejected cleanly, not silently
  wrapped.
- Regression: the reference's pinned tax vectors (e.g. `30.864`) reproduce, or
  are deliberately re-baselined with the divergence recorded.

---

## 7. Extended verification (independent research pass)

Confirmed against SQLite 3.53.4 via the xerial JDBC driver on Corretto 17.

### 7.1 Scale limits, computed exactly

| Scale | Max rupees | Indian notation |
|---|---|---|
| 2 | 92,233,720,368,547,758.07 | ~9.2 × 10⁹ crore |
| **4** | **922,337,203,685,477.5807** | **~922 lakh crore** |
| 6 | 9,223,372,036,854.775807 | ~9.2 lakh crore |
| 8 | 92,233,720,368.54775808 | ~9,223 crore |

Scale 4 is ~3× India's GDP. **No realistic overflow.**

### 7.2 One global scale is NOT workable — confirmed

- Scale 8 everywhere caps money at ~₹9,223 crore → a ₹10,000 crore turnover
  **overflows**.
- Scale 4 everywhere gives exchange rates only 4 dp → unusable.

Per-class scales are mandatory, exactly as recommended in §3.

### 7.3 The real overflow risk is intermediate products, not stored values

```
money_e4 × fx_e8 = 10000000000 × 8312345678 = 8.31e19
```
That **overflows INT64 by ~9×** and SQLite silently degrades it to REAL.

**Rule: never multiply money by a rate in SQL. FX and percentage
multiplication happen in Java `BigDecimal`.** SQL is for storage, filtering,
and `SUM`/`GROUP BY` of same-scale values only.

Overflow behaves three different ways, and **two are silent**:
- `sum()` overflow → hard error ✅
- `max_int64 + 1` → silently becomes REAL ⚠️
- oversized literal into an INTEGER column → silently stored as `real` ⚠️
  (**STRICT catches this one**)

### 7.4 TEXT is worse than previously documented

Beyond the lexicographic-ordering and `SUM()→real` problems already recorded:

- **Non-numeric text silently sums to 0.** `'1,234.56'`, `'Rs 500'`, `'abc'`
  and `''` all contributed **0** with no error. A malformed import would
  silently understate the books.
- A range query `BETWEEN '-100.0000' AND '10.0000'` returned an out-of-range
  row and **missed two in-range rows**.

### 7.5 REAL is dangerous precisely because it usually looks right

SQLite 3.43+ uses Kahan-Babuška compensated summation, so naive column sums
often come out exact — which **masks** the failure until it matters:

```sql
SELECT sum(qty*rate) FROM lines;      -- displays 3000, but = 3000.0 is FALSE
SELECT sum(qty*rate)*0.18 ...         -- 540.0000000000001  (18% GST)
```
Every row **displays** as equal while comparing unequal (diffs ~1e-16). And at
₹1.23 lakh crore with 4 dp, REAL loses **four rupees** outright.

### 7.6 The `decimal` extension is a dead end — confirmed

`decimal_sum()` / `decimal_add()` exist in some CLI builds but are **NOT
compiled into the xerial JDBC driver** (`no such function: decimal_sum`).
Any design relying on exact TEXT decimal arithmetic in SQL is unimplementable
without a custom native build. **Do not design around it.**

### 7.7 STRICT tables — exact enforcement behaviour

`DECIMAL(18,4)` and `NUMERIC` are **illegal** in STRICT tables
(`unknown datatype`). Only INT, INTEGER, REAL, TEXT, BLOB, ANY are allowed —
which conveniently makes the wrong choice unrepresentable.

Into a STRICT INTEGER column:

| Inserted | Result |
|---|---|
| `100.0` | accepted, stored `integer` |
| `'100'` | accepted, stored `integer` |
| `100.5` | **rejected** — `cannot store REAL value in INTEGER column` (code 19) |
| `'100.5'` | **rejected** |

Via JDBC, `setBigDecimal(new BigDecimal("123.4567"))` into a STRICT INTEGER
column was correctly **rejected**.

### 7.8 The verified Java boundary pattern

```java
// write — throws ArithmeticException if the value has more precision than the column
ps.setLong(2, amt.movePointRight(4).longValueExact());

// read
BigDecimal back = BigDecimal.valueOf(rs.getLong(1), 4);
```

`longValueExact()` makes scale violations **fail loudly** instead of silently
truncating. Verified: `new BigDecimal("1.23456")` at scale 4 → rejected with
`ArithmeticException: Rounding necessary`.

End-to-end verification: a trial balance over **90,000 journal lines** summed to
`tot_dr = tot_cr = 35411790000`, **difference exactly 0**.

### 7.9 Never scale back in SQL

```sql
SELECT amt_e4/10000.0 ...   -- WRONG: that division is float
```
This printed `922337203685477.6000` instead of `922337203685477.5807`.
**Always scale back in Java.** Rounding in SQL, where unavoidable, must be done
in integer arithmetic: `(a*b + 500000)/1000000`.

### 7.10 Final schema pattern (verified working)

```sql
CREATE TABLE journal_line (
  id           INTEGER PRIMARY KEY,
  account_id   TEXT NOT NULL,
  amount_e4    INTEGER NOT NULL
                 CHECK (amount_e4 BETWEEN -10000000000000000 AND 10000000000000000),
  gst_rate_bp  INTEGER NOT NULL CHECK (gst_rate_bp BETWEEN 0 AND 10000),
  fx_rate_e8   INTEGER NOT NULL CHECK (fx_rate_e8 > 0)
) STRICT;
```

Naming convention: `_e4` / `_e8` / `_bp` suffixes make the encoding visible at
every call site and in every ad-hoc query.
