package com.csto2.analyze;

import com.csto2.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Derives candidate interaction edges from per-test static facts. These are HYPOTHESES; the dynamic
 * tracer confirms and the simulator weights them. We only emit non-empty intersections.
 */
public final class StaticEdges {
    private StaticEdges() {}

    /** Ubiquitous test-infra namespaces: touched by ~everything, never a meaningful co-init signal. */
    private static final String[] INFRA_PREFIXES = {
        "call:org.junit", "new:org.junit", "read:org.junit", "write:org.junit",
        "call:org.mockito", "new:org.mockito", "call:org.hamcrest", "call:org.assertj",
        "call:org.opentest4j", "new:org.opentest4j", "call:org.apiguardian"
    };

    /** A shared touchpoint matters only if it is RARE; weight by inverse document frequency. */
    private static final double MIN_SHARED_SCORE = 0.75;
    private static final double MIN_LOCALITY_SCORE = 1.5;

    public static Map<String, Object> derive(List<StaticComprehension.TestFacts> facts) {
        int n = facts.size();
        Map<String, Integer> resDf = documentFrequency(facts, true);
        Map<String, Integer> typeDf = documentFrequency(facts, false);

        List<Map<String, Object>> pollution = new ArrayList<>();
        List<Map<String, Object>> sharedResource = new ArrayList<>();
        List<Map<String, Object>> locality = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            StaticComprehension.TestFacts a = facts.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                StaticComprehension.TestFacts b = facts.get(j);
                TreeSet<String> wr = intersect(a.staticWrites, b.staticReads);
                if (!wr.isEmpty()) {
                    pollution.add(edge("from", a.test, "to", b.test, "mechanism", "static-field",
                            "fields", new ArrayList<>(wr)));
                }
            }
            for (int j = i + 1; j < n; j++) {
                StaticComprehension.TestFacts b = facts.get(j);

                TreeSet<String> res = intersect(a.libResources, b.libResources);
                double resScore = idfScore(res, resDf, n, true);
                if (resScore >= MIN_SHARED_SCORE) {
                    sharedResource.add(edge("a", a.test, "b", b.test, "mechanism", "shared-resource",
                            "score", round(resScore), "shared", topByRarity(res, resDf, 8)));
                }

                TreeSet<String> types = intersect(a.appTypes, b.appTypes);
                double typeScore = idfScore(types, typeDf, n, false);
                if (typeScore >= MIN_LOCALITY_SCORE) {
                    locality.add(edge("a", a.test, "b", b.test, "mechanism", "shared-code",
                            "score", round(typeScore), "overlap", types.size()));
                }
            }
        }
        sharedResource.sort((x, y) -> Double.compare((double) y.get("score"), (double) x.get("score")));
        locality.sort((x, y) -> Double.compare((double) y.get("score"), (double) x.get("score")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pollutionEdges", pollution);
        out.put("sharedResourceEdges", sharedResource);
        out.put("localityEdges", locality);
        out.put("counts", edge("pollution", pollution.size(),
                "sharedResource", sharedResource.size(), "locality", locality.size()));
        return out;
    }

    private static boolean isInfra(String key) {
        for (String p : INFRA_PREFIXES) if (key.startsWith(p)) return true;
        return false;
    }

    private static Map<String, Integer> documentFrequency(List<StaticComprehension.TestFacts> facts, boolean resources) {
        Map<String, Integer> df = new java.util.HashMap<>();
        for (StaticComprehension.TestFacts f : facts) {
            for (String x : (resources ? f.libResources : f.appTypes)) {
                if (resources && isInfra(x)) continue;
                df.merge(x, 1, Integer::sum);
            }
        }
        return df;
    }

    /** Sum of inverse-document-frequency weights over shared, non-infra items. */
    private static double idfScore(TreeSet<String> shared, Map<String, Integer> df, int n, boolean resources) {
        double s = 0;
        for (String x : shared) {
            if (resources && isInfra(x)) continue;
            Integer d = df.get(x);
            if (d == null || d <= 1) continue; // not actually shared in df terms
            s += Math.log((double) n / d);
        }
        return s;
    }

    private static List<String> topByRarity(TreeSet<String> shared, Map<String, Integer> df, int limit) {
        List<String> items = new ArrayList<>();
        for (String x : shared) if (!isInfra(x) && df.getOrDefault(x, 0) > 1) items.add(x);
        items.sort((p, q) -> Integer.compare(df.getOrDefault(p, 0), df.getOrDefault(q, 0)));
        return items.size() > limit ? items.subList(0, limit) : items;
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }

    public static void write(Map<String, Object> edges, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        Files.write(out, Json.write(edges).getBytes(StandardCharsets.UTF_8));
    }

    private static TreeSet<String> intersect(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> r = new TreeSet<>(a);
        r.retainAll(b);
        return r;
    }

    private static Map<String, Object> edge(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
