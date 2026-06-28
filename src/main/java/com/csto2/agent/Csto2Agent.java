package com.csto2.agent;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Java agent injected into the Surefire fork (via {@code KP_ARGLINE=-javaagent:csto2-agent.jar=...}).
 * It (1) appends its own jar to the system class loader so the JUnit Platform ServiceLoader discovers
 * {@link Csto2Listener}, and (2) hands the listener its output config + starts JFR. The listener then
 * records per-class MXBean/JFR facts inside the real Surefire run — recovering everything the legacy
 * in-JVM TraceRunner measured, but in the faithful Surefire environment.
 *
 * <p>Agent args: {@code out=<facts.jsonl>,order=<orderId>,jfr=<jfrDir>} (comma-separated).
 */
public final class Csto2Agent {

    public static void premain(String args, Instrumentation inst) {
        Map<String, String> opts = parse(args);
        try {
            String self = Csto2Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (self != null && self.endsWith(".jar")) inst.appendToSystemClassLoaderSearch(new JarFile(self));
        } catch (Throwable t) {
            System.err.println("[csto2-agent] could not append agent jar to system classpath: " + t);
        }
        Csto2Listener.configure(opts.get("out"), opts.get("order"), opts.get("jfr"));
        System.err.println("[csto2-agent] active (order=" + opts.get("order") + ")");
    }

    private static Map<String, String> parse(String args) {
        Map<String, String> m = new HashMap<>();
        if (args == null || args.isBlank()) return m;
        for (String kv : args.split(",")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
        return m;
    }
}
