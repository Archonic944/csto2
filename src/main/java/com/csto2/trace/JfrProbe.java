package com.csto2.trace;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-order JFR (Java Flight Recorder) probe. Counter deltas around a whole test class aggregate and
 * confound the mechanisms we care about (GC vs JIT vs class-load) and are blind to JVM internal state
 * (collection type, heap pressure, which classes). JFR gives a timestamped EVENT STREAM instead; we
 * bin each event into the test that was running when it fired, yielding a per-test, per-mechanism
 * causal record:
 *
 * <ul-free>
 *   GC: young vs OLD/FULL collection counts + pause time + cause — full GCs are the expensive,
 *   placement-sensitive ones; young collections under a big heap are nearly free.
 *   Class loading: count AND identity, so we can isolate classes a test UNIQUELY first-loads (a
 *   one-time cost) from shared ones that would load anyway.
 *   Compilation: count, a proxy for how cold the test ran.
 *   Allocation (sampled): estimated bytes by object class, so churn is attributable to a call path
 *   and its cacheability can be reasoned about.
 * </ul-free>
 *
 * <p>Everything is best-effort: any JFR failure disables the probe without affecting the trace.
 */
public final class JfrProbe {

    private final Recording recording;
    private final List<Window> windows = new ArrayList<>();

    /** A test's wall-clock execution window; JFR event timestamps are binned into these. */
    public static final class Window {
        final String test; final int pos; final Instant start; Instant end;
        Window(String test, int pos, Instant start) { this.test = test; this.pos = pos; this.start = start; }
    }

    private JfrProbe(Recording recording) { this.recording = recording; }

    /** Start recording the mechanisms; returns null (probe disabled) if JFR is unavailable. */
    public static JfrProbe start() {
        try {
            Recording r = new Recording();
            r.enable("jdk.GarbageCollection").withoutThreshold();
            r.enable("jdk.ClassLoad").withoutThreshold();
            r.enable("jdk.Compilation").withoutThreshold();
            // Rate-limited allocation sampling (JDK 16+); cheap, gives bytes-by-class.
            try { r.enable("jdk.ObjectAllocationSample").with("throttle", "1024/s"); }
            catch (Throwable ignore) { r.enable("jdk.ObjectAllocationOutsideTLAB").withoutThreshold(); }
            r.start();
            return new JfrProbe(r);
        } catch (Throwable t) {
            System.err.println("[jfr] disabled: " + t);
            return null;
        }
    }

    public Window begin(String test, int pos) { Window w = new Window(test, pos, Instant.now()); windows.add(w); return w; }
    public void end(Window w) { if (w != null) w.end = Instant.now(); }

    /** Stop, dump, and attribute events to per-test windows; write one facts row per test. */
    public void finishAndWrite(Path jfrFile, Path factsOut, String orderId) {
        try {
            recording.stop();
            if (jfrFile.getParent() != null) Files.createDirectories(jfrFile.getParent());
            recording.dump(jfrFile);
            recording.close();
            attribute(jfrFile, factsOut, orderId);
        } catch (Throwable t) {
            System.err.println("[jfr] attribution failed: " + t);
        }
    }

