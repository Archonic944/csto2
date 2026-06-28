package com.csto2.optimize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Classifies each test from per-test JFR facts (see {@link com.csto2.trace.JfrProbe}) aggregated
 * across many orders, and turns the classification into candidate orders. The point is to drive
 * movement from the MECHANISM, not from noisy wall-clock:
 *
 * <p>GC_CARRIER — does real OLD/FULL collections (gcOld &gt; 0). Full GCs scan the live set, so they
 * are dramatically cheaper on a fresh, low-occupancy heap; such a test wants to run EARLY. A pure
 * young-only allocator (PerformanceTest) is NOT a carrier — its collections are near-free, so moving
 * it does nothing. This is the signal we got wrong before counters could distinguish young from old.
 *
 * <p>WARMUP_SHAREABLE — first-loads many classes / triggers heavy compilation, AND its unique-load
 * count COLLAPSES when it runs later (its classes get loaded by predecessors instead). That collapse
 * proves the classes are SHARED, so loading+compiling them early warms the JVM for everyone after it.
 * Such a test wants to run EARLY.
 *
 * <p>FIXED_WARMUP — loads many classes but the unique count stays high regardless of position: the
 * classes are EXCLUSIVE to it, so its cost is paid no matter where it runs. Leave it in place.
 *
 * <p>INERT — negligible on every axis. Leave in place.
 */
public final class JfrClassifier {

    public static final class Facts {
        public String test;
        public double gcOld, gcYoung, gcPauseMs, compilations, classLoads;
        public double uniqEarly, uniqLate;     // median unique-class-loads when run early vs late
        public double shareFraction;            // (uniqEarly - uniqLate)/uniqEarly, clamped [0,1]
        public int samples;
        public String category = "INERT";
    }

