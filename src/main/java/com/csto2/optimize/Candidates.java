package com.csto2.optimize;

import com.csto2.trace.OrderRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a small set of CANDIDATE orders embodying different, individually-defensible hypotheses.
 * No single model is trusted; the measured selection gate (see {@code select}) picks whichever
 * candidate is actually fastest, with initial as a protected incumbent so a regression can never ship.
 *
 * <p>Design follows the core-testing autopsy:
 * <ul>
 *   <li>heavy allocators belong EARLY (deterministic on allocation magnitude, not a noisy slope sign);</li>
 *   <li>only move a class late when its negative position-slope is HIGH-CONFIDENCE (low residual);</li>
 *   <li>otherwise stay close to initial, which preserves test-family adjacency (pairwise warm-ups) and
 *       JIT type-profile coherence that an aggressive global re-sort destroys.</li>
 * </ul>
 */
public final class Candidates {

    public static final class Stat {
        double allocMB, slope, intercept, residStd, medRt, medJit;
        int n;
    }

    public static Map<String, Stat> stats(Path tracePath) throws Exception {
        Map<String, List<double[]>> obs = new LinkedHashMap<>(); // test -> [pos, rt, allocMB]
        for (String l : Files.readAllLines(tracePath)) {
            l = l.trim(); if (l.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(l);
            String t = (String) r.get("test");
            double pos = ((Number) r.get("position")).doubleValue();
            double rt = ((Number) r.get("runtimeMs")).doubleValue();
            double a = r.containsKey("allocBytes") ? ((Number) r.get("allocBytes")).doubleValue() / 1e6 : 0;
            double jit = r.containsKey("jitMs") ? ((Number) r.get("jitMs")).doubleValue() : 0;
            obs.computeIfAbsent(t, k -> new ArrayList<>()).add(new double[]{pos, rt, a, jit});
        }
        Map<String, Stat> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> e : obs.entrySet()) {
            List<double[]> v = e.getValue();
            Stat s = new Stat(); s.n = v.size();
            int n = v.size();
            double sx = 0, sy = 0; for (double[] p : v) { sx += p[0]; sy += p[1]; }
            double mx = sx / n, my = sy / n, sxx = 0, sxy = 0;
            for (double[] p : v) { double dx = p[0] - mx; sxx += dx * dx; sxy += dx * (p[1] - my); }
            s.slope = sxy / (sxx + 50.0);
            s.intercept = my - s.slope * mx;
            double ss = 0; for (double[] p : v) { double pr = s.intercept + s.slope * p[0]; ss += (p[1] - pr) * (p[1] - pr); }
            s.residStd = Math.sqrt(ss / n);
            s.allocMB = median(v, 2);
            s.medRt = median(v, 1);
            s.medJit = median(v, 3);
            out.put(e.getKey(), s);
        }
        return out;
    }

