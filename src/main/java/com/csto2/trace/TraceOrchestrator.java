package com.csto2.trace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Generates orders and runs {@link TraceRunner} once per order, each in a fresh JVM. */
public final class TraceOrchestrator {

    private final String classpath; // full target test/runtime classpath
    private final Path outDir;
    private final Path selfJar;
    private final List<String> jvmArgs;
    private final String javaBin;
    private long classTimeoutMs = 60000;
    private Path jfrDir; // when set, child records per-test JFR facts here
    private Path workDir; // when set, child JVMs run with this as their working directory

    public void setClassTimeoutMs(long ms) { this.classTimeoutMs = ms; }
    public void setJfrDir(Path dir) { this.jfrDir = dir; }
    /**
     * Working directory for child test JVMs. Maven Surefire runs each module's tests with the module
     * basedir as the cwd, so tests that resolve relative paths (e.g. {@code src/test/resources/...})
     * only pass when launched from there. Defaults to inheriting our cwd when unset.
     */
    public void setWorkDir(Path dir) { this.workDir = dir; }

    public TraceOrchestrator(String classpath, Path outDir, Path selfJar) {
        this(classpath, outDir, selfJar, List.of(), defaultJavaBin());
    }

    public TraceOrchestrator(String classpath, Path outDir, Path selfJar, List<String> jvmArgs) {
        this(classpath, outDir, selfJar, jvmArgs, defaultJavaBin());
    }

    public TraceOrchestrator(String classpath, Path outDir, Path selfJar, List<String> jvmArgs, String javaBin) {
        this.classpath = classpath;
        this.outDir = outDir;
        this.selfJar = selfJar;
        this.jvmArgs = jvmArgs;
        this.javaBin = javaBin;
    }

    /** Run {@code orders} traces (order 0 = initial/as-given, rest = seeded shuffles). */
    public Path run(List<String> tests, int orders, long seed) throws Exception {
        Path orderDir = outDir.resolve("orders");
        Path logDir = outDir.resolve("logs");
        Files.createDirectories(orderDir);
        Files.createDirectories(logDir);
        Path traceOut = outDir.resolve("trace.jsonl");
        Files.deleteIfExists(traceOut);

        Random rnd = new Random(seed);
        for (int i = 0; i < orders; i++) {
            List<String> order = new ArrayList<>(tests);
            String id = i == 0 ? "initial" : "shuffle-" + i;
            if (i != 0) Collections.shuffle(order, rnd);
            Path orderFile = orderDir.resolve(id + ".order");
            Files.write(orderFile, String.join("\n", order).getBytes(StandardCharsets.UTF_8));
            int code = runOne(orderFile, id, traceOut, logDir.resolve(id + ".log"));
            System.err.printf("[csto2] trace %-12s exit=%d%n", id, code);
        }
        return traceOut;
    }

    /** Run a single order file once, appending trace rows tagged with orderId. */
    public int runOrder(Path orderFile, String orderId, Path traceOut) throws Exception {
        Path logDir = outDir.resolve("logs");
        Files.createDirectories(logDir);
        return runOne(orderFile, orderId, traceOut, logDir.resolve(orderId.replace('#', '_').replace('/', '_') + ".log"));
    }

    private int runOne(Path orderFile, String orderId, Path traceOut, Path log) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(jvmArgs);
        cmd.add("-cp");
        cmd.add(classpath + File.pathSeparator + selfJar.toAbsolutePath());
        cmd.add("com.csto2.trace.TraceRunner");
        cmd.add("--order"); cmd.add(orderFile.toString());
        cmd.add("--out"); cmd.add(traceOut.toString());
        cmd.add("--order-id"); cmd.add(orderId);
        cmd.add("--class-timeout-ms"); cmd.add(String.valueOf(classTimeoutMs));
        if (jfrDir != null) { cmd.add("--jfr"); cmd.add(jfrDir.toAbsolutePath().toString()); }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        return p.waitFor();
    }

    private static String defaultJavaBin() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
