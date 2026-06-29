package com.csto2;

import com.csto2.analyze.StaticComprehension;
import com.csto2.analyze.StaticEdges;
import com.csto2.optimize.Candidates;
import com.csto2.optimize.JfrClassifier;
import com.csto2.optimize.OrderOptimizer;
import com.csto2.optimize.Pairwise;
import com.csto2.trace.OrderRunner;
import com.csto2.surefire.SurefireOrchestrator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** CSTO v2 CLI. First command: static comprehension. */
public final class Csto2 {
    public static void main(String[] args) throws Exception {
        // Launch the interactive REPL for: no args, `repl [dir]`, `pipeline [dir]`, or a bare directory.
        boolean replCmd = args.length > 0 && (args[0].equals("repl") || args[0].equals("pipeline"));
        boolean bareDir = args.length == 1 && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(args[0]));
        if (args.length == 0 || replCmd || bareDir) {
            String dir = replCmd ? (args.length > 1 ? args[1] : null) : (bareDir ? args[0] : null);
            com.csto2.cli.Repl repl = new com.csto2.cli.Repl();
            if (dir != null) repl.run(java.nio.file.Paths.get(dir)); else repl.run();
            return;
        }
        dispatch(args[0], parse(args));
    }

    /** Run a single subcommand with a pre-parsed flag map. Public so the REPL can drive the pipeline. */
    public static void dispatch(String cmd, Map<String, String> a) throws Exception {
        switch (cmd) {
            case "analyze" -> analyze(a);
            case "discover" -> discover(a);
            case "trace" -> trace(a);
            case "validate" -> validate(a);
            case "pairwise" -> pairwise(a);
            case "select" -> select(a);
            default -> usage();
        }
    }

    private static void discover(Map<String, String> a) throws Exception {
        String cp = filterClasspath(req(a, "cp"));
        Path candidates = Paths.get(req(a, "tests"));
        Path out = Paths.get(req(a, "out"));
        Path self = Paths.get(Csto2.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin(a));
        cmd.addAll(jvmArgs(a));
        cmd.add("-cp"); cmd.add(cp + File.pathSeparator + self.toAbsolutePath());
        cmd.add("com.csto2.trace.TestDiscovery");
        cmd.add("--discover"); cmd.add("--tests"); cmd.add(candidates.toString());
        cmd.add("--out"); cmd.add(out.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        Path wd = resolveWorkDir(a, cp);
        if (wd != null) pb.directory(wd.toFile());
        int code = pb.start().waitFor();
        if (code != 0) throw new IllegalStateException("discover failed with exit " + code);
    }

    /**
     * Build the order runner the pipeline measures with. All measurement runs through REAL Maven
     * Surefire (the testorder fork) so timing + greenness match {@code mvn test}; {@code --surefire-ext}
     * (the changing-maven-extension jar) is required. Per-class alloc/jit/gc/JFR come from the
     * injected agent (csto2-agent.jar, auto-located beside csto2.jar). The old in-JVM TraceRunner
     * backend has been retired — only its reflection-based discovery survives as {@code TestDiscovery}.
     */
    private static OrderRunner makeRunner(Map<String, String> a, String cp, Path outDir, Path self) throws Exception {
        return makeRunner(a, cp, outDir, self, true);
    }

    /**
     * Build the Surefire-backed runner.
     *
     * <p>{@code attachAgent} controls the per-class instrumentation agent. The agent (JFR +
     * alloc/jit/gc deltas) is the engine of <b>discovery</b> — tracing, JFR mechanism classification,
     * and producer→consumer pair confirmation all need it. But the agent's JFR recording and listener
     * add real overhead that perturbs the very wall-clock we optimize, so the final A/B
     * <b>validation</b> of promising orders (the {@code select} ship gate, {@code validate}) runs with
     * {@code attachAgent=false} for clean timing — those phases only read runtime+status, never agent
     * facts.
     */
    private static OrderRunner makeRunner(Map<String, String> a, String cp, Path outDir, Path self,
                                          boolean attachAgent) throws Exception {
        Path ext = a.containsKey("surefire-ext") && !a.get("surefire-ext").isBlank()
                ? Paths.get(a.get("surefire-ext")) : defaultSurefireExt();
        if (ext == null || !Files.exists(ext))
            throw new IllegalArgumentException("Surefire testorder fork not found in ~/.m2 — build & install it "
                    + "(`mvn install -DskipTests -Drat.skip -Denforcer.skip` in the maven-surefire fork), or pass "
                    + "--surefire-ext <surefire-changing-maven-extension jar>.");
        if (!a.containsKey("surefire-ext")) System.err.println("[csto2] surefire extension (auto): " + ext);
        Path workDir = resolveWorkDir(a, cp);
        Path moduleDir = workDir != null ? workDir : Paths.get("").toAbsolutePath();
        SurefireOrchestrator s = new SurefireOrchestrator(moduleDir, outDir, ext, surefireMvnBin(a, moduleDir));
        if (a.containsKey("kp-argline")) s.setKpArgline(a.get("kp-argline"));
        if (!attachAgent) {
            System.err.println("[csto2] measurement runner: no agent (clean wall-clock for A/B validation)");
            return s;
        }
        // Per-class instrumentation agent: explicit --agent, else csto2-agent.jar beside csto2.jar.
        Path agent = a.containsKey("agent") ? Paths.get(a.get("agent"))
                : (self.getParent() == null ? null : self.getParent().resolve("csto2-agent.jar"));
        if (agent != null && Files.exists(agent) && !"none".equals(a.get("agent"))) {
            s.setAgent(agent);
            System.err.println("[csto2] surefire instrumentation agent: " + agent);
        } else {
            System.err.println("[csto2] no instrumentation agent (runtime+status only); build csto2-agent.jar or pass --agent");
        }
        return s;
    }

    /** Auto-locate the installed testorder-fork extension jar in the local Maven repo (newest). */
    public static Path defaultSurefireExt() {
        Path base = Paths.get(System.getProperty("user.home"), ".m2", "repository",
                "fun", "jvm", "surefire", "flaky", "surefire-changing-maven-extension");
        if (!Files.isDirectory(base)) return null;
        try (java.util.stream.Stream<Path> s = Files.walk(base, 2)) {
            return s.filter(p -> p.getFileName().toString().matches("surefire-changing-maven-extension-.*\\.jar"))
                    .max(java.util.Comparator.comparing(Path::toString)).orElse(null);
        } catch (java.io.IOException e) { return null; }
    }

    private static String surefireMvnBin(Map<String, String> a, Path dir) {
        String m = a.get("mvn");
        if (m != null && !m.isBlank()) return m;
        Path w = dir.resolve("mvnw");
        return Files.isExecutable(w) ? w.toAbsolutePath().toString() : "mvn";
    }

    private static void trace(Map<String, String> a) throws Exception {
        String cp = filterClasspath(req(a, "cp"));
        Path testsFile = Paths.get(req(a, "tests"));
        int orders = Integer.parseInt(a.getOrDefault("orders", "6"));
        long seed = Long.parseLong(a.getOrDefault("seed", "1"));
        Path outDir = Paths.get(a.getOrDefault("out", ".csto2/trace"));
        List<String> tests = readTests(testsFile);
        Path self = Paths.get(Csto2.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        long t0 = System.nanoTime();
        OrderRunner orch = makeRunner(a, cp, outDir, self);
        Path traceOut = orch.run(tests, orders, seed);
        System.err.printf("[csto2] traced %d orders in %.1fs -> %s%n", orders, (System.nanoTime() - t0) / 1e9, traceOut);
    }

    private static void validate(Map<String, String> a) throws Exception {
        String cp = filterClasspath(req(a, "cp"));
        Path testsFile = Paths.get(req(a, "tests"));
        Path tracePath = Paths.get(req(a, "trace"));     // existing trace.jsonl for calibration
        int repeats = Integer.parseInt(a.getOrDefault("repeats", "5"));
        Path outDir = Paths.get(a.getOrDefault("out", ".csto2/validate"));
        List<String> tests = readTests(testsFile);
        Path self = Paths.get(Csto2.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.createDirectories(outDir);

        OrderOptimizer.Model model = OrderOptimizer.calibrate(tracePath);
        List<String> initial = new ArrayList<>(tests);
        List<String> optimized = OrderOptimizer.optimize(model, tests);
        OrderOptimizer.writeReport(model, initial, optimized, outDir.resolve("optimization.json"));
        Path initialFile = outDir.resolve("initial.order");
        Path optFile = outDir.resolve("optimized.order");
        Files.write(initialFile, String.join("\n", initial).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(optFile, String.join("\n", optimized).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.err.println("[csto2] front class chosen: " + optimized.get(0));
        System.err.printf("[csto2] predicted: initial=%.0fms optimized=%.0fms%n",
                model.predict(initial), model.predict(optimized));

        Path measure = outDir.resolve("measure.jsonl");
        Files.deleteIfExists(measure);
        // Validate promising orders with NO agent: the instrumentation/JFR overhead perturbs the
        // wall-clock we are comparing. report() only reads runtimeMs, so agent facts aren't needed.
        OrderRunner orch = makeRunner(a, cp, outDir, self, false);
        // Interleave repeats to spread background-load noise evenly across both orders.
        for (int r = 0; r < repeats; r++) {
            orch.runOrder(initialFile, "initial#" + r, measure);
            orch.runOrder(optFile, "optimized#" + r, measure);
            System.err.println("[csto2] measured repeat " + (r + 1) + "/" + repeats);
        }
        report(measure);
    }

    private static void select(Map<String, String> a) throws Exception {
        String cp = filterClasspath(req(a, "cp"));
        Path testsFile = Paths.get(req(a, "tests"));
        Path tracePath = Paths.get(req(a, "trace"));
        int repeats = Integer.parseInt(a.getOrDefault("repeats", "4"));
        double heavyAllocMB = Double.parseDouble(a.getOrDefault("heavy-alloc-mb", "500"));
        double coldSlope = Double.parseDouble(a.getOrDefault("cold-slope", "-1.0"));
        double maxResid = Double.parseDouble(a.getOrDefault("max-resid", "300"));
        Path outDir = Paths.get(a.getOrDefault("out", ".csto2/select"));
        List<String> tests = readTests(testsFile);
        Path self = Paths.get(Csto2.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.createDirectories(outDir);

        // Keep ALL tests. We never drop tests; instead the selection gate ships only an order that
        // measures fully GREEN (0 failures, 0 timeouts). A green order is known to exist (initial),
        // so any shipped order must be at least as correct as initial AND faster.
        System.err.println("[csto2] optimizing all " + tests.size() + " classes; will ship only a fully-green order");

        Map<String, Candidates.Stat> stats = Candidates.stats(tracePath);
        Map<String, List<String>> cands = Candidates.generate(tests, stats, tracePath, heavyAllocMB, coldSlope, maxResid);

        // Two runners, two roles. DISCOVERY (agent/JFR on): pair confirmation needs the agent's
        // per-class allocation deltas to prove a producer warms a consumer. VALIDATION (agent off):
        // the final candidate A/B is measured clean, since the agent's JFR/listener overhead would
        // confound the wall-clock the ship gate decides on.
        OrderRunner discover = makeRunner(a, cp, outDir, self, true);
        OrderRunner validate = makeRunner(a, cp, outDir, self, false);

        // Producer->consumer cache-warming candidate: trace-mine pairs (allocation-shed fingerprint),
        // then CAUSALLY CONFIRM each with a 2-class probe before trusting it. Applied as a minimal
        // perturbation of initial. Confounded co-occurrence pairs are rejected by the probe.
        double minConsumerAllocMB = Double.parseDouble(a.getOrDefault("pair-consumer-mb", "1000"));
        double minProducerAllocMB = Double.parseDouble(a.getOrDefault("pair-producer-mb", "200"));
        double pairDropFrac = Double.parseDouble(a.getOrDefault("pair-drop-frac", "0.25"));
        List<Candidates.PairSig> raw = Candidates.detectPairs(tracePath, stats,
                minConsumerAllocMB, minProducerAllocMB, /*minAllocDropMB*/ 1000, pairDropFrac);
        for (Candidates.PairSig ps : raw)
            System.err.printf("[csto2] warm-pair candidate (trace): %s -> %s  (sheds %.0fMB, ~%.0fms)%n",
                    ps.producer.substring(ps.producer.lastIndexOf('.') + 1),
                    ps.consumer.substring(ps.consumer.lastIndexOf('.') + 1), ps.allocDropMB, ps.rtDropMs);
        List<Candidates.PairSig> confirmed = raw.isEmpty() ? raw
                : Candidates.confirmPairs(discover, outDir.resolve("probe"), raw, pairDropFrac);
        if (!confirmed.isEmpty())
            cands.put("pairwise-warm", Candidates.applyPairsMinimal(tests, confirmed));

        // JFR-driven candidates: classify tests by MECHANISM (full-GC carriers, shareable-warmup
        // carriers) from per-test JFR facts aggregated across orders, and move those classes. Driven
        // by the instrumented cause, not wall-clock. Enabled when a jfr facts dir is given/exists.
        String jfrDirArg = a.get("jfr-dir");
        Path jfrDir = jfrDirArg != null ? Paths.get(jfrDirArg) : tracePath.getParent().resolve("jfr");
        if (Files.isDirectory(jfrDir)) {
            double minLoads = Double.parseDouble(a.getOrDefault("jfr-min-loads", "200"));
            double minShare = Double.parseDouble(a.getOrDefault("jfr-min-share", "0.3"));
            Map<String, JfrClassifier.Facts> jf = JfrClassifier.analyze(jfrDir, minLoads, minShare);
            System.err.print(JfrClassifier.report(jf));
            long gcCarriers = jf.values().stream().filter(f -> "GC_CARRIER".equals(f.category)).count();
            long warmCarriers = jf.values().stream().filter(f -> "WARMUP_SHAREABLE".equals(f.category)).count();
            if (gcCarriers > 0) cands.put("jfr-gc-front", JfrClassifier.gcFront(tests, jf));
            if (warmCarriers > 0) cands.put("jfr-warmup-front", JfrClassifier.warmupFront(tests, jf));
            if (gcCarriers > 0 && warmCarriers > 0) cands.put("jfr-gc+warmup-front", JfrClassifier.gcAndWarmupFront(tests, jf));
        } else {
            System.err.println("[csto2] no JFR facts dir at " + jfrDir + " (run trace with --jfr to enable mechanism-driven candidates)");
        }

        for (Map.Entry<String, List<String>> e : cands.entrySet()) {
            Files.write(outDir.resolve(e.getKey() + ".order"),
                    String.join("\n", e.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.err.println("[csto2] candidate " + e.getKey() + " (" + e.getValue().size() + " classes)");
        }
        Path measure = outDir.resolve("measure.jsonl");
        Files.deleteIfExists(measure);
        // Interleave: one repeat = each candidate once, but SHUFFLE the within-round order each round.
        // A fixed order pins every candidate to the same slot every round, so any within-round drift
        // (OS file-cache warming, thermal, harness JIT) becomes a systematic per-candidate bias —
        // e.g. the incumbent (always first/cold) looks slow and a later candidate looks fast for free.
        // Seeded shuffle keeps it reproducible while decorrelating slot from candidate.
        List<String> names = new ArrayList<>(cands.keySet());
        java.util.Random shufRnd = new java.util.Random(20240625L);
        for (int r = 0; r < repeats; r++) {
            java.util.Collections.shuffle(names, shufRnd);
            for (String name : names)
                validate.runOrder(outDir.resolve(name + ".order"), name + "#" + r, measure);
            System.err.println("[csto2] measured round " + (r + 1) + "/" + repeats);
        }
        selectReport(measure, "initial");
    }

    /** Report medians + greenness per candidate; SHIP only a fully-green order (incumbent fallback). */
    private static void selectReport(Path measure, String incumbent) throws Exception {
        Map<String, List<Double>> perRun = new LinkedHashMap<>();
        Map<String, Integer> runNonPass = new LinkedHashMap<>(); // per run id: count of FAIL/TIMEOUT
        for (String line : Files.readAllLines(measure)) {
            line = line.trim(); if (line.isEmpty()) continue;
            int oi = line.indexOf("\"orderId\":\""); int ri = line.indexOf("\"runtimeMs\":");
            if (oi < 0 || ri < 0) continue;
            String oid = line.substring(oi + 11, line.indexOf('"', oi + 11));
            double rt = Double.parseDouble(line.substring(ri + 12, findEnd(line, ri + 12)));
            perRun.computeIfAbsent(oid, k -> new ArrayList<>()).add(rt);
            int si = line.indexOf("\"status\":\"");
            if (si >= 0) {
                String st = line.substring(si + 10, line.indexOf('"', si + 10));
                if (!"PASS".equals(st)) runNonPass.merge(oid, 1, Integer::sum);
            }
        }
        Map<String, List<Double>> perCand = new LinkedHashMap<>();
        Map<String, Integer> candNonPass = new LinkedHashMap<>(); // total non-pass across all runs
        for (Map.Entry<String, List<Double>> e : perRun.entrySet()) {
            String name = e.getKey().contains("#") ? e.getKey().substring(0, e.getKey().indexOf('#')) : e.getKey();
            double sum = e.getValue().stream().mapToDouble(Double::doubleValue).sum();
            perCand.computeIfAbsent(name, k -> new ArrayList<>()).add(sum);
            candNonPass.merge(name, runNonPass.getOrDefault(e.getKey(), 0), Integer::sum);
        }
        Map<String, Double> med = new LinkedHashMap<>();
        System.out.println("\n=== CANDIDATE MEASUREMENTS ===");
        for (Map.Entry<String, List<Double>> e : perCand.entrySet()) {
            List<Double> v = new ArrayList<>(e.getValue()); v.sort(Double::compare);
            double m = v.get(v.size() / 2);
            med.put(e.getKey(), m);
            int np = candNonPass.getOrDefault(e.getKey(), 0);
            System.out.printf("  %-22s runs=%d median=%.0fms min=%.0fms max=%.0fms  %s%n",
                    e.getKey(), v.size(), m, v.get(0), v.get(v.size() - 1),
                    np == 0 ? "GREEN" : ("NOT-GREEN (" + np + " non-pass)"));
        }
        double base = med.getOrDefault(incumbent, Double.NaN);
        // Ship only a fully-green candidate, fastest, with a >1% margin over incumbent to deviate.
        String winner = incumbent; double wm = base;
        for (Map.Entry<String, Double> e : med.entrySet()) {
            if (candNonPass.getOrDefault(e.getKey(), 0) != 0) continue; // green gate
            if (e.getValue() < wm) { wm = e.getValue(); winner = e.getKey(); }
        }
        if (!winner.equals(incumbent) && (base - wm) < 0.01 * base) winner = incumbent; // margin guard
        System.out.println();
        for (Map.Entry<String, Double> e : med.entrySet()) {
            if (e.getKey().equals(incumbent)) continue;
            double pct = 100.0 * (base - e.getValue()) / base;
            String grn = candNonPass.getOrDefault(e.getKey(), 0) == 0 ? "" : "  [disqualified: not green]";
            System.out.printf("  %-22s %+.1f%% vs initial%s%n", e.getKey(), pct, grn);
        }
        System.out.printf("%n=> SHIP: %s  (%.0fms, %.1f%% faster than initial) [green]%n",
                winner, wm, 100.0 * (base - wm) / base);
    }

    private static void pairwise(Map<String, String> a) throws Exception {
        String cp = filterClasspath(req(a, "cp"));
        Path tracePath = Paths.get(req(a, "trace"));
        Path factsPath = Paths.get(req(a, "facts"));
        int repeats = Integer.parseInt(a.getOrDefault("repeats", "5"));
        int topConsumers = Integer.parseInt(a.getOrDefault("consumers", "12"));
        Path outDir = Paths.get(a.getOrDefault("out", ".csto2/pairwise"));
        Path self = Paths.get(Csto2.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.createDirectories(outDir);

        // Stable-passing subset: classes that PASS in every traced order (avoids failure confounds).
        List<String> stable = stablePassing(tracePath);
        System.err.println("[csto2] stable-passing classes: " + stable.size());

        OrderOptimizer.Model model = OrderOptimizer.calibrate(tracePath);
        List<String> sens = Pairwise.sensitiveConsumers(tracePath, model, topConsumers);
        sens = new ArrayList<>(sens); sens.retainAll(stable);
        Map<String, java.util.Set<String>> reads = Pairwise.staticReads(factsPath);
        reads.keySet().retainAll(stable); // only probe stable producers

        OrderRunner orch = makeRunner(a, cp, outDir, self);
        System.err.println("[csto2] probing pairs for " + sens.size() + " sensitive consumers...");
        List<Pairwise.Pair> pairs = Pairwise.probe(orch, outDir.resolve("probe.jsonl"),
                sens, reads, 20.0, 0.15, 4);
        Pairwise.writeReport(pairs, outDir.resolve("pairs.json"));
        System.err.println("[csto2] confirmed pairs: " + pairs.size());
        for (Pairwise.Pair p : pairs)
            System.err.printf("   %.0fms  %s  ->  %s%n", p.benefitMs,
                    p.producer.substring(p.producer.lastIndexOf('.') + 1),
                    p.consumer.substring(p.consumer.lastIndexOf('.') + 1));

        List<String> initial = new ArrayList<>(stable);
        List<String> slope = OrderOptimizer.optimize(model, stable);
        List<String> pw = Pairwise.reorder(model, stable, pairs);
        Path measure = outDir.resolve("measure.jsonl");
        Files.deleteIfExists(measure);
        Map<String, List<String>> orders = new LinkedHashMap<>();
        orders.put("initial", initial); orders.put("slope", slope); orders.put("pairwise", pw);
        for (Map.Entry<String, List<String>> e : orders.entrySet()) {
            Path f = outDir.resolve(e.getKey() + ".order");
            Files.write(f, String.join("\n", e.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (int r = 0; r < repeats; r++) {
            for (String name : orders.keySet())
                orch.runOrder(outDir.resolve(name + ".order"), name + "#" + r, measure);
            System.err.println("[csto2] measured repeat " + (r + 1) + "/" + repeats);
        }
        report(measure);
    }

    private static List<String> stablePassing(Path tracePath) throws Exception {
        Map<String, Boolean> ok = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tracePath)) {
            line = line.trim(); if (line.isEmpty()) continue;
            int ti = line.indexOf("\"test\":\""); int si = line.indexOf("\"status\":\"");
            if (ti < 0 || si < 0) continue;
            String t = line.substring(ti + 8, line.indexOf('"', ti + 8));
            String st = line.substring(si + 10, line.indexOf('"', si + 10));
            ok.merge(t, "PASS".equals(st), (x, y) -> x && y);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : ok.entrySet()) if (e.getValue()) out.add(e.getKey());
        java.util.Collections.sort(out);
        return out;
    }

    private static void report(Path measure) throws Exception {
        Map<String, List<Double>> totals = new LinkedHashMap<>();
        for (String line : Files.readAllLines(measure)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // orderId like "initial#3"; sum runtimeMs per run.
            int oi = line.indexOf("\"orderId\":\"");
            int ri = line.indexOf("\"runtimeMs\":");
            if (oi < 0 || ri < 0) continue;
            String oid = line.substring(oi + 11, line.indexOf('"', oi + 11));
            String group = oid.contains("#") ? oid.substring(0, oid.indexOf('#')) : oid;
            String run = oid;
            double rt = Double.parseDouble(line.substring(ri + 12, findEnd(line, ri + 12)));
            totals.computeIfAbsent(group + "|" + run, k -> new ArrayList<>()).add(rt);
        }
        // sum per run, then aggregate per group
        Map<String, List<Double>> perGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : totals.entrySet()) {
            String group = e.getKey().substring(0, e.getKey().indexOf('|'));
            double sum = e.getValue().stream().mapToDouble(Double::doubleValue).sum();
            perGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(sum);
        }
        System.out.println("\n=== MEASURED VALIDATION ===");
        Map<String, Double> medians = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : perGroup.entrySet()) {
            List<Double> v = new ArrayList<>(e.getValue());
            v.sort(Double::compare);
            double med = v.get(v.size() / 2);
            System.out.printf("  %-10s runs=%d  median=%.0fms  min=%.0fms  max=%.0fms%n",
                    e.getKey(), v.size(), med, v.get(0), v.get(v.size() - 1));
            medians.put(e.getKey(), med);
        }
        Double initialMed = medians.get("initial");
        if (initialMed != null) {
            for (Map.Entry<String, Double> e : medians.entrySet()) {
                if (e.getKey().equals("initial")) continue;
                double pct = 100.0 * (initialMed - e.getValue()) / initialMed;
                System.out.printf("  => %-10s is %.1f%% %s than initial (median)%n",
                        e.getKey(), Math.abs(pct), pct >= 0 ? "FASTER" : "SLOWER");
            }
        }
    }

    private static int findEnd(String s, int from) {
        int i = from;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return i;
    }

    /**
     * Working directory for child test JVMs. Maven Surefire runs each module's tests with the module
     * basedir as the cwd, so tests resolving relative paths (e.g. {@code src/test/resources/...}) only
     * pass when launched from there. Honors an explicit {@code --workdir}; otherwise infers the TEST
     * module basedir as the parent of the {@code target/test-classes} dir (the module whose tests we
     * run — its src/test/resources is what relative paths resolve against). Falls back to a plain
     * {@code target/classes} module only if no test-classes entry is on the classpath. Returns null
     * (inherit our cwd) when nothing can be inferred.
     */
    private static Path inferWorkDir(Map<String, String> a, String cp) {
        String wd = a.get("workdir");
        if (wd != null && !wd.isBlank()) return Paths.get(wd);
        String marker = File.separator + "target" + File.separator;
        // Prefer the test module (parent of target/test-classes); fall back to a target/classes module.
        String fallback = null;
        for (String e : cp.split(File.pathSeparator)) {
            if (e.endsWith(File.separator + "target" + File.separator + "test-classes")) {
                int i = e.lastIndexOf(marker);
                if (i > 0) return Paths.get(e.substring(0, i));
            } else if (fallback == null && e.endsWith(File.separator + "target" + File.separator + "classes")) {
                fallback = e;
            }
        }
        if (fallback != null) {
            int i = fallback.lastIndexOf(marker);
            if (i > 0) return Paths.get(fallback.substring(0, i));
        }
        return null;
    }

    private static Path resolveWorkDir(Map<String, String> a, String cp) {
        Path wd = inferWorkDir(a, cp);
        if (wd != null) System.err.println("[csto2] child JVM workdir: " + wd);
        else System.err.println("[csto2] child JVM workdir: <inherited> (pass --workdir if tests use relative paths)");
        return wd;
    }

    /** Drop classpath entries that no longer exist (stale harness entries break child JVMs too). */
    private static String filterClasspath(String cp) {
        return Arrays.stream(cp.split(File.pathSeparator))
                .map(String::trim).filter(s -> !s.isEmpty())
                .filter(s -> Files.exists(Paths.get(s)))
                .collect(Collectors.joining(File.pathSeparator));
    }

    /** Java binary for child test JVMs (e.g. point at JDK 17 for projects that don't support newer). */
    private static String javaBin(Map<String, String> a) {
        String home = a.get("java");
        if (home == null || home.isBlank()) return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Path p = Paths.get(home);
        if (p.getFileName().toString().equals("java")) return p.toString();      // full path to java
        return p.resolve("bin").resolve("java").toString();                       // JAVA_HOME given
    }

    /** Extra JVM args for child test JVMs (e.g. --add-opens to match the project's Surefire config). */
    private static List<String> jvmArgs(Map<String, String> a) {
        String v = a.get("jvmargs");
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> readTests(Path testsFile) throws Exception {
        return Files.readAllLines(testsFile).stream()
                .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
    }

    private static void analyze(Map<String, String> a) throws Exception {
        String app = req(a, "app");
        String lib = a.getOrDefault("lib", "");
        Path testsFile = Paths.get(req(a, "tests"));
        Path outDir = Paths.get(a.getOrDefault("out", ".csto/comprehension"));
        List<String> tests = Files.readAllLines(testsFile).stream()
                .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();

        long t0 = System.nanoTime();
        System.err.println("[csto2] building class hierarchy (app + lib scope)...");
        StaticComprehension sc = StaticComprehension.build(app, lib);
        System.err.printf("[csto2] CHA built in %.1fs; analyzing %d test classes%n",
                (System.nanoTime() - t0) / 1e9, tests.size());

        Path factsOut = outDir.resolve("static-facts.jsonl");
        List<StaticComprehension.TestFacts> facts = sc.analyzeAll(tests, factsOut);
        Map<String, Object> edges = StaticEdges.derive(facts);
        Path edgesOut = outDir.resolve("static-edges.json");
        StaticEdges.write(edges, edgesOut);

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) edges.get("counts");
        System.err.printf("[csto2] done in %.1fs%n", (System.nanoTime() - t0) / 1e9);
        System.err.println("[csto2] facts -> " + factsOut);
        System.err.println("[csto2] edges -> " + edgesOut + "  " + counts);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                // All flags take a value (e.g. --jvmargs's value itself begins with "--add-opens"),
                // so consume the next token unconditionally when present.
                if (i + 1 < args.length) m.put(k, args[++i]);
                else m.put(k, "true");
            }
        }
        return m;
    }

    private static String req(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + k);
        return v;
    }

    private static void usage() {
        System.out.println("""
                CSTO v2

                Commands:
                  analyze --app <classpath> [--lib <classpath>] --tests <file> [--out <dir>]
                      Static comprehension: per-test facts + candidate interaction edges.
                """);
    }
}