    /** Build the named candidate orders. */
    public static Map<String, List<String>> generate(List<String> initial, Map<String, Stat> stats, Path tracePath,
                                                     double heavyAllocMB, double coldSlope, double maxResid) throws Exception {
        Map<String, List<String>> cands = new LinkedHashMap<>();
        cands.put("initial", new ArrayList<>(initial));      // as-given/default order
        cands.put("naive", fastestObserved(tracePath, initial)); // fastest of the trivially-observed orders

        // heavy allocators, descending; everything else keeps initial order
        List<String> heavy = new ArrayList<>();
        for (String t : initial) { Stat s = stats.get(t); if (s != null && s.allocMB >= heavyAllocMB) heavy.add(t); }
        heavy.sort(Comparator.comparingDouble((String t) -> -stats.get(t).allocMB));
        cands.put("alloc-front", move(initial, heavy, true));

        // confident cold-sensitive classes (steep negative slope, low residual), not heavy
        List<String> cold = new ArrayList<>();
        for (String t : initial) {
            Stat s = stats.get(t);
            if (s != null && s.slope <= coldSlope && s.residStd <= maxResid && s.allocMB < heavyAllocMB) cold.add(t);
        }
        cands.put("warm-tail", move(initial, cold, false));

        // combined: heavy to front AND cold to tail
        List<String> combo = move(move(initial, heavy, true), cold, false);
        cands.put("alloc-front+warm-tail", combo);

        // Within-package warmup ordering: keeps packages ATOMIC and in their as-given order (so JIT
        // type-profile / inlining LOCALITY is preserved -- a global re-sort destroys it), but inside
        // each package runs the warmup-heaviest test FIRST so the rest of the package executes warm.
        // This is the lever for suites whose natural (filesystem/package) order is already locality-
        // optimal: the only safe move is intra-package. Measured +5% on javaparser where every global
        // reorder LOST. Heaviest = largest median runtime (a warmup-laden cost proxy).
        cands.put("intra-warmup", intraPackageWarmup(initial, stats));

        // Whole-package block moves: sort package blocks by aggregate signal, but preserve the
        // project's original order inside each package. This is the middle ground between minimal
        // local perturbations and destructive global sorts. It keeps suite-authored adjacency within
        // coherent test families while still moving alloc/runtime-heavy regions earlier.
        cands.put("pkg-alloc-front", packageBlockSort(initial, stats, Signal.ALLOC));
        cands.put("pkg-rt-front", packageBlockSort(initial, stats, Signal.RUNTIME));
        cands.put("pkg-alloc+observed-intra", packageBlockSortObservedIntra(initial, stats, tracePath, Signal.ALLOC));

        // Global allocation-descending sort: a FULL re-sort by allocation, not the threshold-bounded
        // minimal perturbation of alloc-front. Captures alloc-dominated suites where many MEDIUM
        // allocators (none individually over alloc-front's threshold) collectively drive GC -- a full
        // sort fronts them all. Generalizes across alloc-heavy suites (jackson-core +5.4%, paimon +5.8%
        // vs initial) but breaks JIT/locality on locality-bound suites (javaparser -17%). Safe to offer:
        // select's green gate only ships it where it actually measures faster.
        List<String> allocSorted = new ArrayList<>(initial);
        allocSorted.sort(Comparator.comparingDouble((String t) -> {
            Stat s = stats.get(t);
            return s == null ? 0 : -s.allocMB;     // largest allocator first
        }));
        cands.put("alloc-sort", allocSorted);

        // Global JIT-warmup front: sort by per-test compilation time (jitMs) descending. For JIT-BOUND
        // suites (where compilation, not GC or compute, dominates -- e.g. jackson-core: ~8.5s of a 12s
        // run is JIT), front-loading the compilation-heaviest tests warms the JIT once so everyone after
        // runs hot, cutting total compilation. Measured jackson-core +3.3% vs naive / +10% vs initial
        // (beat the fastest-observed baseline, confirmed over 7 rounds). Like alloc-sort this is a global
        // re-sort, so it breaks locality on locality-bound suites -- select's green gate gates it.
        List<String> jitSorted = new ArrayList<>(initial);
        jitSorted.sort(Comparator.comparingDouble((String t) -> {
            Stat s = stats.get(t);
            return s == null ? 0 : -s.medJit;      // heaviest compiler first
        }));
        cands.put("jit-front", jitSorted);

        // NOTE: the producer->consumer "pairwise-warm" candidate is added by the caller (select),
        // because it must be CAUSALLY CONFIRMED with a 2-class probe before it can be trusted (see
        // detectPairs / confirmPairs). Trace co-occurrence alone confounds correlated predecessors.
        return cands;
    }

    private enum Signal { ALLOC, RUNTIME }

