package com.csto2.trace;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a supplied order of test classes IN ONE JVM, recording per-class runtime plus durable-state
 * deltas captured from platform MXBeans at each class boundary. Output is one JSON line per class.
 *
 * <p>This is the dynamic half of the comprehension engine. MXBean deltas (classes loaded, JIT
 * compile time, GC, threads) are the cheap, in-process signals that quantify the warmup/JIT cost
 * the static side can only hypothesize. Run inside a JVM that has the target test classpath.
 */
public final class TraceRunner {
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        if (a.containsKey("discover")) { discover(a); return; }
        if (a.containsKey("explain")) { explain(a.get("explain")); return; }
        Path order = Paths.get(req(a, "order"));
        Path out = Paths.get(req(a, "out"));
        String orderId = a.getOrDefault("order-id", order.getFileName().toString());
        long classTimeoutMs = Long.parseLong(a.getOrDefault("class-timeout-ms", "60000"));
        List<String> tests = new ArrayList<>();
        for (String l : Files.readAllLines(order)) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("#")) tests.add(l); }
        if (out.getParent() != null) Files.createDirectories(out.getParent());

        Runner launcher = selectRunner();
        System.err.println("[trace] runner=" + launcher.getClass().getSimpleName());
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        CompilationMXBean comp = ManagementFactory.getCompilationMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        AllocMeter alloc = AllocMeter.create();
        JfrProbe jfr = a.containsKey("jfr") ? JfrProbe.start() : null;

        boolean progress = System.getenv("CSTO_TRACE_PROGRESS") != null;
        int pos = 0;
        int failed = 0;
        for (String test : tests) {
            long loaded0 = cl.getTotalLoadedClassCount();
            long jit0 = comp == null ? 0 : comp.getTotalCompilationTime();
            long[] gc0 = gcSnapshot(gcs);
            int thr0 = threads.getThreadCount();

            JfrProbe.Window jw = jfr != null ? jfr.begin(test, pos) : null;
            long t0 = System.nanoTime();
            Result r = runWithTimeout(launcher, test, classTimeoutMs, alloc);
            if (jfr != null) jfr.end(jw);
            double runtimeMs = (System.nanoTime() - t0) / 1_000_000.0;
            boolean timedOut = r == null;
            if (timedOut) { r = Result.error(); runtimeMs = classTimeoutMs; }

            long[] gc1 = gcSnapshot(gcs);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", orderId);
            row.put("position", pos);
            row.put("test", test);
            row.put("runtimeMs", runtimeMs);
            row.put("classesLoaded", cl.getTotalLoadedClassCount() - loaded0);
            row.put("jitMs", comp == null ? 0 : comp.getTotalCompilationTime() - jit0);
            row.put("gcCount", gc1[0] - gc0[0]);
            row.put("gcMs", gc1[1] - gc0[1]);
            row.put("allocBytes", r.allocBytes);
            row.put("threadDelta", threads.getThreadCount() - thr0);
            row.put("testsFound", r.found);
            row.put("failures", r.failures);
            row.put("status", timedOut ? "TIMEOUT" : (r.failures == 0 && !r.errored ? "PASS" : "FAIL"));
            if (r.firstError != null) row.put("error", r.firstError);
            // Flush each row immediately: visible progress + partial data survives a hang/timeout.
            Files.write(out, (com.csto2.util.Json.write(row) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (r.failures != 0 || r.errored) failed++;
            if (progress) System.err.printf("[trace] %s [%d/%d] %.0fms %s%n",
                    orderId, pos + 1, tests.size(), runtimeMs, test.substring(test.lastIndexOf('.') + 1));
            pos++;
        }
        System.err.printf("[trace] order=%s classes=%d failedClasses=%d%n", orderId, tests.size(), failed);
        if (jfr != null) {
            String safe = orderId.replace('#', '_').replace('/', '_');
            Path jfrDir = Paths.get(a.get("jfr"));
            jfr.finishAndWrite(jfrDir.resolve(safe + ".jfr"), jfrDir.resolve(safe + ".jfr.jsonl"), orderId);
        }
        // Force exit: real test suites leak non-daemon threads (executor pools, etc.) that would
        // otherwise block JVM shutdown indefinitely. Surefire force-kills its fork for the same
        // reason. Results are already flushed per-class above, so halting here is safe.
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }

    /**
     * Filters a candidate list down to real, runnable test classes: concrete (non-abstract,
     * non-interface) classes that have at least one @Test method (including inherited ones) or a
     * @RunWith. This drops abstract base classes and non-test fixtures that merely end in "Test" —
     * matching what Maven Surefire actually runs. Runs in the target JVM (has JUnit on classpath).
     */
    private static void discover(Map<String, String> a) throws Exception {
        Path testsFile = Paths.get(req(a, "tests"));
        Path out = Paths.get(req(a, "out"));
        List<String> kept = new ArrayList<>();
        int abstractSkip = 0, noTestSkip = 0, loadSkip = 0;
        for (String name : Files.readAllLines(testsFile)) {
            name = name.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            Class<?> c;
            try { c = Class.forName(name, false, TraceRunner.class.getClassLoader()); }
            catch (Throwable t) { loadSkip++; continue; }
            if (java.lang.reflect.Modifier.isAbstract(c.getModifiers()) || c.isInterface()) { abstractSkip++; continue; }
            if (!hasTests(c)) { noTestSkip++; continue; }
            kept.add(name);
        }
        Files.write(out, String.join("\n", kept).getBytes(StandardCharsets.UTF_8));
        System.err.printf("[discover] kept=%d  dropped: abstract/iface=%d noTest=%d unloadable=%d -> %s%n",
                kept.size(), abstractSkip, noTestSkip, loadSkip, out);
    }

    /** Run one class via the JUnit Platform and print each failure's test id + exception. */
    private static void explain(String className) throws Exception {
        Class<?> factory = Class.forName("org.junit.platform.launcher.core.LauncherFactory");
        Class<?> reqBuilder = Class.forName("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
        Class<?> reqClass = Class.forName("org.junit.platform.launcher.LauncherDiscoveryRequest");
        Class<?> listenerIface = Class.forName("org.junit.platform.launcher.TestExecutionListener");
        Class<?> launcherIface = Class.forName("org.junit.platform.launcher.Launcher");
        Class<?> selectorIface = Class.forName("org.junit.platform.engine.DiscoverySelector");
        Class<?> selectors = Class.forName("org.junit.platform.engine.discovery.DiscoverySelectors");
        Class<?> sumListener = Class.forName("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
        Class<?> summaryIface = Class.forName("org.junit.platform.launcher.listeners.TestExecutionSummary");
        Object launcher = factory.getMethod("create").invoke(null);
        Object selector = selectors.getMethod("selectClass", Class.class).invoke(null, Class.forName(className));
        Object arr = Array.newInstance(selectorIface, 1); Array.set(arr, 0, selector);
        Object builder = reqBuilder.getMethod("request").invoke(null);
        builder = reqBuilder.getMethod("selectors", arr.getClass()).invoke(builder, arr);
        Object req = reqBuilder.getMethod("build").invoke(builder);
        Object listener = sumListener.getConstructor().newInstance();
        Object listeners = Array.newInstance(listenerIface, 1); Array.set(listeners, 0, listener);
        launcherIface.getMethod("execute", reqClass, listeners.getClass()).invoke(launcher, req, listeners);
        Object summary = sumListener.getMethod("getSummary").invoke(listener);
        List<?> failures = (List<?>) summaryIface.getMethod("getFailures").invoke(summary);
        Class<?> failIface = Class.forName("org.junit.platform.launcher.listeners.TestExecutionSummary$Failure");
        Class<?> tidClass = Class.forName("org.junit.platform.launcher.TestIdentifier");
        System.out.println("FAILURES in " + className + ": " + failures.size());
        for (Object f : failures) {
            Object tid = failIface.getMethod("getTestIdentifier").invoke(f);
            Object ex = failIface.getMethod("getException").invoke(f);
            String name = (String) tidClass.getMethod("getDisplayName").invoke(tid);
            System.out.println("  * " + name);
            if (ex instanceof Throwable t) {
                System.out.println("      " + t);
                for (StackTraceElement e : t.getStackTrace())
                    if (e.getClassName().contains("javaparser") && !e.getClassName().contains("csto")) {
                        System.out.println("      at " + e); break;
                    }
            }
        }
    }

    // Method-level test markers across JUnit 4, JUnit 5 (Jupiter), and TestNG.
    private static final java.util.Set<String> TEST_ANNOS = java.util.Set.of(
            "org.junit.Test",                              // JUnit 4
            "org.junit.jupiter.api.Test",                  // JUnit 5
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.testng.annotations.Test");                // TestNG (also valid at class level)

    private static boolean isTestAnno(String name) {
        if (TEST_ANNOS.contains(name)) return true;
        // Meta-annotated / custom @TestTemplate-based markers and RunWith/Testable.
        return name.endsWith("RunWith") || name.endsWith(".Testable")
                || name.endsWith(".ParameterizedTest") || name.endsWith(".RepeatedTest");
    }

    private static boolean hasTests(Class<?> c) {
        try {
            for (java.lang.annotation.Annotation an : c.getAnnotations()) {
                String n = an.annotationType().getName();
                if (isTestAnno(n) || n.equals("org.testng.annotations.Test")) return true; // TestNG class-level
            }
            for (Method m : c.getMethods())            // includes inherited test methods from abstract bases
                for (java.lang.annotation.Annotation an : m.getAnnotations())
                    if (isTestAnno(an.annotationType().getName())) return true;
            for (Method m : c.getDeclaredMethods())
                for (java.lang.annotation.Annotation an : m.getAnnotations())
                    if (isTestAnno(an.annotationType().getName())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Runs one test class in a worker with a wall-clock timeout. Returns the Result, or null
     * if it didn't finish in time (deadlock/hang). A hung worker is left parked (idle) — it can't
     * block subsequent classes, and the final Runtime.halt(0) reaps it (so a non-daemon worker is
     * safe and cannot wedge JVM exit). This is what keeps a single deadlocked test (e.g. a
     * concurrency stress test) from hanging the whole run.
     *
     * <p>The worker is NON-daemon to match Surefire fidelity: Maven runs tests on a non-daemon
     * thread, and threads a test spawns INHERIT their parent's daemon flag. A daemon worker would
     * make spawned threads daemon too, breaking tests that assert on it (e.g. commons-lang
     * BasicThreadFactoryTest.testNewThreadNoDaemonFlag). halt(0) at run end makes this safe.
     */
    private static Result runWithTimeout(Runner launcher, String test, long timeoutMs, AllocMeter alloc) {
        final Result[] box = new Result[1];
        Thread worker = new Thread(() -> {
            // Measure allocation on THIS (the test-execution) thread, captured before it exits.
            // Sampling from the main loop after join() would miss it: by then the worker is dead and
            // getThreadAllocatedBytes excludes dead thread ids, collapsing the delta to ~0.
            long a0 = alloc.currentThreadBytes();
            try { box[0] = launcher.run(test); } catch (Throwable t) { box[0] = Result.error(); }
            if (box[0] != null) box[0].allocBytes = Math.max(0, alloc.currentThreadBytes() - a0);
        }, "csto-test-" + test);
        worker.setDaemon(false);
        worker.start();
        try { worker.join(timeoutMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return worker.isAlive() ? null : box[0];
    }

    /** Sums allocated bytes across live threads (HotSpot com.sun ThreadMXBean); 0 if unsupported. */
    static final class AllocMeter {
        private final com.sun.management.ThreadMXBean tb;
        private AllocMeter(com.sun.management.ThreadMXBean tb) { this.tb = tb; }
        static AllocMeter create() {
            try {
                java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
                if (base instanceof com.sun.management.ThreadMXBean csb && csb.isThreadAllocatedMemorySupported()) {
                    csb.setThreadAllocatedMemoryEnabled(true);
                    return new AllocMeter(csb);
                }
            } catch (Throwable ignored) {}
            return new AllocMeter(null);
        }
        long totalBytes() {
            if (tb == null) return 0;
            try {
                long[] ids = ManagementFactory.getThreadMXBean().getAllThreadIds();
                long[] bytes = tb.getThreadAllocatedBytes(ids);
                long sum = 0;
                for (long b : bytes) if (b > 0) sum += b;
                return sum;
            } catch (Throwable t) { return 0; }
        }
        /** Cumulative bytes allocated by the calling thread (must be sampled while it is alive). */
        long currentThreadBytes() {
            if (tb == null) return 0;
            try { long b = tb.getThreadAllocatedBytes(Thread.currentThread().getId()); return b > 0 ? b : 0; }
            catch (Throwable t) { return 0; }
        }
    }

    private static long[] gcSnapshot(List<GarbageCollectorMXBean> gcs) {
        long count = 0, ms = 0;
        for (GarbageCollectorMXBean g : gcs) {
            long c = g.getCollectionCount(); if (c > 0) count += c;
            long t = g.getCollectionTime(); if (t > 0) ms += t;
        }
        return new long[]{count, ms};
    }

    /** Shared per-class execution result. */
    static final class Result {
        long found, failures; boolean errored; String firstError; long allocBytes;
        static Result error() { Result r = new Result(); r.errored = true; return r; }
    }

    interface Runner { Result run(String className) throws Exception; }

    /** Prefer JUnit Platform; fall back to JUnit 4 (e.g. openpojo) when the platform isn't present. */
    static Runner selectRunner() {
        try { return new JUnitPlatformRunner(); } catch (Throwable ignored) {}
        try { return new JUnit4Runner(); } catch (Throwable ignored) {}
        throw new IllegalStateException("No JUnit Platform launcher or JUnit 4 on classpath");
    }

    /** Reflective JUnit 4 runner (org.junit.runner.JUnitCore). */
    static final class JUnit4Runner implements Runner {
        private final Method runClasses;
        JUnit4Runner() throws Exception {
            Class<?> core = Class.forName("org.junit.runner.JUnitCore");
            runClasses = core.getMethod("runClasses", Class[].class);
        }
        public Result run(String className) throws Exception {
            Class<?> testClass = Class.forName(className);
            Object res = runClasses.invoke(null, (Object) new Class[]{testClass});
            Result r = new Result();
            r.found = ((Number) res.getClass().getMethod("getRunCount").invoke(res)).longValue();
            r.failures = ((Number) res.getClass().getMethod("getFailureCount").invoke(res)).longValue();
            if (r.failures > 0) {
                java.util.List<?> fs = (java.util.List<?>) res.getClass().getMethod("getFailures").invoke(res);
                if (!fs.isEmpty()) {
                    Object f0 = fs.get(0);
                    Object thr = f0.getClass().getMethod("getException").invoke(f0);
                    r.firstError = String.valueOf(thr);
                }
            }
            return r;
        }
    }

    /** Minimal reflective JUnit Platform launcher (no compile-time dependency on JUnit). */
    static final class JUnitPlatformRunner implements Runner {
        private final Object launcher;
        private final Class<?> launcherIface, listenerIface, requestClass, selectorIface;
        private final Class<?> summaryListenerClass, summaryIface, discoverySelectors, requestBuilderClass;
        private final Method selectClass, request, selectors, build, execute;

        JUnitPlatformRunner() throws Exception {
            Class<?> factory = Class.forName("org.junit.platform.launcher.core.LauncherFactory");
            requestBuilderClass = Class.forName("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
            requestClass = Class.forName("org.junit.platform.launcher.LauncherDiscoveryRequest");
            listenerIface = Class.forName("org.junit.platform.launcher.TestExecutionListener");
            launcherIface = Class.forName("org.junit.platform.launcher.Launcher");
            selectorIface = Class.forName("org.junit.platform.engine.DiscoverySelector");
            discoverySelectors = Class.forName("org.junit.platform.engine.discovery.DiscoverySelectors");
            summaryListenerClass = Class.forName("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
            summaryIface = Class.forName("org.junit.platform.launcher.listeners.TestExecutionSummary");
            launcher = factory.getMethod("create").invoke(null);
            selectClass = discoverySelectors.getMethod("selectClass", Class.class);
            request = requestBuilderClass.getMethod("request");
            build = requestBuilderClass.getMethod("build");
            selectors = requestBuilderClass.getMethod("selectors", Array.newInstance(selectorIface, 0).getClass());
            execute = launcherIface.getMethod("execute", requestClass, Array.newInstance(listenerIface, 0).getClass());
        }

        public Result run(String className) throws Exception {
            Class<?> testClass = Class.forName(className);
            Object selector = selectClass.invoke(null, testClass);
            Object arr = Array.newInstance(selectorIface, 1); Array.set(arr, 0, selector);
            Object builder = selectors.invoke(request.invoke(null), arr);
            Object req = build.invoke(builder);
            Object listener = summaryListenerClass.getConstructor().newInstance();
            Object listeners = Array.newInstance(listenerIface, 1); Array.set(listeners, 0, listener);
            execute.invoke(launcher, req, listeners);
            Object summary = summaryListenerClass.getMethod("getSummary").invoke(listener);
            Result res = new Result();
            res.found = ((Number) summaryIface.getMethod("getTestsFoundCount").invoke(summary)).longValue();
            res.failures = ((Number) summaryIface.getMethod("getTotalFailureCount").invoke(summary)).longValue();
            return res;
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]); else m.put(k, "true");
            }
        }
        return m;
    }
    private static String req(Map<String, String> a, String k) {
        String v = a.get(k); if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + k); return v;
    }
}
