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
mvn -q package                       # -> target/csto2.jar (shaded, Main-Class: com.csto2.Csto2)
java -jar target/csto2.jar <cmd> ...
```

Java 17 source/target. Single runtime dependency: **WALA** (`com.ibm.wala.core` 1.7.2) for the
static-analysis half. There is no unit-test suite here — validation is empirical, against the
external target projects. `run-alloc-front.sh` is a scratch example of invoking `TraceRunner`
directly with a chosen order file.

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

### Dynamic half — `trace/`

- **`TraceRunner`** is the in-JVM workhorse. Given an order file, it runs each test class **in one
  JVM, sequentially**, and at each class boundary records durable-state deltas from platform MXBeans:
  runtime, `classesLoaded`, `jitMs` (total compilation time), `gcCount`/`gcMs`, `allocBytes`
  (HotSpot per-thread allocated bytes), `threadDelta`, plus `testsFound`/`failures`/`status`. One
  JSON row per class, **flushed immediately** (partial data survives a hang). Crucial behaviors:
  - Each class runs on a **non-daemon worker thread with a wall-clock timeout** (`--class-timeout-ms`).
    A hung test is parked (not killed) so it can't block the rest; `Runtime.halt(0)` at the end reaps
    everything. Non-daemon + halt matches Maven Surefire fidelity (spawned threads inherit the daemon
    flag; some tests assert on it).
  - **Runner auto-selection**: JUnit Platform (JUnit 5) preferred, JUnit 4 (`JUnitCore`) fallback —
    both invoked **reflectively** (no compile-time JUnit dependency), so it runs against whatever the
    target has.
  - `--discover` mode filters a candidate class list to real runnable tests (concrete + has an
    `@Test`/`@RunWith`/TestNG marker, including inherited), mirroring what Surefire would run.
  - `--explain <class>` prints per-test failures for one class (debugging aid).
  - Optional `--jfr <dir>` attaches a `JfrProbe`.
- **`JfrProbe`** records a timestamped JFR *event stream* (GC, ClassLoad, Compilation, allocation
  samples) and **bins each event into the test that was running when it fired** (binary search over
  per-test execution windows). This distinguishes mechanisms that MXBean counters confound: young vs
  **old/full** GC (full GCs are the placement-sensitive expensive ones), and **unique first-loads**
  (one-time cost) vs shared class loads. Output: `<order>.jfr.jsonl`, one facts row per test.
- **`TraceOrchestrator`** generates orders and spawns `TraceRunner` **once per order in a fresh child
  JVM**. Order 0 is `initial`/as-given; the rest are seeded shuffles. It sets the child JVM's
  **working directory** to the target test module's basedir (inferred as the parent of
  `target/test-classes`) so tests that resolve relative resource paths pass like Surefire does.
  `runOrder()` re-runs a single named order, used heavily by the selection/probe phases.

## Pipeline (the `Csto2` subcommands)

Each is a method behind the `switch` in `Csto2.main`. Common flags: `--cp` (target test+runtime
classpath; filtered to existing entries), `--tests <file>` (FQ class names, one per line, `#`
comments), `--out <dir>`, `--class-timeout-ms`, and the child-JVM controls below.

1. **`analyze --app <cp> [--lib <cp>] --tests <file> [--out <dir>]`** — static pass → `static-facts.jsonl`
   + `static-edges.json`. (Uses `--app`/`--lib`, **not** `--cp`.)
2. **`discover --cp --tests --out`** — runs `TraceRunner --discover` in the target JVM to filter a
   candidate list down to actually-runnable test classes.
3. **`trace --cp --tests [--orders N=6] [--seed=1] [--jfr] [--out=.csto2/trace]`** — runs N orders in
   fresh JVMs → `trace.jsonl` (+ `jfr/` facts when `--jfr`). This is the data the optimizers calibrate on.
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

- `trace.jsonl` / measure files (`TraceRunner` rows): `orderId, position, test, runtimeMs,
  classesLoaded, jitMs, gcCount, gcMs, allocBytes, threadDelta, testsFound, failures, status[, error]`.
- `jfr/<order>.jfr.jsonl` (`JfrProbe` rows): `orderId, position, test, gcYoung, gcOld, gcPauseMs,
  classLoads, uniqueClassLoads, compilations, allocTop`.
- `static-facts.jsonl` (`StaticComprehension`): `test, clinitProxy, methodsVisited, staticWrites,
  staticReads, libResources, resourceConstants, appTypes`.
- `static-edges.json` (`StaticEdges`): `pollutionEdges, sharedResourceEdges, localityEdges, counts`.

JSON I/O is hand-rolled and deliberately minimal: `util/Json` writes; `optimize/MiniJson` parses a
single **flat** object (it *skips* nested arrays/objects, returning null) — array fields like
`staticReads` are re-extracted by substring scanning (`Pairwise.extractArray`). Keep emitted rows flat
and parseable by these.

## Child-JVM invocation flags (for finicky targets)

Targets often need their JVM tuned to run green; these pass through to the child test JVMs:

- `--jvmargs "<args>"` — extra child JVM args as **one value** (the parser consumes the whole next
  token even though it starts with `--`). E.g. paimon needs `--add-opens …` plus
  `-Djava.security.manager=allow`.
- `--java <JAVA_HOME | /path/to/java>` — pin a JDK (e.g. JDK 17 for targets that reject newer).
- `--workdir <dir>` — override the inferred child cwd when relative test resource paths fail.
- `--class-timeout-ms` — per-class wall-clock timeout (orchestrated commands default 30000).

`select` thresholds: `--heavy-alloc-mb` (500), `--cold-slope` (-1.0), `--max-resid` (300),
`--pair-consumer-mb` (1000), `--pair-producer-mb` (200), `--pair-drop-frac` (0.25), `--jfr-dir`,
`--jfr-min-loads` (500), `--jfr-min-share` (0.5).

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
