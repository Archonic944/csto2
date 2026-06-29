# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CSTO v2 (`com.csto:csto2`) is a **comprehension-driven test-order optimizer**: a research tool that
reorders a Java project's test **classes** to reduce total suite wall-clock by exploiting JVM
warmup/JIT/GC/allocation state that carries across classes run **in a single JVM**. It targets
*external* projects (siblings under `~/Development/Research/` — `commons-csv`, `javaparser`,
`jackson-core`, `paimon`, …); this repo has no tests of its own. The whole tool is one shaded jar
driven by subcommands.

It is a measurement instrument first and an optimizer second: every proposed order is **re-measured
under controlled A/B** and only shipped if it is *both* faster than the as-given baseline *and* fully
green (zero failures/timeouts). It never drops tests and never ships a regression.

### Orientation memory (read before claiming results)

`~/.claude/.../memory/MEMORY.md` records hard-won, project-specific facts: which target subjects
actually have orderable headroom, which levers worked on which suite, and the measurement
discipline. Key vocabulary used throughout the code and reports:

- **initial** — the as-given/default order. The baseline; all speedups are reported "vs initial."
- **naive** — the fastest of the trivially-traced orders (fastest-observed). This is a *free*
  baseline, **not** an optimizer win. A real win must beat naive, not just initial.

