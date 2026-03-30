package org.example.meta;

import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/**
 * Bài 7.3 — Bytecode: javap, ASM concepts, Instrumentation
 *
 * Mục tiêu học:
 * 1. Đọc bytecode với javap — hiểu JVM instruction set
 * 2. Bytecode structure: constant pool, method descriptors, opcodes
 * 3. ASM library pattern (ClassReader → ClassVisitor → ClassWriter)
 * 4. Java Instrumentation API (javaagent, ClassFileTransformer)
 * 5. Framework bytecode tricks: lazy loading, AOP proxy, serialization
 * 6. invokevirtual vs invokeinterface vs invokestatic vs invokedynamic
 * 7. Stack frame: local variables array + operand stack
 * 8. LambdaMetafactory + invokedynamic (how lambdas actually work)
 * 9. Bytecode manipulation patterns used by Spring, Hibernate, Jackson
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.meta.BytecodeDemo"
 *
 * SA Insight: Hiểu bytecode giúp bạn debug framework magic, tối ưu code,
 * viết custom agents, và đưa ra quyết định kiến trúc dựa trên cơ chế thực tế.
 */
public class BytecodeDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Bài 7.3: Bytecode, ASM & Instrumentation ===\n");

        demo1_BytecodeBasics();
        demo2_JvmInstructions();
        demo3_ConstantPoolAndDescriptors();
        demo4_InvokeInstructions();
        demo5_InvokeDynamicAndLambdas();
        demo6_AsmLibraryPattern();
        demo7_InstrumentationApi();
        demo8_FrameworkBytecodePatterns();
        demo9_BytecodeManipulationInPractice();

        System.out.println("\n=== Hoàn thành bài 7.3 ===");
    }

    // =========================================================================
    // DEMO 1: Bytecode Basics — What javap shows
    // =========================================================================
    static void demo1_BytecodeBasics() {
        System.out.println("--- Demo 1: Bytecode Basics (javap output) ---");

        System.out.println("""
            Để xem bytecode của class này:
              javap -c -verbose org.example.meta.BytecodeDemo

            javap flags:
              -c          : disassemble bytecode (opcodes)
              -verbose    : constant pool, stack size, local var slots
              -p          : include private members
              -l          : line numbers và local variable table
              -s          : method descriptors

            Một method đơn giản sau khi compile:

              // Java source:
              public int add(int a, int b) { return a + b; }

              // Bytecode (javap -c):
              public int add(int, int);
                Code:
                  0: iload_1      // push local[1] (a) onto operand stack
                  1: iload_2      // push local[2] (b) onto operand stack
                  2: iadd         // pop 2 ints, push sum
                  3: ireturn      // pop int, return to caller

            Execution model:
            - Local variable array: [this, a, b, ...]  (slots 0,1,2,...)
            - Operand stack: push/pop values during computation
            - JVM = stack machine (không phải register machine như CPU)
            """);

        // Demonstrate via reflection what the JVM "sees"
        try {
            Method addMethod = SimpleCalculator.class.getDeclaredMethod("add", int.class, int.class);
            System.out.println("Method descriptor: " + addMethod.toString());
            System.out.println("Return type: " + addMethod.getReturnType().getName());
            System.out.println("Parameter count: " + addMethod.getParameterCount());
            System.out.println("Modifiers: " + Modifier.toString(addMethod.getModifiers()));
        } catch (NoSuchMethodException e) {
            System.out.println("(reflection error: " + e.getMessage() + ")");
        }

        System.out.println();
    }

    // Helper class to inspect
    static class SimpleCalculator {
        public int add(int a, int b) { return a + b; }
        public static double sqrt(double x) { return Math.sqrt(x); }
        private String format(Object o) { return String.valueOf(o); }
    }

    // =========================================================================
    // DEMO 2: JVM Instructions — key opcodes by category
    // =========================================================================
    static void demo2_JvmInstructions() {
        System.out.println("--- Demo 2: JVM Instruction Categories ---");

        System.out.println("""
            ── Load/Store ──────────────────────────────────────────────────────
            iload_<n>   load int from local var slot n → stack
            istore_<n>  pop int from stack → local var slot n
            aload_<n>   load reference (object) from slot n
            astore_<n>  store reference to slot n
            ldc         load constant (String, int, float, Class) from pool

            ── Arithmetic ──────────────────────────────────────────────────────
            iadd  isub  imul  idiv  irem   (int)
            ladd  lsub  lmul  ldiv  lrem   (long)
            fadd  fsub  fmul  fdiv          (float)
            dadd  dsub  dmul  ddiv          (double)
            ineg / lneg                     (negate)
            iinc  <slot> <delta>            (increment local var directly, no stack)

            ── Type conversion ─────────────────────────────────────────────────
            i2l  i2f  i2d  l2i  l2f  f2i  d2i  d2l  (widening/narrowing)
            checkcast <type>                (throws ClassCastException if fails)
            instanceof <type>              (push 0 or 1)

            ── Object/Array ────────────────────────────────────────────────────
            new <class>          allocate object (no constructor call yet)
            dup                  duplicate top of stack (needed: new then dup then invokespecial)
            getfield / putfield  instance field access
            getstatic / putstatic static field access
            arraylength          pop array ref, push length
            newarray / anewarray create primitive / reference array

            ── Control flow ────────────────────────────────────────────────────
            if_icmpeq  if_icmpne  if_icmplt  if_icmpge  if_icmpgt  if_icmple
            ifnull / ifnonnull
            goto <offset>
            tableswitch  (dense switch → jump table)
            lookupswitch (sparse switch → search)

            ── Stack manipulation ──────────────────────────────────────────────
            pop   pop2   dup   dup2   swap

            ── Return ──────────────────────────────────────────────────────────
            ireturn  lreturn  freturn  dreturn  areturn  return (void)
            """);

        // Practical: show what "x++" vs "++x" compiles to conceptually
        System.out.println("Practical difference visible in bytecode:");
        System.out.println("  x++ → iload x, iinc x 1  (old value used, then incremented)");
        System.out.println("  ++x → iinc x 1, iload x  (incremented first, then used)");
        System.out.println("  In bytecode: 'iinc' is a direct local-var instruction (no stack ops!)");
        System.out.println();
    }

    // =========================================================================
    // DEMO 3: Constant Pool & Method Descriptors
    // =========================================================================
    static void demo3_ConstantPoolAndDescriptors() {
        System.out.println("--- Demo 3: Constant Pool & Type Descriptors ---");

        System.out.println("""
            ── Constant Pool ───────────────────────────────────────────────────
            Mỗi .class file chứa một constant pool — bảng tra cứu trung tâm.

            Các loại entry:
              CONSTANT_Utf8        : raw string bytes (field names, signatures, source file)
              CONSTANT_Class       : class/interface reference → points to Utf8 entry
              CONSTANT_Fieldref    : class + name-and-type descriptor
              CONSTANT_Methodref   : class + name + descriptor
              CONSTANT_NameAndType : name + descriptor pair
              CONSTANT_String      : String literal → points to Utf8
              CONSTANT_Integer     : int literal ≤ 65535 (ldc uses this)
              CONSTANT_InvokeDynamic: bootstrap method + name + descriptor

            Ví dụ: gọi System.out.println("Hello"):
              #1 CONSTANT_Fieldref: java/lang/System.out:Ljava/io/PrintStream;
              #2 CONSTANT_Methodref: java/io/PrintStream.println:(Ljava/lang/String;)V
              #3 CONSTANT_String: "Hello"

            ── Type Descriptors ────────────────────────────────────────────────
            Primitive:
              B=byte  C=char  D=double  F=float  I=int
              J=long  S=short  Z=boolean  V=void

            Object:    L<fully/qualified/ClassName>;
            Array:     [I (int[])  [[D (double[][])  [Ljava/lang/String; (String[])

            Method descriptor: (parameter-types)return-type
            Examples:
              ()V                           → void noArgs()
              (I)I                          → int method(int)
              (Ljava/lang/String;I)Z        → boolean method(String, int)
              ([Ljava/lang/Object;)V        → void method(Object[])
              (IILjava/lang/String;[D)[I   → int[] method(int,int,String,double[])
            """);

        // Show descriptors via reflection
        System.out.println("Method descriptors inferred from reflection:");
        for (Method m : DescriptorExample.class.getDeclaredMethods()) {
            StringBuilder desc = new StringBuilder("(");
            for (Class<?> p : m.getParameterTypes()) desc.append(toDescriptor(p));
            desc.append(")").append(toDescriptor(m.getReturnType()));
            System.out.printf("  %-40s → %s%n", m.getName(), desc);
        }
        System.out.println();
    }

    static String toDescriptor(Class<?> c) {
        if (c == void.class)    return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class)    return "B";
        if (c == char.class)    return "C";
        if (c == short.class)   return "S";
        if (c == int.class)     return "I";
        if (c == long.class)    return "J";
        if (c == float.class)   return "F";
        if (c == double.class)  return "D";
        if (c.isArray()) return "[" + toDescriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    @SuppressWarnings("unused")
    static class DescriptorExample {
        void noArgs() {}
        int addInts(int a, int b) { return a + b; }
        boolean check(String s, int n) { return s.length() > n; }
        String[] split(String s) { return s.split(","); }
        double[] toDoubles(int[] arr) { return new double[arr.length]; }
    }

    // =========================================================================
    // DEMO 4: invoke* Instructions — the heart of method dispatch
    // =========================================================================
    static void demo4_InvokeInstructions() {
        System.out.println("--- Demo 4: invoke* Instructions ---");

        System.out.println("""
            JVM có 5 loại invoke:

            ┌─────────────────┬────────────────────────────────────────────────┐
            │ Instruction     │ When used                                      │
            ├─────────────────┼────────────────────────────────────────────────┤
            │ invokestatic    │ static methods (no 'this')                    │
            │ invokespecial   │ constructors, private methods, super calls    │
            │ invokevirtual   │ regular instance methods (class-based vtable) │
            │ invokeinterface │ interface methods (slower — needs itable)     │
            │ invokedynamic   │ lambdas, String concat in Java 9+, Groovy...  │
            └─────────────────┴────────────────────────────────────────────────┘

            Performance order (fastest → slowest typically):
              invokestatic > invokespecial > invokevirtual > invokeinterface

            WHY invokeinterface is slower than invokevirtual:
            - invokevirtual: fixed offset in vtable (resolved once → inline)
            - invokeinterface: class may implement many interfaces; requires
              itable search; JIT can still monomorphize if only 1 impl seen

            SA Advice: In hotpath code, prefer concrete classes over interfaces
            when the JIT hasn't warmed up. In practice, JIT makes this moot
            for well-profiled code — but explains why JMH results can surprise.
            """);

        // Demonstrate: calling same logic via static, virtual, interface
        System.out.println("Invoke type benchmark (relative, demonstration only):");

        int N = 1_000_000;
        Transformer concreteImpl = new UpperCaseTransformer();
        StringTransformer iface = concreteImpl; // via interface reference

        // invokestatic
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) StaticHelper.transform("hello");
        long staticNs = System.nanoTime() - t0;

        // invokevirtual (concrete type known)
        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) concreteImpl.transform("hello");
        long virtualNs = System.nanoTime() - t0;

        // invokeinterface
        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) iface.transform("hello");
        long ifaceNs = System.nanoTime() - t0;

        System.out.printf("  invokestatic   : %,d ns total%n", staticNs);
        System.out.printf("  invokevirtual  : %,d ns total%n", virtualNs);
        System.out.printf("  invokeinterface: %,d ns total%n", ifaceNs);
        System.out.println("  (JIT bimonophizes all → similar after warmup)");
        System.out.println();
    }

    interface StringTransformer { String transform(String s); }
    static class Transformer implements StringTransformer {
        public String transform(String s) { return s.toUpperCase(); }
    }
    static class UpperCaseTransformer extends Transformer {}
    static class StaticHelper {
        static String transform(String s) { return s.toUpperCase(); }
    }

    // =========================================================================
    // DEMO 5: invokedynamic & Lambda Implementation
    // =========================================================================
    static void demo5_InvokeDynamicAndLambdas() {
        System.out.println("--- Demo 5: invokedynamic & Lambdas ---");

        System.out.println("""
            invokedynamic (Java 7+) — dynamic method dispatch:
            - Bytecode chứa: invokedynamic #bootstrapMethod, "functionalMethodName", "(descriptor)"
            - Bootstrap method được gọi MỘT LẦN (first execution) → trả về CallSite
            - CallSite chứa MethodHandle cho lần gọi sau (no more bootstrap overhead)

            How lambda works (NOT anonymous class since Java 8):

              // Source:
              Runnable r = () -> System.out.println("hi");

              // Bytecode:
              invokedynamic "run":()Ljava/lang/Runnable;
                  Bootstrap: LambdaMetafactory.metafactory(...)

            LambdaMetafactory at runtime:
            1. Generates bytecode for a tiny class implementing Runnable
            2. Uses Unsafe.defineAnonymousClass (or Java 15+ hidden classes)
            3. Returns a MethodHandle bound to that generated class
            4. CallSite caches the handle → subsequent calls bypass bootstrap

            WHY not anonymous class?
            - Anonymous class: 1 .class file per lambda → class loading overhead
            - invokedynamic: generated at runtime, no .class file, JIT-friendly
            - Lambda can be a singleton (stateless) → further optimization
            """);

        // Show different lambda capture behaviors
        System.out.println("Lambda capture categories:");

        // Non-capturing: can be singleton
        Runnable nonCapturing = () -> {};
        System.out.println("  Non-capturing lambda (no captured vars): likely singleton");

        // Capturing instance: new instance per usage in some JVMs
        String prefix = "prefix";
        Supplier<String> capturing = () -> prefix + "_value";
        System.out.println("  Capturing lambda: binds '" + prefix + "' → " + capturing.get());

        // Method reference types
        System.out.println("\nMethod reference types:");
        System.out.println("  Static ref:   String::valueOf      → invokestatic");
        System.out.println("  Bound ref:    \"hello\"::toUpperCase → invokevirtual on specific instance");
        System.out.println("  Unbound ref:  String::toUpperCase  → invokevirtual, instance passed as arg");
        System.out.println("  Constructor:  ArrayList::new       → invokespecial <init>");

        // Demonstrate MethodHandle (building block of invokedynamic)
        System.out.println("\nMethodHandle — invokedynamic building block:");
        try {
            var lookup = java.lang.invoke.MethodHandles.lookup();

            // MethodHandle for String.toUpperCase()
            var toUpper = lookup.findVirtual(String.class, "toUpperCase",
                java.lang.invoke.MethodType.methodType(String.class));
            System.out.println("  MethodHandle invoke: " + (String) toUpper.invoke("hello"));

            // Partial application via bindTo (currying)
            var bound = toUpper.bindTo("world");
            System.out.println("  Bound MethodHandle:  " + (String) bound.invoke());

            // MethodHandle for static method
            var parseInt = lookup.findStatic(Integer.class, "parseInt",
                java.lang.invoke.MethodType.methodType(int.class, String.class));
            int result = (int) parseInt.invoke("42");
            System.out.println("  Static MethodHandle: " + result);

        } catch (Throwable e) {
            System.out.println("  MethodHandle error: " + e.getMessage());
        }
        System.out.println();
    }

    // =========================================================================
    // DEMO 6: ASM Library Pattern (without actual ASM dependency)
    // =========================================================================
    static void demo6_AsmLibraryPattern() {
        System.out.println("--- Demo 6: ASM Library Pattern ---");

        System.out.println("""
            ASM (ObjectWeb) — the most popular bytecode manipulation library:
            - Frameworks that use ASM: Spring (CGLIB proxy), Hibernate, Mockito, Byte Buddy
            - Two APIs: Core API (visitor pattern, streaming) vs Tree API (in-memory model)

            Core API flow:
              byte[] classBytes = Files.readAllBytes(classFile);
              ClassReader reader = new ClassReader(classBytes);
              ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
              ClassVisitor transformer = new MyTransformer(writer);  // chain
              reader.accept(transformer, 0);
              byte[] newBytes = writer.toByteArray();

            Visitor pattern (same as XML SAX):
              ClassReader emits events → ClassVisitor receives them

              ClassVisitor:
                visit()           → class declaration
                visitField()      → each field
                visitMethod()     → each method → returns MethodVisitor
                visitEnd()        → class done

              MethodVisitor (called per instruction):
                visitCode()
                visitVarInsn(ILOAD, 1)     → iload_1
                visitMethodInsn(...)        → invoke*
                visitInsn(IRETURN)          → ireturn
                visitMaxs(stack, locals)
                visitEnd()
            """);

        // Simulate ASM-style visitor without actual library
        System.out.println("Simulating ASM visitor pattern on our SimpleCalculator class:");
        simulateAsmVisit(SimpleCalculator.class);

        System.out.println("""

            Common ASM use cases:

            1. Method timing (AOP):
               visitCode() → inject "long start = System.nanoTime();"
               visitInsn(RETURN-type) → inject "log(System.nanoTime()-start);"

            2. Null checking (Lombok @NonNull):
               visitCode() → inject null check before method body
               generates: if (param == null) throw new NullPointerException()

            3. Field access interception (Hibernate lazy loading):
               visitFieldInsn(GETFIELD) → replace with method call
               generated: instead of field read, call getXxx() which may fetch DB

            4. Coverage instrumentation (JaCoCo):
               Each bytecode line → inject: probeArray[N] = true;
               After test run → count true probes = coverage %

            Byte Buddy (higher-level than ASM):
              new ByteBuddy()
                .subclass(Foo.class)
                .method(named("bar"))
                .intercept(MethodDelegation.to(MyInterceptor.class))
                .make()
                .load(classLoader)
                .getLoaded();
            """);
        System.out.println();
    }

    static void simulateAsmVisit(Class<?> clazz) {
        System.out.println("  ClassReader.accept → visiting " + clazz.getName());
        System.out.println("  visit(version=61 [Java17], ACC_PUBLIC, name=" +
            clazz.getSimpleName() + ", superName=Object)");

        for (Field f : clazz.getDeclaredFields()) {
            System.out.printf("  visitField(ACC_%s, name=%s, desc=%s)%n",
                Modifier.isStatic(f.getModifiers()) ? "STATIC" : "PUBLIC",
                f.getName(), toDescriptor(f.getType()));
        }

        for (Method m : clazz.getDeclaredMethods()) {
            StringBuilder params = new StringBuilder("(");
            for (Class<?> p : m.getParameterTypes()) params.append(toDescriptor(p));
            params.append(")").append(toDescriptor(m.getReturnType()));
            System.out.printf("  visitMethod(ACC_%s, name=%s, desc=%s)%n",
                Modifier.isStatic(m.getModifiers()) ? "STATIC" : "PUBLIC",
                m.getName(), params);
        }
        System.out.println("  visitEnd()");
    }

    // =========================================================================
    // DEMO 7: Java Instrumentation API
    // =========================================================================
    static void demo7_InstrumentationApi() {
        System.out.println("--- Demo 7: Java Instrumentation API (javaagent) ---");

        System.out.println("""
            Java Instrumentation (java.lang.instrument) — hook into JVM class loading:

            Activation:
              java -javaagent:myagent.jar -jar myapp.jar

            Agent JAR MANIFEST.MF:
              Premain-Class: com.example.MyAgent
              Agent-Class: com.example.MyAgent          (for attach API)
              Can-Redefine-Classes: true
              Can-Retransform-Classes: true

            Agent entry point:
              public static void premain(String args, Instrumentation inst) {
                  inst.addTransformer(new MyTransformer(), true);
              }

            ClassFileTransformer:
              public byte[] transform(
                  ClassLoader loader,
                  String className,          // "org/example/Foo" (slashes!)
                  Class<?> classBeingRedefined,
                  ProtectionDomain domain,
                  byte[] classfileBuffer     // original bytecode
              ) {
                  // Use ASM to modify classfileBuffer
                  // Return modified bytes, or null to skip
              }

            Instrumentation API capabilities:
              inst.getAllLoadedClasses()           → array of all classes
              inst.getObjectSize(obj)              → memory footprint (JOL alternative)
              inst.redefineClasses(ClassDefinition[]) → hot swap (limited)
              inst.retransformClasses(Class<?>...) → re-run all transformers
              inst.appendToBootstrapClassLoaderSearch(jar)
            """);

        // Simulate object size measurement (approximation)
        System.out.println("Object size approximation (without real Instrumentation.getObjectSize):");
        System.out.printf("  Object header overhead: ~16 bytes (mark word + class pointer)%n");
        System.out.printf("  String \"hello\"  ≈ %d bytes header + char[] or byte[] data%n", 16);
        System.out.printf("  Integer object  ≈ 16 bytes (header + int field)%n");
        System.out.printf("  ArrayList(0)    ≈ 48 bytes (header + size + Object[] ref + array header)%n");

        // Demonstrate: simulate ClassFileTransformer decision logic
        System.out.println("\nClassFileTransformer filter logic:");
        String[] candidateClasses = {
            "org/example/service/OrderService",
            "java/lang/String",
            "sun/reflect/GeneratedAccessorImpl",
            "org/example/repository/UserRepo",
            "com/example/thirdparty/ExternalLib"
        };

        for (String cls : candidateClasses) {
            boolean shouldTransform = cls.startsWith("org/example/") || cls.startsWith("com/example/");
            boolean isJdkInternal = cls.startsWith("java/") || cls.startsWith("sun/");
            System.out.printf("  %-50s → %s%n", cls,
                isJdkInternal ? "SKIP (JDK internal)" :
                shouldTransform ? "TRANSFORM (inject timing)" : "SKIP (3rd party)");
        }

        System.out.println("""

            Popular agents in production:
              - New Relic / Dynatrace / Datadog: APM agents using Instrumentation
              - JaCoCo: coverage → transforms every class, injects probe arrays
              - Byte Buddy Agent: used by Mockito for spy/mock creation
              - OpenTelemetry Java Agent: auto-instrument common frameworks
              - Arthas (Alibaba): live bytecode manipulation for production debugging

            SA Decision: Agent overhead is typically 1-5% CPU for APM agents.
            JaCoCo adds ~5-15% overhead — not suitable for load testing.
            """);
        System.out.println();
    }

    // =========================================================================
    // DEMO 8: Framework Bytecode Patterns
    // =========================================================================
    static void demo8_FrameworkBytecodePatterns() {
        System.out.println("--- Demo 8: Framework Bytecode Patterns ---");

        System.out.println("""
            ── Spring AOP / CGLIB Proxy ────────────────────────────────────────
            Problem: @Transactional on a concrete class (no interface).
            JDK Proxy requires interface → Spring uses CGLIB instead.

            CGLIB generates a subclass at runtime:
              // Generated by CGLIB (pseudocode):
              class OrderService$$EnhancerByCGLIB extends OrderService {
                  private MethodInterceptor interceptor;

                  @Override
                  public void placeOrder(Order o) {
                      // Spring's TransactionInterceptor logic:
                      TransactionStatus tx = txManager.getTransaction(...);
                      try {
                          super.placeOrder(o);  // invokespecial (bypass proxy)
                          txManager.commit(tx);
                      } catch (Exception e) {
                          txManager.rollback(tx);
                          throw e;
                      }
                  }
              }

            KEY LIMITATION — self-invocation bypass:
              class OrderService {
                  public void processOrder() {
                      this.placeOrder(o);  // 'this' = real object, NOT proxy!
                      // → @Transactional on placeOrder() is ignored!
                  }
              }
            Fix: inject self as dependency, or use AspectJ compile-time weaving.

            ── Hibernate Lazy Loading ──────────────────────────────────────────
            @Entity class User { @OneToMany List<Order> orders; }

            Hibernate generates:
              class User$$HibernateProxy extends User {
                  private HibernateInterceptor interceptor;

                  @Override
                  public List<Order> getOrders() {
                      if (orders == null) {
                          orders = interceptor.loadCollection(User.class, id, "orders");
                      }
                      return orders;
                  }
              }

            This is why:
            - entity.getClass() != User.class (it's the proxy subclass)
            - Use Hibernate.getClass(entity) for real class
            - final methods can't be overridden → can't be lazy-loaded!
            - Detached entity access throws LazyInitializationException
              (session closed → proxy can't load → NPE-like error)

            ── Jackson JSON Serialization ──────────────────────────────────────
            Jackson uses reflection + bytecode optimization:
            1. First call: reflection-based (slow)
            2. Generates BeanPropertyWriter subclass via ASM for fast field access
            3. Caches the generated deserializer per class

            Result: Jackson cold start slow, warm path ~5x faster than raw reflection.
            """);

        // Demonstrate proxy detection
        System.out.println("Proxy detection patterns:");
        OrderService realService = new OrderService("real");
        Object proxy = createSimpleProxy(OrderService.class, realService);

        System.out.println("  realService.getClass(): " + realService.getClass().getSimpleName());
        System.out.println("  proxy.getClass():       " + proxy.getClass().getSimpleName());
        System.out.println("  proxy instanceof OrderService: " + (proxy instanceof OrderService));
        System.out.println("  Proxy.isProxyClass(proxy.getClass()): " +
            java.lang.reflect.Proxy.isProxyClass(proxy.getClass()));

        System.out.println("\nFinal class → cannot be subclassed → CGLIB FAILS:");
        System.out.println("  final class FinalService { } → Spring cannot create proxy!");
        System.out.println("  Solution: remove final, or use interface + JDK proxy");
        System.out.println();
    }

    interface OrderServiceInterface {
        void process(String order);
    }

    static class OrderService implements OrderServiceInterface {
        private final String name;
        OrderService(String name) { this.name = name; }
        public void process(String order) {
            System.out.println("    [" + name + "] Processing: " + order);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T createSimpleProxy(Class<T> clazz, T target) {
        // Must use interface for JDK proxy — shows the limitation CGLIB solves
        return (T) java.lang.reflect.Proxy.newProxyInstance(
            clazz.getClassLoader(),
            new Class<?>[]{ OrderServiceInterface.class },
            (proxy, method, args) -> {
                System.out.println("    [proxy] before: " + method.getName());
                Object result = method.invoke(target, args);
                System.out.println("    [proxy] after:  " + method.getName());
                return result;
            }
        );
    }

    // =========================================================================
    // DEMO 9: Bytecode Manipulation in Practice — mini runtime enhancer
    // =========================================================================
    static void demo9_BytecodeManipulationInPractice() {
        System.out.println("--- Demo 9: Bytecode Manipulation in Practice ---");

        System.out.println("""
            Without adding ASM as a dependency, we can demonstrate the PATTERNS
            that bytecode manipulation enables, using Java's own APIs.
            """);

        // Pattern 1: Method Timing via Proxy (simulates AOP agent behavior)
        System.out.println("Pattern 1: Method Timing (AOP-style via Proxy)");
        OrderServiceInterface service = new OrderService("warehouse");
        OrderServiceInterface timedService = withTiming(service);
        timedService.process("ORDER-001");
        timedService.process("ORDER-002");

        // Pattern 2: Simulate class instrumentation counting
        System.out.println("\nPattern 2: Simulated Coverage Probe Tracking");
        CoverageTracker coverage = new CoverageTracker(5);
        // Simulate method A calling probes 0, 1, 2
        coverage.probe(0); coverage.probe(1); coverage.probe(2);
        // Simulate method B calling probe 3
        coverage.probe(3);
        // Probe 4 never hit
        coverage.report();

        // Pattern 3: Dynamic class analysis
        System.out.println("\nPattern 3: Class Hierarchy Analysis (javap -verbose output simulation)");
        analyzeClass(ArrayList.class);

        // Pattern 4: Bytecode size estimation
        System.out.println("\nPattern 4: Bytecode complexity estimation via reflection");
        estimateComplexity(BytecodeDemo.class);

        System.out.println("""

            When to use each bytecode tool:

            ┌──────────────────┬──────────────────────────────────────────────────┐
            │ Tool             │ Best for                                         │
            ├──────────────────┼──────────────────────────────────────────────────┤
            │ javap            │ Debugging: what did javac compile my code to?   │
            │ ASM              │ Low-level: JaCoCo, framework internals, agents  │
            │ Byte Buddy       │ Higher-level: Mockito, custom agents, plugins   │
            │ Javassist        │ Legacy: Hibernate, JBoss (String-based editing) │
            │ javaagent        │ Production: APM, tracing, coverage, hot reload  │
            │ JDK Proxy        │ Interface-based AOP (Spring @Transactional)     │
            │ CGLIB            │ Class-based AOP when no interface available     │
            └──────────────────┴──────────────────────────────────────────────────┘

            SA-Level Decision Framework for bytecode manipulation:

            1. DO YOU NEED IT?
               → Reflection usually simpler; bytecode for high-performance paths

            2. COMPILE-TIME vs RUNTIME?
               → Compile-time (Lombok, MapStruct): zero runtime overhead, IDE-friendly
               → Runtime (CGLIB, ASM): flexible, but adds startup cost

            3. DEBUGGING BYTECODE:
               → javap -c -verbose: see exactly what javac produced
               → JITWatch: visualize JIT compilation + inlining decisions
               → async-profiler: find hot paths that bytecode reveals

            4. SECURITY CONSIDERATIONS:
               → Bytecode manipulation bypasses access control
               → javaagent can modify any class including JDK internals
               → In containers/cloud: use -Djdk.attach.allowAttachSelf=true carefully
               → Java 17+ strong encapsulation limits some manipulation (--add-opens)
            """);
    }

    @SuppressWarnings("unchecked")
    static <T> T withTiming(T target) {
        Class<?>[] interfaces = target.getClass().getInterfaces();
        return (T) java.lang.reflect.Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            interfaces,
            (proxy, method, args) -> {
                long start = System.nanoTime();
                Object result = method.invoke(target, args);
                long elapsed = System.nanoTime() - start;
                System.out.printf("  [timing] %s.%s() → %,d ns%n",
                    target.getClass().getSimpleName(), method.getName(), elapsed);
                return result;
            }
        );
    }

    static class CoverageTracker {
        private final boolean[] probes;
        CoverageTracker(int count) { this.probes = new boolean[count]; }
        void probe(int idx) { probes[idx] = true; }
        void report() {
            int hit = 0;
            for (boolean p : probes) if (p) hit++;
            System.out.printf("  Coverage: %d/%d probes hit (%.0f%%)%n",
                hit, probes.length, 100.0 * hit / probes.length);
            for (int i = 0; i < probes.length; i++) {
                System.out.printf("    Probe[%d]: %s%n", i, probes[i] ? "HIT" : "MISSED");
            }
        }
    }

    static void analyzeClass(Class<?> clazz) {
        System.out.println("  Class: " + clazz.getName());
        System.out.println("  Super: " + (clazz.getSuperclass() != null ?
            clazz.getSuperclass().getName() : "none"));
        System.out.println("  Interfaces: " + Arrays.toString(
            Arrays.stream(clazz.getInterfaces()).map(Class::getSimpleName).toArray()));
        System.out.println("  Modifiers: " + Modifier.toString(clazz.getModifiers()));
        System.out.println("  Fields:    " + clazz.getDeclaredFields().length + " declared");
        System.out.println("  Methods:   " + clazz.getDeclaredMethods().length + " declared");
        System.out.println("  Constructors: " + clazz.getDeclaredConstructors().length);
    }

    static void estimateComplexity(Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();
        int totalParams = 0;
        int maxParams = 0;
        for (Method m : methods) {
            int p = m.getParameterCount();
            totalParams += p;
            maxParams = Math.max(maxParams, p);
        }
        System.out.printf("  Class: %s%n", clazz.getSimpleName());
        System.out.printf("  Method count: %d%n", methods.length);
        System.out.printf("  Avg params per method: %.1f%n",
            methods.length > 0 ? (double) totalParams / methods.length : 0);
        System.out.printf("  Max params in one method: %d%n", maxParams);
        System.out.printf("  Inner classes: %d%n", clazz.getDeclaredClasses().length);
        System.out.printf("  Rough complexity: %s%n",
            methods.length > 50 ? "HIGH (consider splitting)" :
            methods.length > 20 ? "MEDIUM" : "LOW");
    }
}
