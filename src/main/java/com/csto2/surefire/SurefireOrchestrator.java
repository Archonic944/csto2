package com.csto2.surefire;

import com.csto2.trace.OrderRunner;
import com.csto2.util.Json;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.w3c.dom.Element;

/**
 * Runs each candidate order through REAL Maven Surefire, using the TestingResearchIllinois fork's
 * {@code -Dsurefire.runOrder=testorder} mode (driven by a custom Maven core extension) to execute an
 * arbitrary class permutation in a single reused fork. Per-class runtime and pass/fail are read back
 * from Surefire's own XML reports, so timing and greenness match {@code mvn test} exactly — which the
 * hand-rolled {@link com.csto2.trace.TraceRunner} could not guarantee (it ignored the project's
 * Surefire-configured system properties, argLine, etc.).
 *
 * <p>The forked extension forces {@code forkMode=once / forkCount=1 / reuseForks=true} (all classes in
 * one JVM, preserving the cross-class warmup/JIT/GC/alloc carryover this tool optimizes) and preserves
 * the project's own {@code argLine}/{@code systemPropertyVariables}. We add per-class JVM
 * instrumentation by prepending to the fork's argLine via the {@code KP_ARGLINE} env var.
 *
 * <p>Tier 1 (this class) records runtime + status. Richer per-class facts (alloc/jit/gc/JFR) come from
 * an injected agent via {@code KP_ARGLINE} and are merged in when present.
 */
public final class SurefireOrchestrator implements OrderRunner {

    private final Path moduleDir;   // the Maven module to run (mvn cwd); its pom drives the real classpath
    private final Path outDir;
    private final Path extJar;      // surefire-changing-maven-extension jar (loaded via -Dmaven.ext.class.path)
    private final String mvnBin;
    private String kpArgline;       // prepended to the fork argLine (e.g. -javaagent + JFR); null = none
    private Path agentJar;          // csto2 instrumentation agent; null = Tier-1 (runtime+status only)
    private final List<String> extraProps = new ArrayList<>();

    public SurefireOrchestrator(Path moduleDir, Path outDir, Path extJar, String mvnBin) {
        this.moduleDir = moduleDir.toAbsolutePath();
        this.outDir = outDir;
        this.extJar = extJar.toAbsolutePath();
        this.mvnBin = mvnBin;
    }

    public void setKpArgline(String argline) { this.kpArgline = argline; }
    /** Enable per-class instrumentation (alloc/jit/gc/JFR) by injecting this agent into the fork. */
    public void setAgent(Path agentJar) { this.agentJar = agentJar == null ? null : agentJar.toAbsolutePath(); }
    /** Extra -Dkey=value props passed to every mvn invocation. */
    public void addProp(String kv) { extraProps.add(kv); }

    @Override
    public Path run(List<String> tests, int orders, long seed) throws Exception {
        Path orderDir = outDir.resolve("orders");
        Files.createDirectories(orderDir);
        Path traceOut = outDir.resolve("trace.jsonl");
        Files.deleteIfExists(traceOut);
        Random rnd = new Random(seed);
        for (int i = 0; i < orders; i++) {
            List<String> order = new ArrayList<>(tests);
            String id = i == 0 ? "initial" : "shuffle-" + i;
            if (i != 0) Collections.shuffle(order, rnd);
            Path orderFile = orderDir.resolve(id + ".order");
            Files.write(orderFile, String.join("\n", order).getBytes(StandardCharsets.UTF_8));
            int code = runOrder(orderFile, id, traceOut);
            System.err.printf("[surefire] trace %-12s exit=%d%n", id, code);
        }
        return traceOut;
    }