The hardest-won lesson (encoded in `Candidates`' design comments and the memory): **do not trust a
single model.** Generate many candidate orders from competing hypotheses, measure them all, and let
the green gate pick the winner. A mechanism that helps one suite often hurts another (a global
alloc-sort gains ~+5% on alloc-bound jackson/paimon but loses ~-17% on locality-bound javaparser).

## Build & run

```bash
mvn -q package        # -> target/csto2.jar (shaded, Main-Class: com.csto2.Csto2)
                      #    target/csto2-agent.jar (slim -javaagent, no WALA)
java -jar target/csto2.jar          # interactive REPL (point it at a Maven module)
java -jar target/csto2.jar <cmd> …  # single subcommand
```

Java 17 source/target. Compile deps: **WALA** (`com.ibm.wala.core` 1.7.2) for the static half, and
`junit-platform-launcher` (provided) for the agent's listener. No unit-test suite here — validation
is empirical, against the external target projects.

**Prerequisite — the Surefire testorder fork.** All measurement runs through real Maven Surefire via
the [TestingResearchIllinois/maven-surefire](https://github.com/TestingResearchIllinois/maven-surefire)
fork (`modifiedRunOrder-ext` branch), which adds `-Dsurefire.runOrder=testorder` (run exactly the
classes listed in `-Dtest`, in order). Build & install it once:
`mvn install -DskipTests -Drat.skip -Denforcer.skip` in the fork. That installs `3.0.0-M8-SNAPSHOT`
surefire + the `surefire-changing-maven-extension` jar into `~/.m2`; csto2 auto-locates the extension
there (`Csto2.defaultSurefireExt`), loads it per-invocation via `-Dmaven.ext.class.path` (no global
Maven mutation), and the extension forces single-fork (`forkMode=once`, carryover preserved) while
keeping the project's own `argLine`/`systemPropertyVariables`.

## The two halves

CSTO blends a **static** comprehension pass (hypotheses, cheap) with a **dynamic** tracer (confirms
and quantifies, authoritative). The static side proposes interaction edges; only the dynamic side's
measurements are ever trusted to ship an order.

### Static half — `analyze/`

- **`StaticComprehension`** builds a WALA class hierarchy over the target's **app** classpath
  (Application loader) plus optional **lib** classpath (Extension loader). For each test class it
  walks the app-scope call graph from the test's declared methods, **stopping at library/JDK
  boundaries** (libraries are summarized, not traversed; `MAX_METHODS_PER_TEST = 8000` guard). Per
  test it records: app static field reads/writes (state-pollution candidates), library statics
  touched, resource-shaped string constants, app types referenced (locality), and a `<clinit>` cost
  proxy. Per-method facts are cached (methods are shared across tests). Output: `static-facts.jsonl`,
  one JSON object per test.
- **`StaticEdges`** derives candidate interaction edges from those facts (all *hypotheses*):
  `pollutionEdges` (A writes a static field B reads), `sharedResourceEdges` (shared library
  touchpoints, IDF-weighted so only *rare* shared resources score), `localityEdges` (shared app
  types). Ubiquitous test-infra namespaces (JUnit/Mockito/Hamcrest/…) are filtered out. Output:
  `static-edges.json`.

### Dynamic half — `surefire/` + `agent/` (was the in-JVM TraceRunner, now retired)

Measurement runs through **real Maven Surefire**, so timing and greenness match `mvn test`. (The
former reflective in-JVM `TraceRunner` was retired — it ignored the project's Surefire-configured
system properties/argLine, so e.g. Avro's serialization tests failed under it but pass under Surefire.)
The two backends share the `OrderRunner` interface (`runOrder` / `run`).

- **`SurefireOrchestrator`** (`com.csto2.surefire`) — runs each order with
  `mvn surefire:test -Dmaven.ext.class.path=<ext> -Dsurefire.runOrder=testorder -Dtest=<orderfile>`
  in the target **module dir** (so the project's own classpath/config apply). Per-class **runtime +
  pass/fail** are parsed from Surefire's own XML reports (`target/surefire-reports/TEST-*.xml`);
  position comes from the order file. It also injects the instrumentation agent and **merges** the
  agent's per-class facts into the report rows by class name → the same `trace.jsonl` schema the
  optimizer consumes.
- **`agent/Csto2Agent` + `Csto2Listener`** (built as the slim `csto2-agent.jar`) — a `-javaagent`
  injected into the Surefire fork via the extension's `KP_ARGLINE` env var. In `premain` it appends
  its own jar to the system classloader (so the JUnit Platform ServiceLoader discovers the listener)
  and starts JFR. The `TestExecutionListener` records, per top-level class (depth-counting collapses
  `@Nested`), the durable-state deltas: `classesLoaded`, `jitMs`, `gcCount`/`gcMs`, `allocBytes`,
  `threadDelta` — plus per-class JFR facts via `JfrProbe`. Works for JUnit 5 and JUnit 4-via-vintage.
- **`JfrProbe`** (`com.csto2.trace`, reused by the agent) — records a timestamped JFR *event stream*
  (GC, ClassLoad, Compilation, allocation samples) and **bins each event into the test running when it
  fired** (binary search over per-class windows the listener marks). This distinguishes what MXBean
  counters confound: young vs **old/full** GC (full GCs are placement-sensitive), and **unique
  first-loads** vs shared class loads. Output: `jfr/<order>.jfr.jsonl`, one facts row per class.
- **`TestDiscovery`** (`com.csto2.trace`) — the surviving piece of the old TraceRunner: reflection-only
  class filtering (concrete + has an `@Test`/`@RunWith`/TestNG marker, inherited included). Never
  executes tests, so no fidelity issue. Used by the `discover` command via `--discover` (and
  `--explain <class>` as a failure-inspection aid).

> **Caveats:** the agent's classpath-append relies on `useSystemClassLoader=true` (Surefire default).
> `-Dtest` listing a class *overrides* the pom's `<excludes>`, so discovery does not yet honor excludes.

## Pipeline (the `Csto2` subcommands)

Each is a method behind the `switch` in `Csto2.main`. The measuring commands (trace/select/validate/
pairwise) build their runner via `makeRunner`, which is **Surefire-only** and needs the testorder fork
(`--surefire-ext`, else auto-located in `~/.m2`) + the agent (auto-located beside `csto2.jar`, or
`--agent`). Common flags: `--cp` (target classpath; used to infer the module dir), `--tests <file>`,
`--out <dir>`, `--workdir <module dir>`, `--mvn <bin>`.

**Agent is for discovery, not for the ship decision.** `makeRunner(…, attachAgent)` takes a flag.
Discovery phases (trace, JFR classification, producer→consumer pair confirmation) attach the agent —
they *need* the per-class alloc/jit/gc/JFR facts. But the agent's JFR recording + listener add real
overhead that perturbs wall-clock, so the final A/B **validation** of promising orders runs the agent
*off* (`select`'s ship-gate measurement loop and `validate`'s interleaved repeats). Those phases only
read `runtimeMs`/`status`, never agent facts, so dropping the agent gives a cleaner timing comparison
without losing anything.

1. **`analyze --app <cp> [--lib <cp>] --tests <file> [--out <dir>]`** — static pass → `static-facts.jsonl`
   + `static-edges.json`. (Uses `--app`/`--lib`, **not** `--cp`. In-process WALA, no Surefire.)
2. **`discover --cp --tests --out`** — runs `TestDiscovery --discover` in the target JVM to filter a
   candidate list down to actually-runnable test classes (reflection only).
3. **`trace --cp --tests [--orders N=6] [--seed=1] [--out=.csto2/trace]`** — runs N orders through
   Surefire+agent → `trace.jsonl` + `jfr/` facts (JFR is always on via the agent). Calibration data.
4. **`validate --cp --tests --trace <trace.jsonl> [--repeats=5]`** — calibrates the **slope model**
   (`OrderOptimizer`), emits `initial` vs `optimized` (slope-sorted) orders, and measures them
   **interleaved per repeat** (spreads background noise evenly), then reports median speedup.
5. **`select --cp --tests --trace [--jfr-dir] [thresholds…]`** — the **main ship gate** (see below).
6. **`pairwise --cp --trace --facts <static-facts.jsonl> [--repeats=5] [--consumers=12]`** — dynamic
   producer→consumer warm-pair discovery on the stable-passing subset; compares `initial` / `slope` /
   `pairwise` orders. Uses static `staticReads` only to *narrow* candidate producers, then confirms
   each pair by direct measurement.

## Candidate-generation strategies (there is NO single optimizer)

`select` is a portfolio: it builds many candidate orders from independent hypotheses, measures them
all, and ships the fastest green one (else falls back to `initial`). Each strategy targets a distinct
mechanism. The full set:

**From `optimize/Candidates` (`generate`):**
- `initial` — as-given (protected incumbent).
- `naive` — fastest trivially-observed traced order (the free baseline to beat).
- `alloc-front` — heavy allocators (≥ `--heavy-alloc-mb`, default 500) moved to front, descending;
  everything else keeps initial order (minimal perturbation).
- `warm-tail` — high-confidence cold-sensitive classes (steep negative position-slope ≤ `--cold-slope`,
  low residual ≤ `--max-resid`, not heavy) moved to the tail.
- `alloc-front+warm-tail` — both of the above.
- `intra-warmup` — keeps package blocks **atomic and in original order** (preserves cross-package JIT
  locality) but inside each package runs the coldest-first (by intercept = est. cost at position 0).
  The lever for suites whose natural order is already locality-optimal (e.g. javaparser).
- `pkg-alloc-front` / `pkg-rt-front` — sort whole **package blocks** by aggregate alloc / runtime,
  preserving original order *within* each block. Middle ground between local perturbation and a
  destructive global sort.
- `pkg-alloc+observed-intra` — package blocks sorted by alloc, but intra-package order taken from the
  fastest traced order.
- `alloc-sort` — full global sort by allocation descending. Gains on alloc-bound suites, breaks
  locality-bound ones — safe only because the green gate filters it.
- `jit-front` — full global sort by per-test compilation time (`jitMs`) descending. The lever for
  **JIT-bound** suites (e.g. jackson-core, where ~8.5s of a 12s run is compilation).

**Added by `select` itself:**
- `pairwise-warm` — producer→consumer cache-warming pairs, **mined** from the trace
  (`detectPairs`: a true warmer makes the consumer shed allocation when it precedes it) then
  **causally confirmed** (`confirmPairs`: run `[P,C]` vs `[C,P]` in isolated fresh JVMs; keep only if
  the consumer really sheds allocation). Applied as a minimal perturbation of initial. Trace
  co-occurrence alone confounds correlated predecessors, so the probe is mandatory.
- `jfr-gc-front` / `jfr-warmup-front` / `jfr-gc+warmup-front` (`optimize/JfrClassifier`) — only when
  a JFR facts dir exists. Classifies tests by **mechanism** from aggregated JFR facts:
  `GC_CARRIER` (does real old/full GCs → wants a fresh low-occupancy heap → run early),
  `WARMUP_SHAREABLE` (first-loads many classes whose unique-load count *collapses* when it runs later,
  proving the classes are shared → warming them early helps everyone), vs `FIXED_WARMUP`/`INERT`
  (exclusive or negligible → leave in place). Fronts the carriers accordingly.

**The slope model (`optimize/OrderOptimizer`)** is used by `validate` and `pairwise` (not `select`):
it fits per-class `runtime(pos) = intercept + slope·pos` (ridge-regularized, `RIDGE=50`) and sorts by
**slope descending**. By the rearrangement inequality this single rule both front-loads
positive-slope allocators and tail-loads negative-slope warmup classes. `Pairwise` then layers
dynamically-probed producer-before-consumer constraints on top of the slope order.

### The selection/green gate (`select` → `selectReport`)

Candidates are measured over `--repeats` rounds; within each round the candidate order is
**seeded-shuffled** so no candidate is pinned to the same slot every round (decorrelates slot-bias
like OS file-cache warming from the candidate). Reported per candidate: median/min/max total runtime
and **greenness** (any non-PASS status across runs disqualifies it). Ship rule: the fastest
**fully-green** candidate, and only if it beats `initial` by **>1% margin** — otherwise ship
`initial`. A regression or a flaky order can never win.

## Data artifacts & schemas

- `trace.jsonl` / measure files: `orderId, position, test, runtimeMs, status, failures, testsFound`
  (from Surefire reports) merged with `classesLoaded, jitMs, gcCount, gcMs, allocBytes, threadDelta`
  (from the agent, by class name). `SurefireOrchestrator.parseReports` + `mergeAgentFacts`.
- `agent/<order>.facts.jsonl` (`Csto2Listener` rows): `test, position, agentRuntimeMs, classesLoaded,
  jitMs, gcCount, gcMs, allocBytes, threadDelta` — the agent's raw per-class output, pre-merge.
- `jfr/<order>.jfr.jsonl` (`JfrProbe` rows): `orderId, position, test, gcYoung, gcOld, gcPauseMs,
  classLoads, uniqueClassLoads, compilations, allocTop`.
- `static-facts.jsonl` (`StaticComprehension`): `test, clinitProxy, methodsVisited, staticWrites,
  staticReads, libResources, resourceConstants, appTypes`.
- `static-edges.json` (`StaticEdges`): `pollutionEdges, sharedResourceEdges, localityEdges, counts`.

JSON I/O is hand-rolled and deliberately minimal: `util/Json` writes; `optimize/MiniJson` parses a
single **flat** object (it *skips* nested arrays/objects, returning null) — array fields like
`staticReads` are re-extracted by substring scanning (`Pairwise.extractArray`). Keep emitted rows flat
and parseable by these.

## Invocation flags

Under the Surefire backend the target's **own** pom drives its JVM config (`argLine`,
`systemPropertyVariables`, toolchains, etc.) — Surefire applies them, so most per-target JVM tuning is
no longer csto2's job. Remaining knobs:

- `--surefire-ext <jar>` — testorder-fork extension (optional; auto-located in `~/.m2`).
- `--agent <jar>|none` — instrumentation agent (optional; auto-located beside `csto2.jar`; `none`
  disables it for runtime+status-only measurement).
- `--mvn <bin>` — mvn binary/wrapper (default `./mvnw` if present, else `mvn`).
- `--kp-argline "<args>"` — extra args prepended to the fork's argLine (on top of the agent).
- `--workdir <dir>` — the Maven **module dir** to run (normally inferred as the parent of
  `target/test-classes`).
- `--jvmargs "<args>"` / `--java <home>` — only affect the `discover` helper JVM now, not measurement.

`select` thresholds: `--heavy-alloc-mb` (500), `--cold-slope` (-1.0), `--max-resid` (300),
`--pair-consumer-mb` (1000), `--pair-producer-mb` (200), `--pair-drop-frac` (0.25), `--jfr-dir`,
`--jfr-min-loads` (200), `--jfr-min-share` (0.3). (These warmup thresholds are deliberately low — the
green gate filters any over-eager candidate, so casting a wider net for shareable-warmup carriers only
costs an extra candidate measurement, never a regression.)

## Methodology constraints (load-bearing — these are why the tool is shaped this way)

- **Never claim a runtime/behavior win from static reading.** Measure with a controlled A/B; control
  confounds (interleave repeats, shuffle within-round slots, hold the green gate).
- A real win must beat **naive**, not just `initial`.
- **Honor the target's Surefire excludes** when discovering tests — globbing `*Test` can pick up an
  excluded slow perf test and fake an order effect. Reconcile the measured suite against `mvn test`.
- **Diagnose the dominant cost first** (GC vs JIT vs compute) before choosing a lever — the surface
  signal lies (jackson looked alloc-heavy but was JIT-bound; `jit-front` won there).
- A new ordering mechanism **must be re-tested on other targets** before trusting it — helping one
  suite is often overfitting. When a new mechanism proves out, fold it back in as another candidate
  strategy in `Candidates`/`JfrClassifier` rather than wiring it as "the" optimizer.
- While prototyping use **2–3 measurement rounds**; reserve 5+ for final winner confirmation.
- Treat every suite as optimizable; never declare one "defended" — dig into the mechanism.
