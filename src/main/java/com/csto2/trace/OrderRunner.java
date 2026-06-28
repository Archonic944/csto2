package com.csto2.trace;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs a given order of test classes and appends one trace row per class to a JSON-lines file.
 * Two backends implement this: {@link TraceOrchestrator} (the in-JVM reflective runner) and
 * {@link com.csto2.surefire.SurefireOrchestrator} (real Maven Surefire via the testorder fork).
 * Both emit the same row schema so the optimizer/selector are backend-agnostic.
 */
public interface OrderRunner {

    /** Run one explicit order once; append rows tagged with {@code orderId} to {@code out}. Returns exit code. */
    int runOrder(Path orderFile, String orderId, Path out) throws Exception;

    /** Trace {@code orders} orders (order 0 = initial/as-given, rest = seeded shuffles) into a fresh trace.jsonl. */
    Path run(List<String> tests, int orders, long seed) throws Exception;
}
