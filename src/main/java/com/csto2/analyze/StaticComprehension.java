package com.csto2.analyze;

import com.csto2.util.Json;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Blended-comprehension STATIC half (S0 + app-scoped S1).
 *
 * <p>For each test class we walk the app-scope call graph starting at the test's declared methods,
 * following call edges but STOPPING at library/JDK boundaries (per the library-treatment policy:
 * libraries are summarized, not traversed). Along the way we record the facts the simulator's
 * scoring terms need:
 * <ul>
 *   <li>static field reads/writes in application code (state-pollution candidates)</li>
 *   <li>library static fields touched (shared-resource / shared-init candidates)</li>
 *   <li>resource-shaped string constants (bundles, files, scripts)</li>
 *   <li>application types referenced (shared-code locality / warmup overlap)</li>
 *   <li>a &lt;clinit&gt; cost proxy</li>
 * </ul>
 * Dynamic tracing later confirms/quantifies these; expensive WALA (S2 slicing) is reserved for
 * edges the dynamic side confirms.
 */
public final class StaticComprehension {

    /** Resource-intensity guard: cap app methods visited per test class. */
    private static final int MAX_METHODS_PER_TEST = 8000;

    private static final Pattern RESOURCE_LIKE = Pattern.compile(
            ".*(\\.(properties|xml|dtd|txt|json|csv|js)|ResourceBundle|/).*",
            Pattern.CASE_INSENSITIVE);

    private final IClassHierarchy cha;
    private final IAnalysisCacheView cache = new AnalysisCacheImpl();
    private final Map<IMethod, MethodFacts> methodCache = new java.util.HashMap<>();

    private StaticComprehension(IClassHierarchy cha) {
        this.cha = cha;
    }

    public static StaticComprehension build(String appClasspath, String libClasspath) throws Exception {
        Path exclusions = writeExclusions();
        AnalysisScope scope = AnalysisScopeReader.instance.makePrimordialScope(exclusions.toFile());
        addEntries(appClasspath, scope, ClassLoaderReference.Application);
        addEntries(libClasspath, scope, ClassLoaderReference.Extension);
        IClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        return new StaticComprehension(cha);
    }

