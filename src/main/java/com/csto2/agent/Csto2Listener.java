package com.csto2.agent;

import com.csto2.trace.JfrProbe;
import com.csto2.util.Json;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit Platform listener (ServiceLoader-registered by {@link Csto2Agent}) that records, per top-level
 * test class, the same durable-state deltas the legacy TraceRunner captured: classes loaded, JIT
 * compile time, GC count/time, allocated bytes, thread delta — plus per-class JFR facts via
 * {@link JfrProbe}. Output is merged back into the Surefire trace by {@code SurefireOrchestrator}.
 *
 * <p>Depth counting collapses {@code @Nested} classes into their enclosing top-level class window, so
 * each top-level class yields exactly one row. Assumes sequential execution (required anyway, since
 * cross-class carryover is what we measure).
 */
public final class Csto2Listener implements TestExecutionListener {

    private static volatile Path outFile;
    private static volatile Path jfrDir;
    private static volatile String orderId = "order";
    private static volatile JfrProbe jfr;

    /** Called from the agent premain before the launcher builds; starts JFR. */
    static void configure(String out, String order, String jfrDirArg) {
        outFile = out == null ? null : Paths.get(out);
        jfrDir = jfrDirArg == null ? null : Paths.get(jfrDirArg);
        if (order != null) orderId = order;
        try { jfr = JfrProbe.start(); } catch (Throwable t) { jfr = null; }
    }

    private final ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
    private final CompilationMXBean comp = ManagementFactory.getCompilationMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<Map<String, Object>> rows = new ArrayList<>();

    private int depth = 0, pos = 0, curPos = 0;
    private String curClass;
    private long loaded0, jit0, thr0, gcCount0, gcMs0, alloc0, t0;
    private JfrProbe.Window win;

    @Override
    public void executionStarted(TestIdentifier id) {
        if (!isClass(id)) return;
        if (depth++ == 0) {
            curClass = className(id);
            curPos = pos++;
            win = jfr != null ? jfr.begin(curClass, curPos) : null;
            loaded0 = cl.getTotalLoadedClassCount();
            jit0 = comp == null ? 0 : comp.getTotalCompilationTime();
            long[] g = gcSnapshot();
            gcCount0 = g[0];
            gcMs0 = g[1];
            thr0 = threads.getThreadCount();
            alloc0 = allocBytes();
            t0 = System.nanoTime();
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!isClass(id)) return;
        if (--depth == 0 && curClass != null) {
            if (win != null && jfr != null) jfr.end(win);
            long[] g = gcSnapshot();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("test", curClass);
            row.put("position", curPos);
            row.put("agentRuntimeMs", (System.nanoTime() - t0) / 1e6);
            row.put("classesLoaded", cl.getTotalLoadedClassCount() - loaded0);
            row.put("jitMs", (comp == null ? 0 : comp.getTotalCompilationTime()) - jit0);
            row.put("gcCount", g[0] - gcCount0);
            row.put("gcMs", g[1] - gcMs0);
            row.put("allocBytes", Math.max(0, allocBytes() - alloc0));
            row.put("threadDelta", threads.getThreadCount() - thr0);
            rows.add(row);
            curClass = null;
            win = null;
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            if (outFile != null) {
                if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> r : rows) sb.append(Json.write(r)).append('\n');
                Files.write(outFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (jfr != null && jfrDir != null) {
                String safe = orderId.replace('#', '_').replace('/', '_');
                jfr.finishAndWrite(jfrDir.resolve(safe + ".jfr"), jfrDir.resolve(safe + ".jfr.jsonl"), orderId);
            }
        } catch (Throwable t) {
            System.err.println("[csto2-agent] failed to write facts: " + t);
        }
    }

    private static boolean isClass(TestIdentifier id) {
        return id.getSource().map(s -> s instanceof ClassSource).orElse(false);
    }

    private static String className(TestIdentifier id) {
        TestSource s = id.getSource().orElse(null);
        return s instanceof ClassSource cs ? cs.getClassName() : id.getLegacyReportingName();
    }

    private long[] gcSnapshot() {
        long count = 0, ms = 0;
        for (GarbageCollectorMXBean g : gcs) {
            long c = g.getCollectionCount(); if (c > 0) count += c;
            long t = g.getCollectionTime(); if (t > 0) ms += t;
        }
        return new long[]{count, ms};
    }

    /** Sum of allocated bytes across live threads (HotSpot); 0 if unsupported. */
    private long allocBytes() {
        try {
            ThreadMXBean base = ManagementFactory.getThreadMXBean();
            if (base instanceof com.sun.management.ThreadMXBean cb && cb.isThreadAllocatedMemorySupported()) {
                long[] ids = base.getAllThreadIds();
                long[] bytes = cb.getThreadAllocatedBytes(ids);
                long sum = 0;
                for (long b : bytes) if (b > 0) sum += b;
                return sum;
            }
        } catch (Throwable ignore) {}
        return 0;
    }
}
