package com.csto2.cli;

import com.csto2.Csto2;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Interactive menu loop that drives the full CSTO v2 pipeline. Each menu item runs one existing
 * subcommand via {@link Csto2#dispatch}, sharing a single in-memory config and auto-wiring the
 * artifacts each stage produces into the inputs the next stage needs:
 *
 * <pre>
 *   discover  -> writes a runnable test list, points 'tests' at it
 *   analyze   -> writes static-facts.jsonl, points 'facts' at it
 *   trace     -> writes trace.jsonl (+ jfr/), points 'trace' and 'jfr-dir' at them
 *   select / validate / pairwise -> consume the above, measure, ship a green order
 * </pre>
 *
 * Config is prompted for on demand (nothing is persisted between launches). Output paths are derived
 * from a single base out dir so stages compose without the user wiring file paths by hand.
 */
public final class Repl {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    /** Shared flag values, keyed exactly like the CLI flags (cp, app, lib, tests, jvmargs, ...). */
    private final Map<String, String> cfg = new LinkedHashMap<>();

    /** Config keys offered by the 'configure' screen, with a one-line hint each. */
    private static final String[][] CONFIG_KEYS = {
        {"cp", "target test+runtime classpath (discover/trace/select/validate/pairwise)"},
        {"app", "app classes classpath (analyze only)"},
        {"lib", "library classpath, optional (analyze only)"},
        {"tests", "file of fully-qualified test class names, one per line"},
        {"out", "base output dir (default .csto2)"},
        {"jvmargs", "extra child-JVM args as ONE value, e.g. \"--add-opens ... -Dfoo=bar\""},
        {"java", "JAVA_HOME or path to java for child JVMs (optional)"},
        {"workdir", "child-JVM working dir if tests use relative resource paths (optional)"},
        {"orders", "trace: number of orders to run (default 6)"},
        {"seed", "trace: shuffle seed (default 1)"},
        {"repeats", "measurement rounds for select/validate/pairwise (lower = faster, noisier; select default 4)"},
        {"surefire-ext", "testorder-fork extension jar (optional; auto-located from ~/.m2 when blank)"},
        {"mvn", "mvn binary/wrapper for the Surefire runner (optional; default ./mvnw or mvn)"},
    };

    public void run() throws Exception {
        run(Paths.get(System.getProperty("user.dir")));
    }

    /** Launch the REPL; if {@code startDir} (or cwd) looks like a Maven project, offer to load it. */
    public void run(Path startDir) throws Exception {
        System.out.println("CSTO v2 pipeline REPL — type a number, or 'q' to quit.");
        if (startDir != null && Files.exists(startDir.resolve("pom.xml"))) {
            String yn = prompt("Detected Maven project at " + startDir.toAbsolutePath()
                    + " — load classpath + tests now? (Y/n)");
            if (yn == null) return;
            if (!yn.trim().equalsIgnoreCase("n")) {
                try { loadProject(startDir); }
                catch (Throwable t) { System.out.println("[project] autodetect failed: " + t); }
            }
        }
        reportRunnerStatus();
        loop:
        while (true) {
            printMenu();
            String choice = prompt("choice");
            if (choice == null) break;
            choice = choice.trim().toLowerCase();
            try {
                switch (choice) {
                    case "1" -> configure();
                    case "2" -> state();
                    case "p" -> project();
                    case "3" -> discover();
                    case "4" -> analyze();
                    case "5" -> trace();
                    case "6" -> select();
                    case "7" -> validate();
                    case "8" -> pairwise();
                    case "9" -> fullPipeline();
                    case "q", "0", "quit", "exit" -> { break loop; }
                    case "" -> {}
                    default -> System.out.println("unknown choice: " + choice);
                }
            } catch (Throwable t) {
                System.out.println("[error] " + choice + " failed: " + t);
            }
        }
        System.out.println("bye.");
    }

    private void printMenu() {
        System.out.println();
        System.out.println("==== CSTO v2 ====  (base out: " + baseDir() + ")");
        System.out.println("  1) configure        set classpath / tests / JVM args / params");
        System.out.println("  2) state            show current config + produced artifacts");
        System.out.println("  p) project          autodetect cp + tests + workdir from a Maven project");
        System.out.println("  --- stages ---");
        System.out.println("  3) discover         filter the test list to runnable classes");
        System.out.println("  4) analyze          static comprehension (WALA) -> static-facts");
        System.out.println("  5) trace            run N orders (with JFR) -> trace.jsonl");
        System.out.println("  6) select           green-gated candidate selection -> ship order");
        System.out.println("  7) validate         slope-model initial-vs-optimized A/B");
        System.out.println("  8) pairwise         producer->consumer warm-pair probe");
        System.out.println("  --- combined ---");
        System.out.println("  9) full pipeline    discover -> trace -> select");
        System.out.println("  q) quit");
    }

    // ---- stages ----------------------------------------------------------------------------------

    private void discover() throws Exception {
        require("cp"); require("tests");
        Path runnable = baseDir().resolve("tests.runnable");
        Map<String, String> a = args("cp", "jvmargs", "java", "workdir");
        a.put("tests", cfg.get("tests"));
        a.put("out", runnable.toString());
        Csto2.dispatch("discover", a);
        cfg.put("tests", runnable.toString());
        System.out.println("[wired] tests -> " + runnable);
    }

    private void analyze() throws Exception {
        require("app"); require("tests");
        Path outDir = baseDir().resolve("comprehension");
        Map<String, String> a = args("app", "lib");
        a.put("tests", cfg.get("tests"));
        a.put("out", outDir.toString());
        Csto2.dispatch("analyze", a);
        Path facts = outDir.resolve("static-facts.jsonl");
        cfg.put("facts", facts.toString());
        System.out.println("[wired] facts -> " + facts);
    }

    private void trace() throws Exception {
        require("cp"); require("tests");
        Path outDir = baseDir().resolve("trace");
        Path traceJsonl = outDir.resolve("trace.jsonl");
        Map<String, String> a = args("cp", "jvmargs", "java", "workdir", "orders", "seed", "surefire-ext", "mvn", "kp-argline");
        a.put("tests", cfg.get("tests"));
        a.put("out", outDir.toString());
        Csto2.dispatch("trace", a);
        if (!Files.exists(traceJsonl) || Files.readAllLines(traceJsonl).stream().noneMatch(l -> !l.isBlank())) {
            cfg.remove("trace");
            System.out.println("[error] trace produced no rows — every order's Surefire run failed (no reports).");
            printFirstLogError(outDir.resolve("logs"));
            throw new IllegalStateException("trace produced no rows (see " + outDir.resolve("logs") + ")");
        }
        cfg.put("trace", traceJsonl.toString());
        cfg.put("jfr-dir", outDir.resolve("jfr").toString());
        System.out.println("[wired] trace -> " + cfg.get("trace") + "  jfr-dir -> " + cfg.get("jfr-dir"));
        warnNonGreen(traceJsonl);
    }

    private void select() throws Exception {
        require("cp"); require("tests"); requireFile("trace");
        Map<String, String> a = args("cp", "trace", "jfr-dir", "jvmargs", "java", "workdir", "repeats", "surefire-ext", "mvn", "kp-argline");
        a.put("tests", cfg.get("tests"));
        a.put("out", baseDir().resolve("select").toString());
        Csto2.dispatch("select", a);
    }

    private void validate() throws Exception {
        require("cp"); require("tests"); requireFile("trace");
        Map<String, String> a = args("cp", "trace", "jvmargs", "java", "workdir", "repeats", "surefire-ext", "mvn", "kp-argline");
        a.put("tests", cfg.get("tests"));
        a.put("out", baseDir().resolve("validate").toString());
        Csto2.dispatch("validate", a);
    }

    private void pairwise() throws Exception {
        require("cp"); requireFile("trace"); requireFile("facts");
        Map<String, String> a = args("cp", "trace", "facts", "jvmargs", "java", "workdir", "repeats", "surefire-ext", "mvn", "kp-argline");
        a.put("out", baseDir().resolve("pairwise").toString());
        Csto2.dispatch("pairwise", a);
    }

    private void fullPipeline() throws Exception {
        System.out.println("[pipeline] discover -> trace -> select");
        discover();
        trace();
        select();
        System.out.println("[pipeline] done.");
    }

    // ---- project autodetect ----------------------------------------------------------------------

    private void project() throws Exception {
        String dir = prompt("project dir [" + Paths.get(System.getProperty("user.dir")).toAbsolutePath() + "]");
        if (dir == null) return;
        dir = dir.trim();
        Path p = dir.isEmpty() ? Paths.get(System.getProperty("user.dir")) : Paths.get(dir);
        loadProject(p);
    }

    /**
     * Infer everything a stage needs from a Maven project: the test+runtime classpath (project
     * classes + test classes + resolved dependency jars via {@code mvn dependency:build-classpath}),
     * the candidate test list (every top-level class under {@code target/test-classes} — the
     * {@code discover} stage then filters these to actually-runnable test classes), and the child-JVM
     * working dir (the project basedir). Compiles the test classes first if they are missing.
     */
    private void loadProject(Path dir) throws Exception {
        if (!Files.exists(dir.resolve("pom.xml")))
            throw new IllegalStateException("no pom.xml at " + dir.toAbsolutePath() + " (Maven projects only)");
        Path classes = dir.resolve("target/classes");
        Path testClasses = dir.resolve("target/test-classes");
        String mvn = mvnBin(dir);

        if (!Files.isDirectory(testClasses)) {
            String yn = prompt("target/test-classes missing — run '" + mvn + " test-compile' now? (Y/n)");
            // -Dmaven.build.cache.enabled=false: the Maven build-cache extension (e.g. Avro) reports
            // BUILD SUCCESS by restoring from cache and SKIPS compiler:testCompile, so target/ is never
            // written to disk. Disabling it forces a real compile that materializes target/test-classes.
            if (yn != null && !yn.trim().equalsIgnoreCase("n"))
                runMvn(dir, mvn, "-q", "-Dmaven.build.cache.enabled=false", "test-compile");
        }
        if (!Files.isDirectory(testClasses))
            throw new IllegalStateException("still no target/test-classes after test-compile — compile the "
                    + "project manually (a build-cache extension may be skipping it; try "
                    + "'mvn -Dmaven.build.cache.enabled=false test-compile')");

        // Resolve the dependency classpath into a file, then prepend the project's own output dirs.
        Files.createDirectories(baseDir());
        Path depCp = baseDir().resolve("deps.classpath").toAbsolutePath();
        System.out.println("[project] resolving dependency classpath (mvn)...");
        runMvn(dir, mvn, "-q", "-Dmaven.build.cache.enabled=false", "dependency:build-classpath", "-Dmdep.outputFile=" + depCp);
        String deps = Files.exists(depCp) ? Files.readString(depCp).trim() : "";
        StringBuilder cp = new StringBuilder();
        cp.append(testClasses.toAbsolutePath());
        if (Files.isDirectory(classes)) cp.append(File.pathSeparator).append(classes.toAbsolutePath());
        if (!deps.isEmpty()) cp.append(File.pathSeparator).append(deps);
        String fullCp = ensureJUnitLauncher(cp.toString(), dir, mvn);
        cfg.put("cp", fullCp);

        // Candidate test list: every top-level .class under target/test-classes.
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.walk(testClasses)) {
            s.filter(f -> f.toString().endsWith(".class"))
             .map(f -> testClasses.relativize(f).toString())
             .filter(rel -> !rel.contains("$"))            // skip inner/anonymous classes
             .map(rel -> rel.substring(0, rel.length() - ".class".length()).replace(File.separatorChar, '.'))
             .sorted()
             .forEach(names::add);
        }
        Path testsFile = baseDir().resolve("tests.all").toAbsolutePath();
        Files.write(testsFile, String.join("\n", names).getBytes(StandardCharsets.UTF_8));
        cfg.put("tests", testsFile.toString());
        cfg.put("workdir", dir.toAbsolutePath().toString());

        System.out.printf("[project] %s%n", dir.toAbsolutePath());
        System.out.printf("[project]   cp:    %d entries%n", fullCp.split(File.pathSeparator).length);
        System.out.printf("[project]   tests: %d candidate classes -> %s%n", names.size(), testsFile);
        System.out.printf("[project]   workdir: %s%n", dir.toAbsolutePath());
        System.out.println("[project] next: run 'discover' to filter to runnable tests, then 'full pipeline'.");
    }

    /**
     * Maven Surefire injects {@code junit-platform-launcher} into the test runtime, but it is not a
     * declared project dependency, so {@code dependency:build-classpath} omits it — and our child JVM
     * needs its {@code LauncherFactory} to run JUnit 5 tests. If the cp has the JUnit Platform but not
     * the launcher, derive the matching launcher jar from a sibling platform jar's repo path (fetching
     * it into the local repo if absent) and append it. No-op for JUnit 4-only projects.
     */
    private String ensureJUnitLauncher(String cp, Path dir, String mvn) throws Exception {
        if (cp.contains("junit-platform-launcher")) return cp;
        String anchor = null;
        for (String e : cp.split(File.pathSeparator)) {
            if (e.contains("junit-platform-commons")) { anchor = e; break; }
            if (anchor == null && e.contains("junit-platform-engine")) anchor = e;
        }
        if (anchor == null) return cp; // not a JUnit 5 project; JUnit4Runner handles the rest
        Path launcher = Paths.get(anchor.replace("junit-platform-commons", "junit-platform-launcher")
                                        .replace("junit-platform-engine", "junit-platform-launcher"));
        if (!Files.exists(launcher)) {
            String fn = launcher.getFileName().toString();
            String ver = fn.substring("junit-platform-launcher-".length(), fn.length() - ".jar".length());
            System.out.println("[project] fetching junit-platform-launcher:" + ver + " (Surefire-provided, not a project dep)...");
            try { runMvn(dir, mvn, "-q", "dependency:get", "-Dartifact=org.junit.platform:junit-platform-launcher:" + ver); }
            catch (Throwable t) { System.out.println("[warn] could not fetch junit-platform-launcher: " + t); }
        }
        if (Files.exists(launcher)) {
            System.out.println("[project]   +junit-platform-launcher");
            return cp + File.pathSeparator + launcher.toAbsolutePath();
        }
        System.out.println("[warn] junit-platform-launcher not on classpath; JUnit 5 trace runs will fail to find a runner.");
        return cp;
    }

    /** Prefer the project's Maven wrapper if present, else fall back to 'mvn' on PATH. */
    private static String mvnBin(Path dir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = dir.resolve(win ? "mvnw.cmd" : "mvnw");
        return Files.isExecutable(wrapper) ? wrapper.toAbsolutePath().toString() : "mvn";
    }

    private static void runMvn(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IllegalStateException(cmd[0] + " exited " + code + " in " + dir);
    }

    // ---- config / state --------------------------------------------------------------------------

    private void configure() throws Exception {
        System.out.println("Set values (blank keeps current).");
        for (String[] k : CONFIG_KEYS) {
            String cur = cfg.get(k[0]);
            String shown = cur == null ? "<unset>" : cur;
            String v = prompt(String.format("%-12s [%s]  (%s)", k[0], shown, k[1]));
            if (v == null) return;
            v = v.trim();
            if (!v.isEmpty()) cfg.put(k[0], v);
        }
    }

    /** Report whether the Surefire testorder fork + instrumentation agent are available for measurement. */
    private void reportRunnerStatus() {
        Path ext = cfg.containsKey("surefire-ext") ? Paths.get(cfg.get("surefire-ext")) : Csto2.defaultSurefireExt();
        if (ext != null && Files.exists(ext)) {
            System.out.println("[surefire] testorder fork: " + ext);
        } else {
            System.out.println("[surefire] NOT FOUND — measurement needs the testorder fork. Build & install it");
            System.out.println("           (mvn install -DskipTests -Drat.skip -Denforcer.skip in the maven-surefire fork),");
            System.out.println("           or set 'surefire-ext' via configure.");
        }
        Path agent = locateAgent();
        System.out.println("[surefire] instrumentation agent: "
                + (agent != null && Files.exists(agent) ? agent : "(missing — runtime+status only)"));
    }

    /** The csto2-agent.jar shipped next to the running csto2.jar. */
    private Path locateAgent() {
        try {
            Path self = Paths.get(Repl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return self.getParent() == null ? null : self.getParent().resolve("csto2-agent.jar");
        } catch (Exception e) { return null; }
    }

    private void state() {
        System.out.println("--- config ---");
        for (String[] k : CONFIG_KEYS) {
            String v = cfg.get(k[0]);
            if (v != null) System.out.printf("  %-9s = %s%n", k[0], v);
        }
        System.out.println("--- wired artifacts ---");
        for (String k : new String[]{"facts", "trace", "jfr-dir"}) {
            if (cfg.containsKey(k)) System.out.printf("  %-9s = %s%n", k, cfg.get(k));
        }
        System.out.println("  base out  = " + baseDir());
        reportRunnerStatus();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Output base dir, always ABSOLUTE: derived artifact paths (tests.runnable, trace.jsonl, order
     * files) are passed to child JVMs that run with the target project as their cwd, so a relative
     * path would resolve against the wrong directory.
     */
    private Path baseDir() {
        return Paths.get(cfg.getOrDefault("out", ".csto2")).toAbsolutePath();
    }

    /** Copy the given config keys (when present and non-blank) into a fresh flag map. */
    private Map<String, String> args(String... keys) {
        Map<String, String> a = new LinkedHashMap<>();
        for (String k : keys) {
            String v = cfg.get(k);
            if (v != null && !v.isBlank()) a.put(k, v);
        }
        return a;
    }

    /** Ensure a required config value is set, prompting for it if missing. */
    private void require(String key) throws IOException {
        String v = cfg.get(key);
        if (v != null && !v.isBlank()) return;
        String hint = key;
        for (String[] k : CONFIG_KEYS) if (k[0].equals(key)) { hint = key + " — " + k[1]; break; }
        String entered = prompt("need " + hint);
        if (entered == null || entered.isBlank()) throw new IllegalStateException("missing required value: " + key);
        cfg.put(key, entered.trim());
    }

    /** Like {@link #require} but also checks the referenced file actually exists on disk. */
    private void requireFile(String key) throws IOException {
        require(key);
        Path p = Paths.get(cfg.get(key));
        if (!Files.exists(p))
            throw new IllegalStateException(key + " file not found: " + p
                    + (key.equals("trace") ? " — run 'trace' first" : key.equals("facts") ? " — run 'analyze' first" : ""));
    }

    /** Scan a completed trace and warn about any class that was not green (FAIL/TIMEOUT) in any order. */
    private void warnNonGreen(Path traceJsonl) throws IOException {
        Map<String, String> nonGreen = new LinkedHashMap<>(); // test -> first non-PASS status seen
        int rows = 0;
        for (String line : Files.readAllLines(traceJsonl)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            rows++;
            String status = strField(line, "status");
            String test = strField(line, "test");
            if (test != null && status != null && !status.equals("PASS")) nonGreen.putIfAbsent(test, status);
        }
        if (nonGreen.isEmpty()) {
            System.out.println("[trace] all classes green across all orders (" + rows + " rows).");
            return;
        }
        System.out.println("[warn] " + nonGreen.size() + " class(es) NOT green — selection can only ship an order "
                + "in which these are green, so they cap the achievable speedup:");
        for (Map.Entry<String, String> e : nonGreen.entrySet())
            System.out.printf("   %-8s %s%n", e.getValue(), simple(e.getKey()));
    }

    /** Print the first error/exception line from a trace child log to explain a failed trace. */
    private void printFirstLogError(Path logDir) {
        try (Stream<Path> s = Files.list(logDir)) {
            Path log = s.filter(p -> p.toString().endsWith(".log")).findFirst().orElse(null);
            if (log == null) return;
            String firstErr = null;
            for (String line : Files.readAllLines(log)) {
                String t = line.trim();
                if (t.contains("Caused by:") || t.contains("BUILD FAILURE")
                        || (t.startsWith("[ERROR]") && t.length() > 8 && !t.contains("\tat "))) {
                    firstErr = t;
                    break;
                }
                if (firstErr == null && (t.contains("Exception") || t.contains("ClassNotFound")))
                    firstErr = t;
            }
            if (firstErr != null) System.out.println("   " + log.getFileName() + ": " + firstErr);
        } catch (IOException ignored) {}
    }

    /** Extract a string field value from one flat JSON line (e.g. "status":"PASS"). */
    private static String strField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i += needle.length();
        int j = json.indexOf('"', i);
        return j < 0 ? null : json.substring(i, j);
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private String prompt(String label) throws IOException {
        System.out.print(label + "> ");
        System.out.flush();
        return in.readLine();
    }
}
