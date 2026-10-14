.PHONY: setup format lint test demo bench audit ci

# MVNLOCAL (set by the author's repoenv helper) keeps the Maven repository inside the repo; empty in CI.
MVN = mvn -B -q $(MVNLOCAL)
PYTHON ?= python3
SKETCH = java -Xmx1g -cp target/classes portfolio.sketches.cli.Main
DEMO_LOG = target/demo/access.log

setup:
	$(MVN) -DskipTests dependency:resolve

# Rewrite sources in the project style (palantir-java-format through Spotless).
format:
	$(MVN) spotless:apply

# Enforcer (JDK 21+, Maven 3.9+), Spotless format check, and javac with -Xlint:all -Werror.
lint:
	$(MVN) -DskipTests compile

# 53 tests (about 10 s): hash reference vectors, HyperLogLog sparse/dense equivalence and accuracy over seeded
# trials, Count-Min bounds, heavy hitters on Zipf streams, input parsing, CLI end to end.
test:
	$(MVN) verify

# One million log lines from 200,000 clients: sketch answers next to exact counts.
demo: lint
	rm -f $(DEMO_LOG)
	$(SKETCH) generate --out $(DEMO_LOG) --lines 1000000 --clients 200000 --seed 42
	$(SKETCH) analyze $(DEMO_LOG) --exact
	rm -f $(DEMO_LOG)

# Observed error vs theoretical bounds (200 HyperLogLog trials per row, 10 Count-Min trials per row).
bench: lint
	$(SKETCH) bench

# Known-vulnerability check of every resolved dependency (test scope included) against OSV.dev.
audit:
	$(MVN) dependency:list -DincludeScope=test -DoutputFile=target/dependencies.txt
	$(PYTHON) scripts/osv_audit.py Maven target/dependencies.txt

ci: setup lint test demo bench
