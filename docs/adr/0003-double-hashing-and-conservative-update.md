# ADR 0003: Double hashing and optional conservative update in Count-Min Sketch

- **Status:** Accepted

## Context

The Count-Min bound (`estimate <= true + epsilon * N` with probability `1 - delta`) is proved for
rows hashed by independent, pairwise-independent hash functions. Computing `depth` separate hashes
per update makes the update cost grow with the depth (5 rows for `delta = 0.01`). The plain update
also adds every unit to every row, so heavy items inflate the counters that light items share;
conservative update (Estan and Varghese, 2002) reduces that but is only valid for non-negative
updates. The sketch will also be reused by cache-eviction-lab as a TinyLFU admission filter, which
needs cheap updates, a never-under-count estimate and periodic aging.

## Decision

- Hash each item once with MurmurHash3 x64 128 and derive row `i`'s position from
  `h1 + i * h2` (Kirsch and Mitzenmacher, 2006), mapped into `[0, width)` with
  `Math.unsignedMultiplyHigh`, which uses the high bits and works for any width.
- Offer conservative update as a constructor flag. Reject negative counts in both modes, so the
  never-under-count guarantee holds for every sketch.
- Provide `halve()` for aging: every counter and the total are shifted right by one.

## Consequences

- One hash per update or query whatever the depth.
- Measured on 1,000,000 Zipf updates over 100,000 items (10 trials, 8 configurations): the share of
  items over `epsilon * N` was at most 0.0028%, against `delta = 0.67%`, with zero under-counts in
  5,722,728 estimates. `CountMinSketchTest.meanOverCountOfOneRowMatchesTheExpectedCollisionMass`
  shows a single row spreads items as uniformly as the theory assumes (observed mean over-count
  within 3% of `(N - count)/w`). The independence assumption is still weaker than in the proof;
  this is recorded as a risk in RFC 0001.
- Conservative update roughly halves the mean over-count on these streams (for example 185.2 to
  99.1 at `epsilon = 0.001`, skew 0.8) and lifted top-20 recall from 99.0% to 100.0% there. Its
  cost is a read of every row before writing; `conservativeUpdateNeverExceedsPlainEstimates`
  checks that it never does worse than plain update.
- Turnstile streams (decrements) are not supported; they would need plain update and a
  different heavy-hitter strategy.
- After `halve()` the guarantee applies to the halved counts: every estimate stays at or above
  `floor(true / 2)`, which `halvingAgesCountsWithoutUnderCountingTheHalvedTruth` checks.