    /**
     * Sort whole package blocks by aggregate signal, preserving first-appearance package order for
     * ties and preserving the original class order inside each package. The stability matters:
     * package adjacency often encodes fixture/resource/JIT locality that per-class global sorts break.
     */
    static List<String> packageBlockSort(List<String> initial, Map<String, Stat> stats, Signal signal) {
        final class Block {
            final String pkg;
            final int first;
            final List<String> members = new ArrayList<>();
            Block(String pkg, int first) { this.pkg = pkg; this.first = first; }
            double score() {
                double s = 0;
                for (String t : members) {
                    Stat st = stats.get(t);
                    if (st == null) continue;
                    s += switch (signal) {
                        case ALLOC -> st.allocMB;
                        case RUNTIME -> st.medRt;
                    };
                }
                return s;
            }
        }

        Map<String, Block> blocks = new LinkedHashMap<>();
        for (int i = 0; i < initial.size(); i++) {
            String t = initial.get(i);
            String p = packageName(t);
            Block b = blocks.get(p);
            if (b == null) {
                b = new Block(p, i);
                blocks.put(p, b);
            }
            b.members.add(t);
        }
        List<Block> ordered = new ArrayList<>(blocks.values());
        ordered.sort(Comparator.comparingDouble(Block::score).reversed().thenComparingInt(b -> b.first));
        List<String> out = new ArrayList<>(initial.size());
        for (Block b : ordered) out.addAll(b.members);
        return out;
    }

    /**
     * Sort whole package blocks by aggregate signal, but use the fastest traced order's relative
     * order inside each package. This composes package-level locality preservation with the one
     * empirically observed order signal that is often useful but too destructive as a global order.
     */
    static List<String> packageBlockSortObservedIntra(List<String> initial, Map<String, Stat> stats,
                                                      Path tracePath, Signal signal) throws Exception {
        List<String> blockOrder = packageBlockSort(initial, stats, signal);
        List<String> observed = fastestObserved(tracePath, initial);
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (String t : initial) byPackage.computeIfAbsent(packageName(t), k -> new ArrayList<>());

        Map<String, java.util.Set<String>> allowed = new LinkedHashMap<>();
        for (String t : initial) allowed.computeIfAbsent(packageName(t), k -> new java.util.LinkedHashSet<>()).add(t);
        for (String t : observed) {
            String p = packageName(t);
            java.util.Set<String> a = allowed.get(p);
            if (a != null && a.contains(t)) byPackage.get(p).add(t);
        }
        for (String t : initial) {
            List<String> members = byPackage.get(packageName(t));
            if (!members.contains(t)) members.add(t);
        }

        List<String> out = new ArrayList<>(initial.size());
        java.util.Set<String> emittedPackages = new java.util.LinkedHashSet<>();
        for (String t : blockOrder) {
            String p = packageName(t);
            if (emittedPackages.add(p)) out.addAll(byPackage.get(p));
        }
        return out;
    }

    /**
     * Group the initial order into contiguous per-package blocks (first-appearance order preserved),
     * then sort tests WITHIN each block by descending median runtime (warmup-heaviest first). Package
     * blocks stay in place and atomic, so cross-package locality is untouched.
     */
    static List<String> intraPackageWarmup(List<String> initial, Map<String, Stat> stats) {
        java.util.LinkedHashMap<String, List<String>> blocks = new java.util.LinkedHashMap<>();
        for (String t : initial) {
            blocks.computeIfAbsent(packageName(t), k -> new ArrayList<>()).add(t);
        }
        List<String> order = new ArrayList<>(initial.size());
        for (List<String> members : blocks.values()) {
            members.sort(Comparator.comparingDouble((String t) -> {
                Stat s = stats.get(t);
                // Sort by COLD cost (intercept = est. runtime at position 0), not median runtime:
                // a class's warmup-laden first-run cost is what running it early amortizes for the
                // rest of its package. Measured intra-intercept +4.2% vs intra-medRt +0.0% on javaparser.
                return s == null ? 0 : -s.intercept;
            }));
            order.addAll(members);
        }
        return order;
    }

