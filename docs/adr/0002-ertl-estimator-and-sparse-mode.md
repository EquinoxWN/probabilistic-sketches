# ADR 0002: Ertl's estimator and an exact sparse-to-dense conversion for HyperLogLog

- **Status:** Accepted

## Context

The original HyperLogLog estimator (Flajolet et al., 2007) is biased for small cardinalities, so
it switches to linear counting below `2.5m`. Around that switch both estimators are poor: the
measured RMSE of the classic estimator at `p = 12, n = 10,000` is 2.870%, against 1.245% for the
estimator chosen here and 1.625% in theory. HyperLogLog++ (Heule et al., 2013) fixes this with
empirical bias tables (about 200 interpolation points per precision) and a per-precision
threshold. Separately, a dense sketch spends `m` bytes even when only a handful of items were
added, and for small sets its error (linear counting over `m` buckets) is far larger than needed.

## Decision

1. **Dense estimate:** use Ertl's improved estimator ("New cardinality estimation algorithms for
   HyperLogLog sketches", 2017). It works on the register histogram `C[0..q+1]` with two
   convergent series, `sigma` for empty registers and `tau` for saturated ones, and has no
   switch point and no tables. The classic estimator stays available as `classicEstimate()` so the
   difference can be measured.
2. **Sparse form:** store each hash as a 31-bit int, the top 25 bits as a fine index and the
   leading-zero run of the other 39 bits, in a sorted, de-duplicated list fed through a small
   buffer; estimate with linear counting over 2^25 buckets; convert to dense registers when
   `4 * (entries + buffer) > m`.
3. **Exact conversion:** a sparse entry maps to dense register `fineIndex >>> (25 - p)`. If the low
   `25 - p` bits of the fine index are not all zero, the dense run length is the position of their
   first one bit; otherwise it is `(25 - p) + fineRun`. This is exactly the run length dense
   insertion computes from the same hash, so a converted sketch is identical to one that was dense
   from the start.

## Consequences

- No bias tables in the code; the estimator is about 30 lines. Measured over 200 trials per row,
  RMSE stays at or below `1.04/sqrt(m)` for p = 12 and 14 up to n = 100,000 and at most 1.059x it
  anywhere (p = 10, n = 100,000), while the classic estimator reaches 1.766x at the switch.
- Small sets are near exact: RMSE 0.000% at 100 items and 0.012% at 1,000 items (p = 14), using
  2,448 and 6,048 bytes instead of 16,384.
- `HyperLogLogTest.sparseToDenseConversionGivesExactlyTheDenseRegisters` checks register-for-
  register equality for every precision from 4 to 18, including hashes whose low 39 bits are zero;
  a mutation that shifts the converted run length by one is caught there.
- Cost: the sparse buffer is sorted on every flush (`m/32` entries), and computing an estimate
  while sparse flushes the buffer first. Estimating is O(m) in the dense form because the histogram
  is rebuilt each time; callers who estimate after every insert pay for it.
- Merging two sketches (M3) has to handle sparse + sparse, sparse + dense and dense + dense; the
  exact conversion makes the mixed case a conversion followed by a register-wise maximum.