    private void attribute(Path jfrFile, Path factsOut, String orderId) throws Exception {
        // windows are appended in execution order => already sorted by start time.
        List<Acc> accs = new ArrayList<>();
        for (Window w : windows) accs.add(new Acc(w));
        Set<String> seenClasses = new HashSet<>(); // for unique-first-load detection, in execution order

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                int idx = windowIndexFor(e.getStartTime());
                if (idx < 0) continue;
                Acc acc = accs.get(idx);
                String type = e.getEventType().getName();
                switch (type) {
                    case "jdk.GarbageCollection" -> {
                        String name = safeStr(e, "name"), cause = safeStr(e, "cause");
                        boolean full = (name != null && (name.contains("Old") || name.contains("Full")))
                                || (cause != null && cause.contains("Full"));
                        if (full) acc.gcOld++; else acc.gcYoung++;
                        if (e.hasField("sumOfPauses")) {
                            try { acc.gcPauseNanos += e.getDuration("sumOfPauses").toNanos(); }
                            catch (Throwable ignore) {}
                        }
                    }
                    case "jdk.ClassLoad" -> {
                        acc.classLoads++;
                        String cn = loadedClassName(e);
                        if (cn != null && seenClasses.add(cn)) acc.uniqueClassLoads++;
                    }
                    case "jdk.Compilation" -> acc.compilations++;
                    case "jdk.ObjectAllocationSample", "jdk.ObjectAllocationOutsideTLAB" -> {
                        String cn = objectClassName(e);
                        long w = e.hasField("weight") ? e.getLong("weight")
                                : (e.hasField("allocationSize") ? e.getLong("allocationSize") : 0);
                        if (cn != null && w > 0) acc.allocByClass.merge(cn, w, Long::sum);
                    }
                    default -> {}
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Acc acc : accs) sb.append(acc.toJson(orderId)).append('\n');
        if (factsOut.getParent() != null) Files.createDirectories(factsOut.getParent());
        Files.write(factsOut, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.err.println("[jfr] wrote per-test facts -> " + factsOut);
    }

    /** Binary search for the window containing instant t; -1 if before/after all windows. */
    private int windowIndexFor(Instant t) {
        int lo = 0, hi = windows.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Window w = windows.get(mid);
            if (t.isBefore(w.start)) hi = mid - 1;
            else { ans = mid; lo = mid + 1; } // last window whose start <= t
        }
        if (ans < 0) return -1;
        Window w = windows.get(ans);
        if (w.end != null && t.isAfter(w.end)) return -1; // in the gap after this test, before next
        return ans;
    }

    private static String safeStr(RecordedEvent e, String f) {
        try { return e.hasField(f) ? String.valueOf(e.getValue(f)) : null; } catch (Throwable t) { return null; }
    }
    private static String loadedClassName(RecordedEvent e) {
        try { if (e.hasField("loadedClass")) { RecordedClass c = e.getClass("loadedClass"); return c == null ? null : c.getName(); } }
        catch (Throwable ignore) {}
        return null;
    }
    private static String objectClassName(RecordedEvent e) {
        try { if (e.hasField("objectClass")) { RecordedClass c = e.getClass("objectClass"); return c == null ? null : c.getName(); } }
        catch (Throwable ignore) {}
        return null;
    }

    /** Per-test accumulator. */
    private static final class Acc {
        final Window w;
        int gcYoung, gcOld, classLoads, uniqueClassLoads, compilations;
        long gcPauseNanos;
        final Map<String, Long> allocByClass = new LinkedHashMap<>();
        Acc(Window w) { this.w = w; }

        String toJson(String orderId) {
            List<Map.Entry<String, Long>> top = new ArrayList<>(allocByClass.entrySet());
            top.sort(Comparator.comparingLong((Map.Entry<String, Long> en) -> -en.getValue()));
            StringBuilder alloc = new StringBuilder("[");
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                if (i > 0) alloc.append(',');
                alloc.append("[\"").append(esc(top.get(i).getKey())).append("\",").append(top.get(i).getValue()).append(']');
            }
            alloc.append(']');
            return "{\"orderId\":\"" + esc(orderId) + "\",\"position\":" + w.pos
                    + ",\"test\":\"" + esc(w.test) + "\""
                    + ",\"gcYoung\":" + gcYoung + ",\"gcOld\":" + gcOld
                    + ",\"gcPauseMs\":" + (gcPauseNanos / 1e6)
                    + ",\"classLoads\":" + classLoads + ",\"uniqueClassLoads\":" + uniqueClassLoads
                    + ",\"compilations\":" + compilations
                    + ",\"allocTop\":" + alloc + "}";
        }
        private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    }
}