    /** Add classpath entries one at a time so a single missing/stale entry can't abort the scope. */
    private static void addEntries(String classpath, AnalysisScope scope, ClassLoaderReference loader) {
        if (classpath == null || classpath.isBlank()) return;
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            if (!Files.exists(Paths.get(entry))) {
                System.err.println("[csto2] skip missing classpath entry: " + entry);
                continue;
            }
            try {
                AnalysisScopeReader.instance.addClassPathToScope(entry, scope, loader);
            } catch (Throwable t) {
                System.err.println("[csto2] skip unreadable classpath entry " + entry + " (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /** Per-test-class fused static facts. */
    public static final class TestFacts {
        public final String test;
        public final Set<String> staticReads = new TreeSet<>();
        public final Set<String> staticWrites = new TreeSet<>();
        public final Set<String> libResources = new TreeSet<>();
        public final Set<String> resourceConstants = new TreeSet<>();
        public final Set<String> appTypes = new TreeSet<>();
        public int clinitProxy;
        public int methodsVisited;
        TestFacts(String test) { this.test = test; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("test", test);
            m.put("clinitProxy", clinitProxy);
            m.put("methodsVisited", methodsVisited);
            m.put("staticWrites", new ArrayList<>(staticWrites));
            m.put("staticReads", new ArrayList<>(staticReads));
            m.put("libResources", new ArrayList<>(libResources));
            m.put("resourceConstants", new ArrayList<>(resourceConstants));
            m.put("appTypes", new ArrayList<>(appTypes));
            return m;
        }
    }

    public TestFacts analyzeTest(String testClassName) {
        TestFacts facts = new TestFacts(testClassName);
        TypeReference tref = TypeReference.findOrCreate(
                ClassLoaderReference.Application, "L" + testClassName.replace('.', '/'));
        IClass testClass = cha.lookupClass(tref);
        if (testClass == null) return facts; // not found in app scope

        facts.clinitProxy = clinitProxy(testClass);

        Deque<IMethod> work = new ArrayDeque<>();
        Set<IMethod> seen = new HashSet<>();
        for (IMethod m : testClass.getDeclaredMethods()) {
            if (seen.add(m)) work.add(m);
        }
        while (!work.isEmpty() && facts.methodsVisited < MAX_METHODS_PER_TEST) {
            IMethod m = work.poll();
            if (!isApplication(m)) continue; // library boundary: summarize, don't traverse
            facts.methodsVisited++;
            MethodFacts mf = factsOf(m);
            facts.staticReads.addAll(mf.staticReads);
            facts.staticWrites.addAll(mf.staticWrites);
            facts.libResources.addAll(mf.libResources);
            facts.resourceConstants.addAll(mf.resourceConstants);
            facts.appTypes.addAll(mf.appTypes);
            for (MethodReference callee : mf.appCallees) {
                IMethod target = cha.resolveMethod(callee);
                if (target != null && isApplication(target) && seen.add(target)) work.add(target);
            }
        }
        return facts;
    }

    /** Per-method extracted facts, cached (methods are shared across tests). */
    private static final class MethodFacts {
        final Set<String> staticReads = new HashSet<>();
        final Set<String> staticWrites = new HashSet<>();
        final Set<String> libResources = new HashSet<>();
        final Set<String> resourceConstants = new HashSet<>();
        final Set<String> appTypes = new HashSet<>();
        final List<MethodReference> appCallees = new ArrayList<>();
    }

    private MethodFacts factsOf(IMethod m) {
        MethodFacts cached = methodCache.get(m);
        if (cached != null) return cached;
        MethodFacts mf = new MethodFacts();
        IR ir = safeIR(m);
        if (ir != null) {
            SymbolTable st = ir.getSymbolTable();
            for (SSAInstruction ins : ir.getInstructions()) {
                if (ins == null) continue;
                scanInstruction(ins, st, mf);
            }
        }
        methodCache.put(m, mf);
        return mf;
    }

    private void scanInstruction(SSAInstruction ins, SymbolTable st, MethodFacts mf) {
        if (ins instanceof SSAFieldAccessInstruction && ((SSAFieldAccessInstruction) ins).isStatic()) {
            FieldReference f = ((SSAFieldAccessInstruction) ins).getDeclaredField();
            Loader owner = loaderOf(f.getDeclaringClass());
            if (owner == Loader.PRIMORDIAL) return; // JDK statics: not interesting state
            String key = fieldKey(f);
            boolean app = owner == Loader.APPLICATION;
            if (ins instanceof SSAPutInstruction) {
                if (app) mf.staticWrites.add(key); else mf.libResources.add("write:" + key);
            } else if (ins instanceof SSAGetInstruction) {
                if (app) mf.staticReads.add(key); else mf.libResources.add("read:" + key);
            }
        } else if (ins instanceof SSAInvokeInstruction) {
            MethodReference target = ((SSAInvokeInstruction) ins).getDeclaredTarget();
            Loader owner = loaderOf(target.getDeclaringClass());
            if (owner == Loader.APPLICATION) {
                mf.appCallees.add(target);
                mf.appTypes.add(typeKey(target.getDeclaringClass()));
            } else if (owner == Loader.EXTENSION) {
                // Library entrypoint touched: record which subsystem, do not descend.
                mf.libResources.add("call:" + typeKey(target.getDeclaringClass()));
            }
        } else if (ins instanceof SSANewInstruction) {
            TypeReference t = ((SSANewInstruction) ins).getConcreteType();
            Loader owner = loaderOf(t);
            if (owner == Loader.APPLICATION) mf.appTypes.add(typeKey(t));
            else if (owner == Loader.EXTENSION) mf.libResources.add("new:" + typeKey(t));
        }
        // String constants used by this instruction (resource hints).
        for (int i = 0; i < ins.getNumberOfUses(); i++) {
            int v = ins.getUse(i);
            if (v >= 0 && st.isStringConstant(v)) {
                Object cv = st.getConstantValue(v);
                if (cv != null) {
                    String s = cv.toString();
                    if (!s.isEmpty() && s.length() <= 200 && RESOURCE_LIKE.matcher(s).matches()) {
                        mf.resourceConstants.add(s);
                    }
                }
            }
        }
    }

    private IR safeIR(IMethod m) {
        if (m.isAbstract() || m.isNative()) return null;
        try { return cache.getIR(m); } catch (Throwable t) { return null; }
    }

    private int clinitProxy(IClass c) {
        int proxy = c.getDeclaredStaticFields() == null ? 0 : c.getDeclaredStaticFields().size();
        for (IMethod m : c.getDeclaredMethods()) {
            if (m.isClinit()) {
                IR ir = safeIR(m);
                if (ir != null) proxy += ir.getInstructions().length;
            }
        }
        return proxy;
    }

    private enum Loader { APPLICATION, EXTENSION, PRIMORDIAL, UNKNOWN }

    private static boolean isApplication(IMethod m) { return isApplication(m.getDeclaringClass()); }
    private static boolean isApplication(IClass c) {
        return c.getClassLoader().getReference().equals(ClassLoaderReference.Application);
    }

    /**
     * Resolve a type reference to its defining class and return that class's REAL loader.
     * A TypeReference in app bytecode carries the Application loader by reference, so we must
     * look the class up in the hierarchy to learn where it is actually defined.
     */
    private Loader loaderOf(TypeReference t) {
        IClass c = cha.lookupClass(t);
        if (c == null) return Loader.UNKNOWN;
        ClassLoaderReference ref = c.getClassLoader().getReference();
        if (ref.equals(ClassLoaderReference.Application)) return Loader.APPLICATION;
        if (ref.equals(ClassLoaderReference.Extension)) return Loader.EXTENSION;
        if (ref.equals(ClassLoaderReference.Primordial)) return Loader.PRIMORDIAL;
        return Loader.UNKNOWN;
    }

    private static String fieldKey(FieldReference f) {
        return typeKey(f.getDeclaringClass()) + "#" + f.getName().toString();
    }
    private static String typeKey(TypeReference t) {
        String s = t.getName().toString();
        if (s.startsWith("L")) s = s.substring(1);
        return s.replace('/', '.');
    }

    private static Path writeExclusions() throws IOException {
        Path p = Files.createTempFile("csto2-wala-excl", ".txt");
        String body = String.join("\n",
                "java\\/awt\\/.*",
                "javax\\/swing\\/.*",
                "sun\\/awt\\/.*",
                "sun\\/swing\\/.*",
                "com\\/sun\\/.*",
                "sun\\/.*",
                "org\\/netbeans\\/.*",
                "org\\/openide\\/.*",
                "") + "\n";
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        p.toFile().deleteOnExit();
        return p;
    }

    /** Write per-test facts as JSON-lines and return them. */
    public List<TestFacts> analyzeAll(List<String> tests, Path out) throws IOException {
        List<TestFacts> all = new ArrayList<>();
        Files.createDirectories(out.getParent());
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String t : tests) {
                TestFacts f = analyzeTest(t);
                all.add(f);
                w.write(Json.write(f.toMap()));
                w.write("\n");
            }
        }
        return all;
    }
}
