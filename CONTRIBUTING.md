# Contributing

Thanks for helping. This is a Java project; issues and pull requests are welcome.

## Set up and check your change

Needs JDK 21+ with Maven 3.9+ (and Python 3 for `make audit`). Caches stay in git-ignored folders
inside the repo when you pass `-Dmaven.repo.local=.m2/repository`.

```bash
make setup   # resolve dependencies
make format  # apply the project format (palantir-java-format via Spotless)
make lint    # enforcer, format check, javac -Xlint:all -Werror
make test    # full test suite
make demo    # sketch answers next to exact counts on a 1,000,000-line log
make bench   # observed error vs theoretical bounds; fails if a bound is exceeded
make audit   # known-vulnerability check (as in CI)
```

A pull request is ready when `make lint`, `make test` and `make bench` pass and CI is green.

## Guidelines

- Keep each change focused; explain the problem it solves in the pull request.
- Add or update a test for every behaviour change. A change to an estimator or a sketch needs a
  statistical test with fixed seeds and a tolerance derived from the theory, not from one run.
- Update `docs/results/` when numbers change, with the command that produced them.
- Significant design changes need an ADR in `docs/adr/` (copy `0001` for the format).
- Keep function comments to one short phrase; let names and tests explain the rest.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

By contributing you agree that your work is released under the [MIT License](LICENSE).