    private static String packageName(String test) {
        int dot = test.lastIndexOf('.');
        return dot < 0 ? "" : test.substring(0, dot);
    }

    /** A detected producer->consumer warming relationship: C allocates/runs less when P precedes it. */
    public static final class PairSig {
        public final String producer, consumer;
        public double allocDropMB, rtDropMs;
        public int nBefore, nAfter;
        PairSig(String p, String c) { producer = p; consumer = c; }
    }

    /**
     * Mine the multi-order trace for producer->consumer warming pairs. For each heavy-allocating
     * consumer C and each substantial producer P, split the traced orders into {P before C} and
     * {P after C} and compare C's median allocation: a true cache-warmer makes C shed allocation.
     * Returns the single best producer per consumer, ranked by realized runtime benefit.
     */
    public static List<PairSig> detectPairs(Path tracePath, Map<String, Stat> stats,
            double minConsumerAllocMB, double minProducerAllocMB,
            double minAllocDropMB, double minDropFrac) throws Exception {
        // orderId -> (test -> position), and (orderId,test) -> [rt, allocMB]
        Map<String, Map<String, Integer>> pos = new LinkedHashMap<>();
        Map<String, Map<String, double[]>> obs = new LinkedHashMap<>(); // orderId -> test -> [rt, allocMB]
        for (String l : Files.readAllLines(tracePath)) {
            l = l.trim(); if (l.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(l);
            String oid = (String) r.get("orderId"), t = (String) r.get("test");
            int p = ((Number) r.get("position")).intValue();
            double rt = ((Number) r.get("runtimeMs")).doubleValue();
            double a = r.containsKey("allocBytes") ? ((Number) r.get("allocBytes")).doubleValue() / 1e6 : 0;
            pos.computeIfAbsent(oid, k -> new LinkedHashMap<>()).put(t, p);
            obs.computeIfAbsent(oid, k -> new LinkedHashMap<>()).put(t, new double[]{rt, a});
        }
        List<String> consumers = new ArrayList<>();
        for (Map.Entry<String, Stat> e : stats.entrySet())
            if (e.getValue().allocMB >= minConsumerAllocMB) consumers.add(e.getKey());
        List<String> producers = new ArrayList<>();
        for (Map.Entry<String, Stat> e : stats.entrySet())
            if (e.getValue().allocMB >= minProducerAllocMB) producers.add(e.getKey());

        List<PairSig> out = new ArrayList<>();
        for (String c : consumers) {
            PairSig best = null;
            for (String p : producers) {
                if (p.equals(c)) continue;
                List<Double> bA = new ArrayList<>(), aA = new ArrayList<>(), bR = new ArrayList<>(), aR = new ArrayList<>();
                for (String oid : pos.keySet()) {
                    Map<String, Integer> pm = pos.get(oid);
                    Integer cp = pm.get(c), pp = pm.get(p);
                    if (cp == null || pp == null) continue;
                    double[] co = obs.get(oid).get(c);
                    if (pp < cp) { bR.add(co[0]); bA.add(co[1]); } else { aR.add(co[0]); aA.add(co[1]); }
                }
                if (bA.size() < 2 || aA.size() < 2) continue;
                double allocBefore = median(bA), allocAfter = median(aA);
                double allocDrop = allocAfter - allocBefore;        // >0 => warming reduces allocation
                double rtDrop = median(aR) - median(bR);            // >0 => warming reduces runtime
                if (allocDrop < minAllocDropMB || allocDrop < minDropFrac * allocAfter) continue;
                if (best == null || allocDrop > best.allocDropMB) {
                    PairSig ps = new PairSig(p, c);
                    ps.allocDropMB = allocDrop; ps.rtDropMs = rtDrop;
                    ps.nBefore = bA.size(); ps.nAfter = aA.size();
                    best = ps;
                }
            }
            if (best != null) out.add(best);
        }
        out.sort(Comparator.comparingDouble((PairSig p) -> -p.rtDropMs));
        return out;
    }

    /**
     * Causally confirm trace-mined pairs with a 2-class probe. Trace co-occurrence is necessary but
     * NOT sufficient: with few orders, a producer that merely rides along with the true warmer (e.g.
     * other date tests) gets miscredited. Here we run the pair in isolation BOTH ways — [P,C] and
     * [C,P] — in fresh JVMs and keep the pair only if the consumer actually sheds allocation when P
     * runs first. This rejects confounded pairs (a non-warmer leaves C's allocation unchanged) while
     * keeping genuine cache producers. Cheap: 2 short runs per candidate pair.
     */
    public static List<PairSig> confirmPairs(OrderRunner orch, Path probeDir, List<PairSig> raw,
            double minDropFrac) throws Exception {
        Files.createDirectories(probeDir);
        Path probeOut = probeDir.resolve("probe.jsonl");
        Files.deleteIfExists(probeOut);
        List<PairSig> ok = new ArrayList<>();
        for (PairSig p : raw) {
            double[] warm = probeConsumer(orch, probeDir, probeOut, p.producer, p.consumer, true);  // [P,C]
            double[] cold = probeConsumer(orch, probeDir, probeOut, p.producer, p.consumer, false); // [C,P]
            double allocDrop = cold[1] - warm[1];   // >0 => P-first makes C allocate less
            double rtDrop = cold[0] - warm[0];       // >0 => P-first makes C faster
            boolean confirmed = cold[1] > 0 && allocDrop >= minDropFrac * cold[1] && rtDrop > 0;
            System.err.printf("[csto2] warm-pair %s: %s -> %s  (probe consumer %.0f->%.0fMB, %.0f->%.0fms)%n",
                    confirmed ? "CONFIRMED" : "rejected", simple(p.producer), simple(p.consumer),
                    cold[1], warm[1], cold[0], warm[0]);
            if (confirmed) {
                PairSig c = new PairSig(p.producer, p.consumer);
                c.allocDropMB = allocDrop; c.rtDropMs = rtDrop; c.nBefore = 1; c.nAfter = 1;
                ok.add(c);
            }
        }
        ok.sort(Comparator.comparingDouble((PairSig p) -> -p.rtDropMs));
        return ok;
    }

    /** Run [producer,consumer] (warm) or [consumer,producer] (cold) in a fresh JVM; return consumer [runtimeMs, allocMB]. */
    private static double[] probeConsumer(OrderRunner orch, Path probeDir, Path probeOut,
            String producer, String consumer, boolean warm) throws Exception {
        String id = (warm ? "warm_" : "cold_") + sanitize(producer) + "__" + sanitize(consumer);
        Path of = probeDir.resolve(id + ".order");
        Files.write(of, ((warm ? producer + "\n" + consumer : consumer + "\n" + producer)).getBytes(StandardCharsets.UTF_8));
        orch.runOrder(of, id, probeOut);
        double rt = Double.NaN, alloc = 0;
        for (String l : Files.readAllLines(probeOut)) {
            l = l.trim(); if (l.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(l);
            if (id.equals(r.get("orderId")) && consumer.equals(r.get("test"))) {
                rt = ((Number) r.get("runtimeMs")).doubleValue();
                alloc = r.containsKey("allocBytes") ? ((Number) r.get("allocBytes")).doubleValue() / 1e6 : 0;
            }
        }
        return new double[]{rt, alloc};
    }

    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9]", "_"); }

    /**
     * Apply detected pairs to the initial order as a MINIMAL perturbation: each producer is moved to immediately
     * before its single best consumer. One placement per producer (its highest-benefit consumer) —
     * moving a producer early to warm several consumers pays its cold-cache penalty once for each
     * move and (measured) loses more than it saves, so we keep it surgical and let the green gate
     * confirm. Pairs are applied strongest-first.
     */
    public static List<String> applyPairsMinimal(List<String> initial, List<PairSig> pairs) {
        List<String> order = new ArrayList<>(initial);
        java.util.Set<String> placedProducers = new java.util.HashSet<>();
        for (PairSig p : pairs) {
            if (p.rtDropMs <= 0 || placedProducers.contains(p.producer)) continue;
            int pi = order.indexOf(p.producer), ci = order.indexOf(p.consumer);
            if (pi < 0 || ci < 0 || pi == ci - 1) { placedProducers.add(p.producer); continue; } // already adjacent-before
            order.remove(pi);
            ci = order.indexOf(p.consumer);          // recompute after removal
            order.add(ci, p.producer);               // insert producer immediately before consumer
            placedProducers.add(p.producer);
        }
        return order;
    }

    /** Move {@code group} to the front (toFront=true) or back (false), preserving the given group order. */
    private static List<String> move(List<String> base, List<String> group, boolean toFront) {
        java.util.Set<String> g = new java.util.LinkedHashSet<>(group);
        List<String> rest = new ArrayList<>();
        for (String t : base) if (!g.contains(t)) rest.add(t);
        List<String> out = new ArrayList<>();
        if (toFront) { out.addAll(group); out.addAll(rest); }
        else { out.addAll(rest); out.addAll(group); }
        return out;
    }

    /** The traced order with the lowest total runtime (the paper's "naive" selection). */
    private static List<String> fastestObserved(Path tracePath, List<String> fallback) throws Exception {
        Map<String, Double> total = new LinkedHashMap<>();
        Map<String, List<String[]>> orderRows = new LinkedHashMap<>(); // orderId -> [pos, test]
        for (String l : Files.readAllLines(tracePath)) {
            l = l.trim(); if (l.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(l);
            String oid = (String) r.get("orderId");
            total.merge(oid, ((Number) r.get("runtimeMs")).doubleValue(), Double::sum);
            orderRows.computeIfAbsent(oid, k -> new ArrayList<>())
                    .add(new String[]{String.valueOf(((Number) r.get("position")).intValue()), (String) r.get("test")});
        }
        if (total.isEmpty()) return new ArrayList<>(fallback);
        // Only rank runs that actually covered the full suite. A run whose fork crashed early (e.g. an
        // OOM-killed JVM) records only the classes that completed before the crash, so its summed runtime
        // is artificially tiny and would otherwise always win -- collapsing "naive" to whatever handful of
        // classes happened to run before the crash. Require complete coverage before comparing totals.
        java.util.Set<String> required = new java.util.HashSet<>(fallback);
        List<String> complete = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : orderRows.entrySet()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String[] a : e.getValue()) seen.add(a[1]);
            if (seen.containsAll(required)) complete.add(e.getKey());
        }
        if (complete.isEmpty()) return new ArrayList<>(fallback); // no run finished the whole suite; don't trust partials
        String best = complete.stream().min(Comparator.comparingDouble(total::get)).get();
        List<String[]> rows = orderRows.get(best);
        rows.sort(Comparator.comparingInt(a -> Integer.parseInt(a[0])));
        java.util.Set<String> allowed = new java.util.HashSet<>(fallback);
        List<String> order = new ArrayList<>();
        for (String[] a : rows) if (allowed.contains(a[1])) order.add(a[1]); // keep only stable classes
        return order;
    }

    private static String simple(String fqn) { int i = fqn.lastIndexOf('.'); return i < 0 ? fqn : fqn.substring(i + 1); }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v); s.sort(Double::compare);
        int n = s.size(); return n == 0 ? 0 : (n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2);
    }

    private static double median(List<double[]> v, int idx) {
        List<Double> s = new ArrayList<>(); for (double[] p : v) s.add(p[idx]); s.sort(Double::compare);
        int n = s.size(); return n == 0 ? 0 : (n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2);
    }
}