    /** Read every <order>.jfr.jsonl in jfrDir and aggregate per test. */
    public static Map<String, Facts> analyze(Path jfrDir, double minLoadsForWarmup, double minShareFraction) throws Exception {
        // test -> list of per-order observations
        Map<String, List<double[]>> obs = new LinkedHashMap<>(); // [position, gcOld, gcYoung, gcPauseMs, comp, loads, uniq]
        try (Stream<Path> s = Files.list(jfrDir)) {
            for (Path f : (Iterable<Path>) s.filter(p -> p.getFileName().toString().endsWith(".jfr.jsonl"))::iterator) {
                for (String l : Files.readAllLines(f)) {
                    l = l.trim(); if (l.isEmpty()) continue;
                    Map<String, Object> r = MiniJson.parseObject(l);
                    String t = (String) r.get("test");
                    if (t == null) continue;
                    obs.computeIfAbsent(t, k -> new ArrayList<>()).add(new double[]{
                            num(r, "position"), num(r, "gcOld"), num(r, "gcYoung"), num(r, "gcPauseMs"),
                            num(r, "compilations"), num(r, "classLoads"), num(r, "uniqueClassLoads")});
                }
            }
        }
        Map<String, Facts> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> e : obs.entrySet()) {
            List<double[]> v = e.getValue();
            Facts f = new Facts(); f.test = e.getKey(); f.samples = v.size();
            f.gcOld = median(v, 1); f.gcYoung = median(v, 2); f.gcPauseMs = median(v, 3);
            f.compilations = median(v, 4); f.classLoads = median(v, 5);
            // shareability: median unique-loads in the early-half vs late-half of this test's positions
            double posMed = median(v, 0);
            List<Double> early = new ArrayList<>(), late = new ArrayList<>();
            for (double[] o : v) (o[0] <= posMed ? early : late).add(o[6]);
            f.uniqEarly = early.isEmpty() ? median(v, 6) : medianOf(early);
            f.uniqLate = late.isEmpty() ? f.uniqEarly : medianOf(late);
            f.shareFraction = f.uniqEarly <= 0 ? 0 : Math.max(0, Math.min(1, (f.uniqEarly - f.uniqLate) / f.uniqEarly));
            f.category = classify(f, minLoadsForWarmup, minShareFraction);
            out.put(f.test, f);
        }
        return out;
    }

    private static String classify(Facts f, double minLoads, double minShare) {
        if (f.gcOld >= 1) return "GC_CARRIER";
        // Gate on uniqEarly (classes first-loaded when this test IS the loader), not median loads:
        // a shareable carrier loads many only when early, so its median is misleadingly low.
        if (f.uniqEarly >= minLoads || f.compilations >= minLoads) {
            return f.shareFraction >= minShare ? "WARMUP_SHAREABLE" : "FIXED_WARMUP";
        }
        return "INERT";
    }

    /** GC_CARRIERs to the front (heaviest full-GC load first), onto a fresh heap. */
    public static List<String> gcFront(List<String> initial, Map<String, Facts> facts) {
        List<String> carriers = new ArrayList<>();
        for (String t : initial) { Facts f = facts.get(t); if (f != null && "GC_CARRIER".equals(f.category)) carriers.add(t); }
        carriers.sort(Comparator.comparingDouble((String t) -> -facts.get(t).gcOld));
        return moveFront(initial, carriers);
    }

    /** Shareable warmup carriers to the front (heaviest compiler first) so the JVM warms for the rest. */
    public static List<String> warmupFront(List<String> initial, Map<String, Facts> facts) {
        List<String> carriers = new ArrayList<>();
        for (String t : initial) { Facts f = facts.get(t); if (f != null && "WARMUP_SHAREABLE".equals(f.category)) carriers.add(t); }
        carriers.sort(Comparator.comparingDouble((String t) -> -facts.get(t).compilations));
        return moveFront(initial, carriers);
    }

    /** GC carriers then shareable-warmup carriers, both up front. */
    public static List<String> gcAndWarmupFront(List<String> initial, Map<String, Facts> facts) {
        List<String> front = new ArrayList<>();
        for (String t : initial) { Facts f = facts.get(t); if (f != null && "GC_CARRIER".equals(f.category)) front.add(t); }
        front.sort(Comparator.comparingDouble((String t) -> -facts.get(t).gcOld));
        List<String> warm = new ArrayList<>();
        for (String t : initial) { Facts f = facts.get(t); if (f != null && "WARMUP_SHAREABLE".equals(f.category)) warm.add(t); }
        warm.sort(Comparator.comparingDouble((String t) -> -facts.get(t).compilations));
        front.addAll(warm);
        return moveFront(initial, front);
    }

    public static String report(Map<String, Facts> facts) {
        List<Facts> all = new ArrayList<>(facts.values());
        all.sort(Comparator.comparingDouble((Facts f) -> -(f.gcOld * 1e6 + f.compilations)));
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Facts f : all) counts.merge(f.category, 1, Integer::sum);
        sb.append("[jfr] categories: ").append(counts).append('\n');
        for (Facts f : all) {
            if ("INERT".equals(f.category)) continue;
            sb.append(String.format("[jfr] %-14s %-34s gcOld=%.0f gcPauseMs=%.0f comp=%.0f loads=%.0f share=%.2f (uniq %.0f->%.0f)%n",
                    f.category, simple(f.test), f.gcOld, f.gcPauseMs, f.compilations, f.classLoads,
                    f.shareFraction, (double) f.uniqEarly, (double) f.uniqLate));
        }
        return sb.toString();
    }

    private static List<String> moveFront(List<String> base, List<String> group) {
        java.util.Set<String> g = new java.util.LinkedHashSet<>(group);
        List<String> out = new ArrayList<>(group);
        for (String t : base) if (!g.contains(t)) out.add(t);
        return out;
    }

    private static double num(Map<String, Object> r, String k) {
        Object o = r.get(k); return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
    private static double median(List<double[]> v, int idx) {
        List<Double> s = new ArrayList<>(); for (double[] a : v) s.add(a[idx]); return medianOf(s);
    }
    private static double medianOf(List<Double> s) {
        if (s.isEmpty()) return 0;
        List<Double> c = new ArrayList<>(s); c.sort(Double::compare);
        int n = c.size(); return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2;
    }
    private static String simple(String fqn) { int i = fqn.lastIndexOf('.'); return i < 0 ? fqn : fqn.substring(i + 1); }
}
