package com.csto2.trace;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-based test-class discovery, run in the target JVM (which has JUnit on its classpath).
 * This is the surviving piece of the former in-JVM TraceRunner: it never EXECUTES tests (so it has
 * none of the fidelity problems that motivated the move to Surefire) — it only filters a candidate
 * class list down to the classes Surefire would actually run, and offers an {@code --explain} aid for
 * inspecting a class's failures. All measurement now goes through
 * {@link com.csto2.surefire.SurefireOrchestrator}.
 */
public final class TestDiscovery {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        if (a.containsKey("discover")) { discover(a); return; }
        if (a.containsKey("explain")) { explain(a.get("explain")); return; }
        throw new IllegalArgumentException("TestDiscovery: pass --discover --tests <file> --out <file>, or --explain <class>");
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
            try { c = Class.forName(name, false, TestDiscovery.class.getClassLoader()); }
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
                    if (!e.getClassName().contains("csto") && !e.getClassName().startsWith("org.junit")
                            && !e.getClassName().startsWith("jdk.")) {
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
        String v = a.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + k);
        return v;
    }
}
