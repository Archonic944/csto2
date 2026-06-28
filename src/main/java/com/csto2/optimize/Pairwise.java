package com.csto2.optimize;

import com.csto2.trace.OrderRunner;
import com.csto2.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dynamic-probe-driven pairwise layer. The position-slope model captures global (one-to-many)
 * effects; this captures specific producer->consumer relationships (e.g. a test that warms a shared
 * cache another test reads). Static analysis can't see the common cache pattern (mutation through a
 * static container reference is read+read, not write+read), so we drive confirmation dynamically and
 * use static only to NARROW candidates via shared mutable-container co-access.
 */
public final class Pairwise {

    /** Infra fields touched by everything: never a meaningful producer/consumer link. */
    private static final String[] INFRA = {"LoggerFactory#", "Logger#"};

    public static final class Pair {
        public final String producer, consumer;
        public double benefitMs; // consumer cost(cold/first) - cost(after producer)
        public Pair(String p, String c) { producer = p; consumer = c; }
    }

    /** Classes whose runtime residual (after the linear position fit) is large => predecessor-sensitive. */
    public static List<String> sensitiveConsumers(Path tracePath, OrderOptimizer.Model model, int topN) throws Exception {
        Map<String, double[]> ss = new HashMap<>(); // test -> [sumResid2, count]
        for (String line : Files.readAllLines(tracePath)) {
            line = line.trim(); if (line.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(line);
            String t = (String) r.get("test");
            OrderOptimizer.ClassModel cm = model.classes.get(t);
            if (cm == null) continue;
            double pos = ((Number) r.get("position")).doubleValue();
            double rt = ((Number) r.get("runtimeMs")).doubleValue();
            double resid = rt - (cm.intercept + cm.slope * pos);
            double[] a = ss.computeIfAbsent(t, k -> new double[2]);
            a[0] += resid * resid; a[1] += 1;
        }
        List<String> tests = new ArrayList<>(ss.keySet());
        tests.sort(Comparator.comparingDouble((String t) -> {
            double[] a = ss.get(t); return -(a[1] > 0 ? Math.sqrt(a[0] / a[1]) : 0);
        }));
        return tests.size() > topN ? tests.subList(0, topN) : tests;
    }

    /** Candidate producers for a consumer: classes sharing a non-infra static-container read. */
    public static Map<String, Set<String>> staticReads(Path factsJsonl) throws Exception {
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        for (String line : Files.readAllLines(factsJsonl)) {
            line = line.trim(); if (line.isEmpty()) continue;
            Map<String, Object> r = MiniJson.parseObject(line);
            String t = (String) r.get("test");
            Set<String> fs = new HashSet<>();
            // MiniJson skips arrays (returns null); re-extract reads cheaply by substring.
            fs.addAll(extractArray(line, "staticReads"));
            reads.put(t, fs);
        }
        return reads;
    }

    private static List<String> extractArray(String json, String key) {
        List<String> out = new ArrayList<>();
        int k = json.indexOf("\"" + key + "\":[");
        if (k < 0) return out;
        int start = json.indexOf('[', k), depth = 0, i = start;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
        }
        String body = json.substring(start + 1, i);
        for (String s : body.split(",")) {
            s = s.trim();
            if (s.length() >= 2 && s.startsWith("\"")) out.add(s.substring(1, s.length() - 1));
        }
        return out;
    }

    private static boolean isInfra(String field) {
        for (String p : INFRA) if (field.contains(p)) return true;
        return false;
    }

    /** Probe candidate pairs and return confirmed ones (benefit above thresholds). */
    public static List<Pair> probe(OrderRunner orch, Path probeOut,
                                   List<String> consumers, Map<String, Set<String>> reads,
                                   double minBenefitMs, double minFraction, int maxProducersPerConsumer) throws Exception {
        Files.deleteIfExists(probeOut);
        List<Pair> confirmed = new ArrayList<>();
        Path orderDir = probeOut.getParent().resolve("probe-orders");
        Files.createDirectories(orderDir);
        for (String consumer : consumers) {
            Set<String> cReads = reads.getOrDefault(consumer, Set.of());
            if (cReads.isEmpty()) continue;
            // rank candidate producers by # shared non-infra container reads
            List<String> producers = new ArrayList<>();
            Map<String, Integer> score = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : reads.entrySet()) {
                if (e.getKey().equals(consumer)) continue;
                TreeSet<String> shared = new TreeSet<>(cReads);
                shared.retainAll(e.getValue());
                shared.removeIf(Pairwise::isInfra);
                if (!shared.isEmpty()) { producers.add(e.getKey()); score.put(e.getKey(), shared.size()); }
            }
            producers.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
            if (producers.size() > maxProducersPerConsumer) producers = producers.subList(0, maxProducersPerConsumer);

            for (String producer : producers) {
                double warm = consumerCost(orch, orderDir, probeOut, producer, consumer, true);
                double cold = consumerCost(orch, orderDir, probeOut, producer, consumer, false);
                double benefit = cold - warm;
                if (benefit >= minBenefitMs && benefit >= minFraction * cold) {
                    Pair p = new Pair(producer, consumer);
                    p.benefitMs = benefit;
                    confirmed.add(p);
                }
            }
        }
        confirmed.sort((a, b) -> Double.compare(b.benefitMs, a.benefitMs));
        return confirmed;
    }

    /** Run [producer,consumer] (warm) or [consumer,producer] (cold) and return the consumer's runtime. */
    private static double consumerCost(OrderRunner orch, Path orderDir, Path probeOut,
                                       String producer, String consumer, boolean warm) throws Exception {
        String id = (warm ? "warm_" : "cold_") + sanitize(producer) + "__" + sanitize(consumer);
        Path of = orderDir.resolve(id + ".order");
        String body = warm ? producer + "\n" + consumer : consumer + "\n" + producer;
        Files.write(of, body.getBytes(StandardCharsets.UTF_8));
        orch.runOrder(of, id, probeOut);
        // read back the consumer's runtime for this run id
        double rt = Double.NaN;
        for (String line : Files.readAllLines(probeOut)) {
            if (line.contains("\"orderId\":\"" + id + "\"") && line.contains("\"test\":\"" + consumer + "\"")) {
                int ri = line.indexOf("\"runtimeMs\":");
                rt = Double.parseDouble(line.substring(ri + 12, numEnd(line, ri + 12)));
            }
        }
        return rt;
    }

    /** Slope-sorted base order, then enforce producer-before-consumer for confirmed pairs. */
    public static List<String> reorder(OrderOptimizer.Model model, List<String> tests, List<Pair> pairs) {
        List<String> order = OrderOptimizer.optimize(model, tests);
        for (Pair p : pairs) { // strongest first
            int pi = order.indexOf(p.producer), ci = order.indexOf(p.consumer);
            if (pi < 0 || ci < 0 || pi < ci) continue; // already producer-before-consumer
            order.remove(pi);
            ci = order.indexOf(p.consumer);
            order.add(ci, p.producer); // insert producer immediately before consumer
        }
        return order;
    }

    public static void writeReport(List<Pair> pairs, Path out) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Pair p : pairs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("producer", p.producer);
            m.put("consumer", p.consumer);
            m.put("benefitMs", Math.round(p.benefitMs * 10) / 10.0);
            rows.add(m);
        }
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("confirmedPairs", rows);
        rep.put("count", rows.size());
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, Json.write(rep).getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9]", "_"); }
    private static int numEnd(String s, int from) {
        int i = from; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++; return i;
    }
}
