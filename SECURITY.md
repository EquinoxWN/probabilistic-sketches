# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security > Report a vulnerability** on this repository
(`https://github.com/EquinoxWN/probabilistic-sketches/security/advisories/new`). Do not open a public issue.

Include what you found, how to reproduce it, and the impact you expect. You will get an answer
within 7 days. Fixes are released as soon as they are ready, and you are credited unless you ask
not to be.

## Supported versions

This project is pre-1.0. Only the latest commit on `main` receives fixes.

## Scope

- **In scope:** input that makes the library or the command-line tool crash, hang or use memory
  beyond its documented limits (log lines over 8,192 characters, client fields over 45
  characters, sketch dimensions, `--exact` above 5,000,000 distinct clients); a Count-Min estimate
  below the true count; `generate` overwriting an existing file.
- **Known limitation, out of scope:** MurmurHash3 is not a cryptographic hash and the seed is
  fixed so results are reproducible. Someone who controls the items can craft collisions that
  inflate a Count-Min estimate or skew a HyperLogLog count. Do not use these sketches for
  decisions an adversary can game (rate limiting, billing) without a secret per-deployment seed.
- **Out of scope:** answers that differ from exact counts by no more than the documented error
  bounds.

## How this repository protects itself

- Every GitHub Action is pinned to a full commit SHA, and workflows run with read-only
  permissions and without persisted credentials.
- Dependabot update pull requests are paused (`open-pull-requests-limit: 0`) so the repository keeps a single `main` branch; dependencies are updated by hand and checked by the CI audit.
- CI runs the format check, `javac -Xlint:all -Werror`, the tests, the end-to-end demo, the
  accuracy bench and an OSV.dev check of every resolved dependency on every push and pull request.
- There are no runtime dependencies; JUnit is the only dependency, in test scope.