    @Override
    public int runOrder(Path orderFile, String orderId, Path traceOut) throws Exception {
        Path reportsDir = moduleDir.resolve("target/surefire-reports");
        clearReports(reportsDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(mvnBin);
        cmd.add("surefire:test");
        cmd.add("-Dmaven.ext.class.path=" + extJar);
        cmd.add("-Dsurefire.runOrder=testorder");
        cmd.add("-Dtest=" + orderFile.toAbsolutePath());
        cmd.add("-Dmaven.build.cache.enabled=false");   // some projects (Avro) skip testCompile otherwise
        cmd.add("-Dmaven.test.failure.ignore=true");    // a red test must not abort the run; we read reports
        for (String p : extraProps) cmd.add(p);

        String safe = orderId.replace('#', '_').replace('/', '_');
        Path agentFacts = null;
        String argline = kpArgline;
        if (agentJar != null) {
            agentFacts = outDir.resolve("agent").resolve(safe + ".facts.jsonl").toAbsolutePath();
            Files.createDirectories(agentFacts.getParent());
            Path jfrOut = outDir.resolve("jfr").toAbsolutePath();
            Files.createDirectories(jfrOut);
            String inj = "-javaagent:" + agentJar + "=out=" + agentFacts + ",order=" + orderId + ",jfr=" + jfrOut;
            argline = (argline == null || argline.isBlank()) ? inj : inj + " " + argline;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(moduleDir.toFile());
        if (argline != null && !argline.isBlank()) pb.environment().put("KP_ARGLINE", argline);
        Path logDir = outDir.resolve("logs");
        Files.createDirectories(logDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logDir.resolve(safe + ".log").toFile());
        int code = pb.start().waitFor();

        Map<String, Integer> position = readPositions(orderFile);
        List<Map<String, Object>> rows = parseReports(reportsDir, orderId, position);
        if (agentFacts != null) mergeAgentFacts(rows, agentFacts);
        if (traceOut.getParent() != null) Files.createDirectories(traceOut.getParent());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows) sb.append(Json.write(r)).append('\n');
        Files.write(traceOut, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return code;
    }

    /** class FQN -> its index in the order file (the position the class ran at). */
    private static Map<String, Integer> readPositions(Path orderFile) throws Exception {
        Map<String, Integer> pos = new LinkedHashMap<>();
        int i = 0;
        for (String l : Files.readAllLines(orderFile)) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int hash = l.indexOf('#');                 // tolerate class#method entries
            String cls = hash < 0 ? l : l.substring(0, hash);
            if (!pos.containsKey(cls)) pos.put(cls, i++);
        }
        return pos;
    }

    /** Parse each TEST-*.xml testsuite into a trace row (orderId, position, test, runtimeMs, status, failures). */
    private static List<Map<String, Object>> parseReports(Path reportsDir, String orderId,
                                                          Map<String, Integer> position) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isDirectory(reportsDir)) return rows;
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        try (Stream<Path> s = Files.list(reportsDir)) {
            for (Path f : (Iterable<Path>) s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("TEST-") && n.endsWith(".xml");
            })::iterator) {
                Element root;
                try { root = db.parse(f.toFile()).getDocumentElement(); }
                catch (Exception e) { continue; }
                if (!"testsuite".equals(root.getTagName())) continue;
                String name = root.getAttribute("name");
                double timeSec = parseD(root.getAttribute("time"));
                long failures = (long) parseD(root.getAttribute("failures"));
                long errors = (long) parseD(root.getAttribute("errors"));
                long tests = (long) parseD(root.getAttribute("tests"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", orderId);
                row.put("position", position.getOrDefault(name, -1));
                row.put("test", name);
                row.put("runtimeMs", timeSec * 1000.0);
                row.put("testsFound", tests);
                row.put("failures", failures + errors);
                row.put("status", (failures + errors) == 0 ? "PASS" : "FAIL");
                rows.add(row);
            }
        }
        rows.sort((a, b) -> Integer.compare((int) a.get("position"), (int) b.get("position")));
        return rows;
    }

    /** Merge the agent's per-class facts (alloc/jit/gc/classload/thread) into the report rows by class name. */
    private static void mergeAgentFacts(List<Map<String, Object>> rows, Path agentFacts) {
        if (!Files.exists(agentFacts)) return;
        Map<String, String> byClass = new LinkedHashMap<>();
        try { for (String l : Files.readAllLines(agentFacts)) { String t = jsonStr(l, "test"); if (t != null) byClass.put(t, l); } }
        catch (Exception e) { return; }
        String[] keys = {"classesLoaded", "jitMs", "gcCount", "gcMs", "allocBytes", "threadDelta"};
        for (Map<String, Object> row : rows) {
            String line = byClass.get((String) row.get("test"));
            if (line == null) continue;
            for (String k : keys) {
                Double v = jsonNum(line, k);
                if (v != null) row.put(k, v == Math.floor(v) ? (Object) (long) (double) v : v);
            }
        }
    }

    private static String jsonStr(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i += needle.length();
        int j = json.indexOf('"', i);
        return j < 0 ? null : json.substring(i, j);
    }

    private static Double jsonNum(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i += needle.length();
        int j = i;
        while (j < json.length() && "+-0123456789.eE".indexOf(json.charAt(j)) >= 0) j++;
        try { return Double.parseDouble(json.substring(i, j)); } catch (RuntimeException e) { return null; }
    }

    private static double parseD(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.replace(",", "")); } catch (NumberFormatException e) { return 0; }
    }

    private static void clearReports(Path reportsDir) throws Exception {
        if (!Files.isDirectory(reportsDir)) return;
        try (Stream<Path> s = Files.list(reportsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith("TEST-") && n.endsWith(".xml")) Files.deleteIfExists(p);
            }
        }
    }
}
