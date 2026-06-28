package com.csto2.optimize;

import com.csto2.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calibrates a per-class linear position model from trace data and emits an optimized order.
 *
 * <p>Model: a class's runtime at position p is {@code intercept + slope*p}. The slope captures the
 * net position sensitivity of all mechanisms at once:
 * <ul>
 *   <li>warmup-sensitive classes are cheaper later (cold when early) => NEGATIVE slope => run late;</li>
 *   <li>a heavy allocator is cheaper earlier (favorable heap/GC ergonomics before the heap is tuned
 *       small by many little tests) => POSITIVE slope => run early.</li>
 * </ul>
 * Minimizing {@code sum(intercept + slope*pos)} over all permutations is, by the rearrangement
 * inequality, achieved by sorting classes by slope DESCENDING (largest slope gets position 0). This
 * is the single rule that both front-loads the allocator and tail-loads warmup classes.
 *
 * <p>Slopes are ridge-regularized so classes seen at few positions shrink toward 0 (no effect).
 */
public final class OrderOptimizer {

    /** Ridge term: shrinks slope toward 0 when position variance/coverage is low. */
    private static final double RIDGE = 50.0;

    public static final class ClassModel {
        public final String test;
        public double intercept;   // a in a + b*p
        public double slope;       // b
        public int obs;
        public double medAllocMB;  // explanatory only
        public double medGcCount;  // explanatory only
        ClassModel(String test) { this.test = test; }
    }

    public static final class Model {
        public final Map<String, ClassModel> classes = new LinkedHashMap<>();

        public double predict(List<String> order) {
            double total = 0;
            for (int i = 0; i < order.size(); i++) {
                ClassModel cm = classes.get(order.get(i));
                if (cm != null) total += Math.max(0, cm.intercept + cm.slope * i);
            }
            return total;
        }
    }

    public static Model calibrate(Path tracePath) throws Exception {
        // test -> observations
        Map<String, List<double[]>> obs = new LinkedHashMap<>(); // [position, runtimeMs, allocMB, gcCount]
        for (String line : Files.readAllLines(tracePath)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(line);
            String test = (String) r.get("test");
            double pos = num(r.get("position"));
            double rt = num(r.get("runtimeMs"));
            double alloc = r.containsKey("allocBytes") ? num(r.get("allocBytes")) / 1e6 : 0;
            double gc = r.containsKey("gcCount") ? num(r.get("gcCount")) : 0;
            obs.computeIfAbsent(test, k -> new ArrayList<>()).add(new double[]{pos, rt, alloc, gc});
        }
        Model m = new Model();
        for (Map.Entry<String, List<double[]>> e : obs.entrySet()) {
            ClassModel cm = new ClassModel(e.getKey());
            List<double[]> v = e.getValue();
            cm.obs = v.size();
            fitLine(v, cm);
            cm.medAllocMB = median(v, 2);
            cm.medGcCount = median(v, 3);
            m.classes.put(cm.test, cm);
        }
        return m;
    }

    /** Ridge least-squares fit of runtime ~ position. */
    private static void fitLine(List<double[]> v, ClassModel cm) {
        int n = v.size();
        double sx = 0, sy = 0;
        for (double[] p : v) { sx += p[0]; sy += p[1]; }
        double mx = sx / n, my = sy / n;
        double sxx = 0, sxy = 0;
        for (double[] p : v) { double dx = p[0] - mx; sxx += dx * dx; sxy += dx * (p[1] - my); }
        double slope = sxx + RIDGE == 0 ? 0 : sxy / (sxx + RIDGE);
        cm.slope = slope;
        cm.intercept = my - slope * mx;
    }

    /** Optimized order = classes sorted by slope DESCENDING (steepest-positive first). */
    public static List<String> optimize(Model m, List<String> tests) {
        List<String> order = new ArrayList<>(tests);
        order.sort(Comparator.comparingDouble((String t) -> {
            ClassModel cm = m.classes.get(t);
            return cm == null ? 0 : -cm.slope; // descending slope
        }));
        return order;
    }

    public static void writeReport(Model m, List<String> initial, List<String> optimized, Path out) throws Exception {
        double pn = m.predict(initial), po = m.predict(optimized);
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("predictedInitialMs", round(pn));
        rep.put("predictedOptimizedMs", round(po));
        rep.put("predictedImprovementPct", round(100.0 * (pn - po) / pn));
        List<Map<String, Object>> top = new ArrayList<>();
        m.classes.values().stream()
                .sorted(Comparator.comparingDouble((ClassModel c) -> -c.slope))
                .limit(8)
                .forEach(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("test", c.test);
                    row.put("slopeMsPerPos", round(c.slope));
                    row.put("interceptMs", round(c.intercept));
                    row.put("medAllocMB", round(c.medAllocMB));
                    row.put("medGcCount", round(c.medGcCount));
                    row.put("obs", c.obs);
                    top.add(row);
                });
        rep.put("steepestPositiveSlope_runEarly", top);
        List<Map<String, Object>> bottom = new ArrayList<>();
        m.classes.values().stream()
                .sorted(Comparator.comparingDouble(c -> c.slope))
                .limit(5)
                .forEach(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("test", c.test);
                    row.put("slopeMsPerPos", round(c.slope));
                    bottom.add(row);
                });
        rep.put("steepestNegativeSlope_runLate", bottom);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, Json.write(rep).getBytes(StandardCharsets.UTF_8));
    }

    private static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o)); }
    private static double median(List<double[]> v, int idx) {
        List<Double> s = new ArrayList<>();
        for (double[] p : v) s.add(p[idx]);
        s.sort(Double::compare);
        int n = s.size(); return n == 0 ? 0 : (n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2);
    }
    private static double round(double d) { return Math.round(d * 10.0) / 10.0; }
}
