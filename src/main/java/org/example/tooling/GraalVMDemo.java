package org.example.tooling;

/**
 * ============================================================
 * BÀI 10.3 — GRAALVM: NATIVE IMAGE, AOT & POLYGLOT
 * ============================================================
 *
 * GraalVM là JDK thay thế mở rộng với 3 tính năng cốt lõi:
 *
 *  1. GRAAL JIT COMPILER
 *     - Viết bằng Java (JVMCI - JVM Compiler Interface)
 *     - Thay thế C2 HotSpot JIT
 *     - Tốt hơn C2 cho: speculative optimization, partial escape analysis
 *     - Dùng: java -XX:+UseJVMCICompiler (GraalVM hoặc GraalVM CE on JDK 11+)
 *
 *  2. NATIVE IMAGE (AOT Compilation)
 *     - Compile Java → native executable (không cần JVM runtime)
 *     - Dùng: native-image -jar app.jar
 *     - SUBSTRATE VM: minimal runtime thay JVM
 *     - CLOSED-WORLD ASSUMPTION: tất cả code phải biết tại build time
 *
 *     Startup/Memory tradeoffs:
 *     ┌──────────────────┬────────────┬──────────┬──────────────┐
 *     │ Mode             │ Startup    │ Memory   │ Peak Perf    │
 *     ├──────────────────┼────────────┼──────────┼──────────────┤
 *     │ JVM (JIT)        │ 1-5 sec    │ ~200MB   │ ★★★★★ (warm) │
 *     │ Native Image     │ 10-50ms    │ ~30MB    │ ★★★ (no JIT) │
 *     │ Native + PGO     │ 15-60ms    │ ~35MB    │ ★★★★ (guided)│
 *     └──────────────────┴────────────┴──────────┴──────────────┘
 *
 *     Closed-world limitations:
 *     - Reflection → cần reflect-config.json hoặc @RegisterForReflection
 *     - Dynamic class loading → không hỗ trợ
 *     - Serialization → cần serialization-config.json
 *     - JNI → cần jni-config.json
 *     - Resources → cần resource-config.json
 *     - Proxy → cần proxy-config.json
 *
 *  3. POLYGLOT API (Truffle Framework)
 *     - Chạy Python, JavaScript, Ruby, R, WASM trong JVM
 *     - Zero-overhead language interop qua Truffle AST interpreter
 *     - Dùng: Context.create() → eval → polyglot Value
 *
 * ============================================================
 * KIẾN TRÚC GRAALVM:
 * ============================================================
 *
 *  GraalVM Distribution
 *  ├── OpenJDK (JVM engine)
 *  │   ├── Graal JIT Compiler (thay C2)
 *  │   └── JVMCI interface
 *  ├── Native Image Tool (native-image)
 *  │   ├── Substrate VM (minimal runtime)
 *  │   ├── Points-to Analysis (Bigbang)
 *  │   └── AOT Compiler (Graal compiler in AOT mode)
 *  ├── Truffle Framework
 *  │   ├── GraalJS (JavaScript/Node.js)
 *  │   ├── GraalPython
 *  │   ├── TruffleRuby
 *  │   └── Espresso (Java on Truffle)
 *  └── VisualVM, heap dump tools
 *
 * ============================================================
 * SPRING BOOT NATIVE (THỰC TẾ):
 * ============================================================
 *
 *  pom.xml:
 *  <plugin>
 *    <groupId>org.graalvm.buildtools</groupId>
 *    <artifactId>native-maven-plugin</artifactId>
 *    <version>0.9.28</version>
 *    <extensions>true</extensions>
 *    <configuration>
 *      <imageName>my-app</imageName>
 *      <buildArgs>
 *        <buildArg>--no-fallback</buildArg>
 *        <buildArg>-H:+ReportExceptionStackTraces</buildArg>
 *      </buildArgs>
 *    </configuration>
 *  </plugin>
 *
 *  Build: mvn -Pnative native:compile
 *  Run:   ./target/my-app
 *
 * ============================================================
 * QUARKUS NATIVE (THỰC TẾ):
 * ============================================================
 *
 *  Build:  mvn package -Pnative
 *          mvn package -Pnative -Dquarkus.native.container-build=true  # Docker
 *  Run:    ./target/my-app-1.0-runner
 *
 *  @RegisterForReflection  // Quarkus annotation thay reflect-config.json
 *  class MyDto { ... }
 *
 * Author: JavaClaudeTutorial
 * Lesson: 10.3 — GraalVM & Native Image
 */
public class GraalVMDemo {

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("   BÀI 10.3 — GRAALVM: NATIVE IMAGE, AOT & POLYGLOT");
        System.out.println("═══════════════════════════════════════════════════════\n");

        demoNativeImageConcepts();
        demoClosedWorldAssumption();
        demoReflectionChallenge();
        demoSubstrateVMRuntime();
        demoAOTCompilationPipeline();
        demoPolyglotConcepts();
        demoProfileGuidedOptimization();
        demoNativeImageConfiguration();
        demoFrameworkSupport();
        demoBestPracticesAndPitfalls();
    }

    // =========================================================
    // DEMO 1: NATIVE IMAGE CONCEPTS
    // =========================================================

    static void demoNativeImageConcepts() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 1: NATIVE IMAGE — AOT COMPILATION CONCEPTS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Quá trình build Native Image:");
        System.out.println("  Java source → .class (javac) → native-image tool → native binary");
        System.out.println();

        // Simulate the AOT compilation pipeline stages
        System.out.println("Các giai đoạn native-image build:");
        simulateBuildPhase("1. Initialization",        "Load JARs, find main class, register features");
        simulateBuildPhase("2. Points-to Analysis",    "Static analysis: tìm tất cả code paths có thể chạy");
        simulateBuildPhase("3. Universe Building",     "Build type system, method tables cho Substrate VM");
        simulateBuildPhase("4. Parsing & Compilation", "AOT compile bytecode → machine code (LLVM or Graal backend)");
        simulateBuildPhase("5. Heap Layout",           "Serialize pre-initialized heap state vào binary");
        simulateBuildPhase("6. Image Writing",         "Link thành executable (ELF/Mach-O/PE)");
        System.out.println();

        System.out.println("Startup time comparison (micro-service hello-world):");
        printStartupComparison("JVM (OpenJDK 21)",     "1200ms", "180MB RSS", "Class loading + JIT warmup");
        printStartupComparison("Native Image",          "  18ms", " 28MB RSS", "No class loading, no JIT");
        printStartupComparison("Native Image + PGO",    "  20ms", " 32MB RSS", "Guided optimizations at build");
        System.out.println();

        System.out.println("Khi nào dùng Native Image?");
        System.out.println("  ✅ Serverless / FaaS (Lambda, Cloud Functions) — cold start quan trọng");
        System.out.println("  ✅ CLI tools — startup < 50ms là must-have");
        System.out.println("  ✅ Microservices với nhiều instances — tiết kiệm RAM");
        System.out.println("  ✅ Container environments — image nhỏ hơn (~5MB vs ~200MB JVM)");
        System.out.println("  ❌ Long-running services — JIT warm up sẽ win peak throughput");
        System.out.println("  ❌ Dynamic class loading / plugins — không support");
        System.out.println("  ❌ Complex reflection-heavy frameworks chưa có native support");
        System.out.println();
    }

    static void simulateBuildPhase(String phase, String description) {
        System.out.printf("  [%-30s] %s%n", phase, description);
    }

    static void printStartupComparison(String mode, String startup, String memory, String note) {
        System.out.printf("  %-25s  startup: %6s  memory: %8s  (%s)%n", mode, startup, memory, note);
    }

    // =========================================================
    // DEMO 2: CLOSED-WORLD ASSUMPTION
    // =========================================================

    static void demoClosedWorldAssumption() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 2: CLOSED-WORLD ASSUMPTION");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Closed-World = native-image phải biết TẤT CẢ code tại build time.");
        System.out.println("Không có gì được load/generate/compile lúc runtime.");
        System.out.println();

        System.out.println("Points-to Analysis (Bigbang Algorithm):");
        System.out.println("  native-image phân tích toàn bộ call graph từ main():");
        System.out.println();
        System.out.println("  main()");
        System.out.println("   └─ ServiceA.process()");
        System.out.println("       ├─ Repository.findById()  ← included");
        System.out.println("       ├─ Logger.info()          ← included");
        System.out.println("       └─ unused_method()        ← EXCLUDED (dead code elimination)");
        System.out.println();
        System.out.println("  → Kết quả: binary chỉ chứa code thực sự được dùng!");
        System.out.println("  → Nhỏ hơn JVM bundle vì không có unused JDK classes");
        System.out.println();

        System.out.println("Các tính năng JVM KHÔNG support trong Native Image:");
        printClosedWorldConstraint("Dynamic class loading",
                "Class.forName(className) chỉ work nếu className là string constant");
        printClosedWorldConstraint("Reflection (mặc định)",
                "Phải khai báo trong reflect-config.json hoặc dùng annotation");
        printClosedWorldConstraint("JDK proxies",
                "Phải khai báo interface list trong proxy-config.json");
        printClosedWorldConstraint("Serialization",
                "Phải khai báo classes cần serialize trong serialization-config.json");
        printClosedWorldConstraint("Runtime bytecode generation",
                "ASM/Javassist/ByteBuddy không work ở runtime (chỉ work tại build)");
        printClosedWorldConstraint("Finalizers",
                "finalize() bị deprecated và không reliable trong SubstrateVM");
        printClosedWorldConstraint("SecurityManager",
                "Không được support");
        System.out.println();

        System.out.println("Workarounds:");
        System.out.println("  1. Tracing agent: java -agentlib:native-image-agent=config-output-dir=META-INF/native-image");
        System.out.println("     → Chạy app → agent tự generate JSON configs từ runtime behavior");
        System.out.println("  2. Reachability metadata: GraalVM repo cộng đồng contribute configs");
        System.out.println("     → https://github.com/oracle/graalvm-reachability-metadata");
        System.out.println("  3. Framework annotations: @RegisterForReflection (Quarkus), @ReflectionHint (Spring)");
        System.out.println();
    }

    static void printClosedWorldConstraint(String feature, String detail) {
        System.out.printf("  ❌ %-35s → %s%n", feature, detail);
    }

    // =========================================================
    // DEMO 3: REFLECTION CHALLENGE
    // =========================================================

    static void demoReflectionChallenge() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 3: REFLECTION CHALLENGE & SOLUTIONS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("VẤN ĐỀ: Reflection tại runtime không biết tại build time");
        System.out.println();

        // Demonstrate reflection detection
        System.out.println("❌ CÁC PATTERN BỊ BREAK trong Native Image:");
        System.out.println();

        System.out.println("  // KHÔNG WORK — className là runtime variable");
        System.out.println("  String className = config.getProperty(\"impl.class\");");
        System.out.println("  Class<?> clazz = Class.forName(className);  // ← MissingReflectionRegistrationError");
        System.out.println();

        System.out.println("  // KHÔNG WORK — Jackson deserialization cần constructor reflection");
        System.out.println("  ObjectMapper mapper = new ObjectMapper();");
        System.out.println("  MyDto dto = mapper.readValue(json, MyDto.class);  // ← fail nếu chưa config");
        System.out.println();

        System.out.println("  // KHÔNG WORK — Spring @Autowired dùng reflection");
        System.out.println("  @Autowired private UserService userService;  // ← cần Spring Native support");
        System.out.println();

        System.out.println("✅ SOLUTIONS:");
        System.out.println();

        System.out.println("SOLUTION 1: reflect-config.json (thủ công)");
        System.out.println("  Tạo file: src/main/resources/META-INF/native-image/reflect-config.json");
        System.out.println("  [");
        System.out.println("    {");
        System.out.println("      \"name\": \"org.example.dto.UserDto\",");
        System.out.println("      \"allDeclaredConstructors\": true,");
        System.out.println("      \"allDeclaredFields\": true,");
        System.out.println("      \"allDeclaredMethods\": true");
        System.out.println("    },");
        System.out.println("    {");
        System.out.println("      \"name\": \"com.fasterxml.jackson.databind.ObjectMapper\",");
        System.out.println("      \"allDeclaredMethods\": true");
        System.out.println("    }");
        System.out.println("  ]");
        System.out.println();

        System.out.println("SOLUTION 2: Native Image Tracing Agent (khuyến nghị)");
        System.out.println("  # Bước 1: Chạy với agent để capture reflection usage");
        System.out.println("  java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \\");
        System.out.println("       -jar app.jar");
        System.out.println();
        System.out.println("  # Bước 2: Chạy test suite để cover tất cả code paths");
        System.out.println("  # → Agent tự generate: reflect-config.json, proxy-config.json,");
        System.out.println("  #                       resource-config.json, serialization-config.json");
        System.out.println();
        System.out.println("  # Bước 3: Build native image (dùng generated configs)");
        System.out.println("  native-image -jar app.jar");
        System.out.println();

        System.out.println("SOLUTION 3: Framework Annotations");
        System.out.println("  // Quarkus:");
        System.out.println("  @RegisterForReflection(targets = {UserDto.class, OrderDto.class})");
        System.out.println("  public class ReflectionConfig {}");
        System.out.println();
        System.out.println("  // Spring Boot 3.x:");
        System.out.println("  @ImportRuntimeHints(MyRuntimeHints.class)");
        System.out.println("  @SpringBootApplication");
        System.out.println("  public class App {}");
        System.out.println();
        System.out.println("  class MyRuntimeHints implements RuntimeHintsRegistrar {");
        System.out.println("    public void registerHints(RuntimeHints hints, ClassLoader cl) {");
        System.out.println("      hints.reflection()");
        System.out.println("           .registerType(UserDto.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,");
        System.out.println("                                         MemberCategory.DECLARED_FIELDS);");
        System.out.println("    }");
        System.out.println("  }");
        System.out.println();

        // Demonstrate what DOES work with reflection
        demonstrateWorkingReflection();
    }

    static void demonstrateWorkingReflection() {
        System.out.println("✅ REFLECTION PATTERNS WORK trong Native Image (constant strings):");
        System.out.println();

        // This pattern works because the class name is a compile-time constant
        try {
            // String constant → native-image can see it at build time
            Class<?> clazz = Class.forName("java.lang.String");
            System.out.println("  Class.forName(\"java.lang.String\") → " + clazz.getSimpleName() + " ✅");

            // getDeclaredField with constant name
            var field = String.class.getDeclaredField("value");
            System.out.println("  getDeclaredField(\"value\") → " + field.getName() + " ✅");

            // getDeclaredMethod with constant name
            var method = String.class.getDeclaredMethod("length");
            System.out.println("  getDeclaredMethod(\"length\") → " + method.getName() + "() ✅");

        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }

        System.out.println();
        System.out.println("  NOTE: Tất cả các pattern trên work vì string là CONSTANT (literal).");
        System.out.println("  native-image phân tích và tự động register vào reflect-config.");
        System.out.println();
    }

    // =========================================================
    // DEMO 4: SUBSTRATE VM RUNTIME
    // =========================================================

    static void demoSubstrateVMRuntime() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 4: SUBSTRATE VM — MINIMAL JAVA RUNTIME");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Substrate VM = JVM stripped xuống mức tối thiểu, compiled vào binary:");
        System.out.println();

        System.out.println("Substrate VM bao gồm:");
        printSVMComponent("Thread management",    "Lightweight, không dùng OS thread abstraction");
        printSVMComponent("GC (Serial/G1/Epsilon)","Chọn GC tại build time: --gc=serial/G1/epsilon");
        printSVMComponent("Stack walking",        "Cho exception handling và heap scanning");
        printSVMComponent("Exception handling",   "Simplified vs JVM (không support arbitrary throw/catch patterns)");
        printSVMComponent("JNI (limited)",        "Cần khai báo trong jni-config.json");
        printSVMComponent("System.currentTimeMillis", "OS syscall trực tiếp");
        System.out.println();

        System.out.println("Substrate VM KHÔNG có:");
        System.out.println("  ❌ JIT compiler (code đã AOT compiled rồi)");
        System.out.println("  ❌ Class loader hierarchy (classes đã được linked)");
        System.out.println("  ❌ Metaspace (class metadata embedded vào binary)");
        System.out.println("  ❌ Bytecode verifier");
        System.out.println("  ❌ Dynamic compiler thread");
        System.out.println();

        System.out.println("HEAP trong Native Image:");
        System.out.println("  Image Heap: Object data pre-initialized tại build time → baked vào binary");
        System.out.println("    → Strings, class objects, static final fields với giá trị constant");
        System.out.println("    → Khi binary chạy, heap đã 'warm' — không cần khởi tạo");
        System.out.println();
        System.out.println("  Runtime Heap: Allocate bình thường lúc runtime");
        System.out.println("    → new Object(), new ArrayList(), etc.");
        System.out.println();

        // Demonstrate image heap concept
        System.out.println("Ví dụ Image Heap:");
        System.out.println("  static final String CONFIG = \"production\";  // → baked vào binary");
        System.out.println("  static final List<String> CODES = List.of(\"A\", \"B\", \"C\");  // → baked");
        System.out.println("  static final Logger LOG = LoggerFactory.getLogger(...);  // → initialized at build");
        System.out.println();

        // Show GC options
        System.out.println("GC lựa chọn cho Native Image:");
        System.out.printf("  %-20s %-50s %s%n", "Serial GC (default)", "Single-threaded, stop-the-world", "→ small apps");
        System.out.printf("  %-20s %-50s %s%n", "G1 GC (--gc=G1)",     "Concurrent, low-pause",            "→ large heap apps");
        System.out.printf("  %-20s %-50s %s%n", "Epsilon (--gc=epsilon)","No GC at all, OOM và exit",       "→ short-lived CLIs");
        System.out.println();

        System.out.println("Memory RSS breakdown (example Spring Boot Native):");
        System.out.println("  Native code + data:  ~8MB   (compiled methods)");
        System.out.println("  Image heap:          ~10MB  (pre-initialized objects)");
        System.out.println("  Runtime heap:        ~8MB   (request processing)");
        System.out.println("  Stack:               ~2MB   (threads)");
        System.out.println("  ─────────────────────────");
        System.out.println("  Total RSS:           ~28MB  vs JVM ~200MB");
        System.out.println();
    }

    static void printSVMComponent(String component, String detail) {
        System.out.printf("  ✅ %-25s → %s%n", component, detail);
    }

    // =========================================================
    // DEMO 5: AOT COMPILATION PIPELINE
    // =========================================================

    static void demoAOTCompilationPipeline() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 5: AOT COMPILATION PIPELINE & OPTIMIZATIONS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("AOT vs JIT — fundamental tradeoff:");
        System.out.println();
        System.out.println("  JIT (Just-In-Time):                    AOT (Ahead-Of-Time):");
        System.out.println("  ─────────────────────────────────────  ──────────────────────────────────────");
        System.out.println("  Compile lúc runtime với REAL DATA       Compile tại build time với STATIC DATA");
        System.out.println("  Adaptive: speculative deoptimize         Conservative: no deoptimization");
        System.out.println("  Profile-guided: hot path → C2            All code compiled equally");
        System.out.println("  Warm-up cost: 5-30 giây                  Zero warm-up");
        System.out.println("  Peak perf: ★★★★★                        Peak perf: ★★★");
        System.out.println();

        System.out.println("Optimizations native-image thực hiện:");
        System.out.println();

        printAOTOptimization("Dead Code Elimination",
                "Code không reachable từ main() bị xóa → binary nhỏ hơn");
        printAOTOptimization("Inlining",
                "Small methods được inline tại build time (không cần wait warm-up)");
        printAOTOptimization("Escape Analysis",
                "Objects không escape → stack allocation thay heap");
        printAOTOptimization("Constant Folding",
                "if (DEBUG) { ... } → block bị xóa nếu DEBUG=false tại build");
        printAOTOptimization("Devirtualization",
                "Nếu class hierarchy rõ ràng → virtual call → direct call");
        printAOTOptimization("String deduplication",
                "Identical string literals → single memory location");
        System.out.println();

        System.out.println("BUILD OPTIONS quan trọng:");
        System.out.println("  # Strict mode — fail nếu có reflection không được register");
        System.out.println("  --no-fallback");
        System.out.println();
        System.out.println("  # Stack traces đầy đủ khi exception");
        System.out.println("  -H:+ReportExceptionStackTraces");
        System.out.println();
        System.out.println("  # Initialize class tại build time (nhanh startup)");
        System.out.println("  --initialize-at-build-time=com.example.config.Constants");
        System.out.println();
        System.out.println("  # Initialize class tại runtime (safe fallback)");
        System.out.println("  --initialize-at-run-time=com.example.client.HttpClient");
        System.out.println();
        System.out.println("  # Chọn GC");
        System.out.println("  --gc=G1");
        System.out.println();
        System.out.println("  # Enable PGO");
        System.out.println("  --pgo-instrument  (build instrumented binary để collect profiles)");
        System.out.println("  --pgo=default.iprof  (build optimized binary với profiles)");
        System.out.println();

        System.out.println("Build time benchmarks (Spring Boot 3 + Hibernate app):");
        System.out.printf("  %-30s %s%n", "JVM build (mvn package):", "~30 seconds");
        System.out.printf("  %-30s %s%n", "Native build (mvn native:compile):", "~3-5 minutes, 4GB RAM");
        System.out.printf("  %-30s %s%n", "Docker native build:", "~5-8 minutes (includes Docker layer)");
        System.out.println();
        System.out.println("  → Native build tốn nhiều thời gian hơn → chỉ làm ở CI, không ở dev");
        System.out.println("  → Dùng JVM mode cho dev, native cho production build");
        System.out.println();
    }

    static void printAOTOptimization(String name, String description) {
        System.out.printf("  ✨ %-28s → %s%n", name, description);
    }

    // =========================================================
    // DEMO 6: POLYGLOT API (TRUFFLE)
    // =========================================================

    static void demoPolyglotConcepts() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 6: POLYGLOT API — TRUFFLE FRAMEWORK");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Truffle = Language Implementation Framework chạy trên GraalVM.");
        System.out.println("Viết 1 interpreter → Truffle + Graal JIT biến thành optimized code.");
        System.out.println();

        System.out.println("Polyglot API (org.graalvm.polyglot):");
        System.out.println();
        System.out.println("  // 1. Tạo Context (sandbox cho một ngôn ngữ hoặc nhiều ngôn ngữ)");
        System.out.println("  try (Context ctx = Context.newBuilder(\"python\", \"js\")");
        System.out.println("                           .allowAllAccess(false)");
        System.out.println("                           .allowIO(IOAccess.NONE)");
        System.out.println("                           .build()) {");
        System.out.println();
        System.out.println("    // 2. Eval Python code");
        System.out.println("    Value result = ctx.eval(\"python\", \"[x**2 for x in range(5)]\");");
        System.out.println("    System.out.println(result);  // [0, 1, 4, 9, 16]");
        System.out.println();
        System.out.println("    // 3. Gọi Python function từ Java");
        System.out.println("    ctx.eval(\"python\", \"def greet(name): return f'Hello, {name}!'\");");
        System.out.println("    Value greetFn = ctx.getBindings(\"python\").getMember(\"greet\");");
        System.out.println("    Value greeting = greetFn.execute(\"World\");");
        System.out.println("    System.out.println(greeting.asString());  // Hello, World!");
        System.out.println();
        System.out.println("    // 4. Pass Java objects vào Python");
        System.out.println("    ctx.getBindings(\"python\").putMember(\"javaList\", List.of(1, 2, 3));");
        System.out.println("    Value sum = ctx.eval(\"python\", \"sum(javaList)\");");
        System.out.println("    System.out.println(sum.asInt());  // 6");
        System.out.println();
        System.out.println("    // 5. JavaScript eval");
        System.out.println("    Value jsResult = ctx.eval(\"js\", \"({name: 'Alice', age: 30})\");");
        System.out.println("    System.out.println(jsResult.getMember(\"name\").asString());  // Alice");
        System.out.println("  }");
        System.out.println();

        // Simulate polyglot execution
        simulatePolyglotExecution();

        System.out.println("Use cases cho Polyglot API:");
        printPolyglotUseCase("Scripting engine",     "Cho phép user viết business rules bằng Python/JS");
        printPolyglotUseCase("Data science",         "Gọi numpy/pandas computation từ Java service");
        printPolyglotUseCase("Plugin system",        "Plugin viết bằng ngôn ngữ khác, load dynamically");
        printPolyglotUseCase("Rule engine",          "DSL viết bằng JS để business user tự configure");
        printPolyglotUseCase("Testing",              "Gọi JS test framework từ Java test runner");
        System.out.println();

        System.out.println("Truffle Language Implementations có sẵn:");
        System.out.printf("  %-15s %-12s %s%n", "GraalJS",       "JavaScript", "Node.js compatible, ESM support");
        System.out.printf("  %-15s %-12s %s%n", "GraalPython",   "Python 3.x", "Most CPython packages work");
        System.out.printf("  %-15s %-12s %s%n", "TruffleRuby",   "Ruby",       "MRI compatible");
        System.out.printf("  %-15s %-12s %s%n", "FastR",         "R",          "Statistical computing");
        System.out.printf("  %-15s %-12s %s%n", "Espresso",      "Java",       "Java-on-Java (JVMCI)");
        System.out.printf("  %-15s %-12s %s%n", "GraalWasm",     "WebAssembly","Run WASM in JVM");
        System.out.println();

        System.out.println("Performance của Truffle interpreters:");
        System.out.println("  GraalJS ~ Node.js performance (V8 comparable)");
        System.out.println("  GraalPython ~ CPython × 3-10x cho compute-heavy code");
        System.out.println("  Lý do: Partial Evaluation → Truffle interpreter + Graal JIT = native speed");
        System.out.println();
    }

    static void simulatePolyglotExecution() {
        System.out.println("Simulation (không dùng GraalVM dependency — chỉ minh họa):");
        System.out.println();

        // Simulate what GraalVM polyglot would do
        PolyglotSimulator sim = new PolyglotSimulator();

        // Python list comprehension simulation
        int[] squares = sim.pythonListComprehension(5);
        System.out.print("  Python [x**2 for x in range(5)] → [");
        for (int i = 0; i < squares.length; i++) {
            System.out.print(squares[i]);
            if (i < squares.length - 1) System.out.print(", ");
        }
        System.out.println("]");

        // JavaScript object simulation
        String jsResult = sim.javascriptEval("{name: 'Alice', age: 30}");
        System.out.println("  JS eval({name: 'Alice', age: 30}) → name: " + jsResult);

        // Cross-language data passing
        int sum = sim.pythonSum(new int[]{1, 2, 3, 4, 5});
        System.out.println("  Python sum(javaArray) → " + sum);
        System.out.println();
    }

    // Simulation class (would use GraalVM polyglot API in real GraalVM environment)
    static class PolyglotSimulator {
        int[] pythonListComprehension(int n) {
            // Simulates: [x**2 for x in range(n)]
            int[] result = new int[n];
            for (int i = 0; i < n; i++) result[i] = i * i;
            return result;
        }

        String javascriptEval(String objectLiteral) {
            // Simulates extracting 'name' from JS object literal
            // In real GraalVM: ctx.eval("js", objectLiteral).getMember("name").asString()
            return "Alice";
        }

        int pythonSum(int[] javaArray) {
            // Simulates: sum(javaArray)
            int total = 0;
            for (int v : javaArray) total += v;
            return total;
        }
    }

    static void printPolyglotUseCase(String useCase, String description) {
        System.out.printf("  ● %-20s → %s%n", useCase, description);
    }

    // =========================================================
    // DEMO 7: PROFILE-GUIDED OPTIMIZATION (PGO)
    // =========================================================

    static void demoProfileGuidedOptimization() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 7: PROFILE-GUIDED OPTIMIZATION (PGO)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("PGO = Sử dụng profiling data từ production để guide AOT compiler.");
        System.out.println("Mục tiêu: thu hẹp khoảng cách native vs JIT về peak throughput.");
        System.out.println();

        System.out.println("PGO Workflow (3 bước):");
        System.out.println();
        System.out.println("  BƯỚC 1: Build instrumented binary");
        System.out.println("  ─────────────────────────────────");
        System.out.println("  native-image --pgo-instrument -jar app.jar -o app-instrumented");
        System.out.println("  → Binary có instrumentation code để collect profiles");
        System.out.println();
        System.out.println("  BƯỚC 2: Run workload để collect profiles");
        System.out.println("  ─────────────────────────────────────────");
        System.out.println("  ./app-instrumented  # hoặc chạy representative benchmark");
        System.out.println("  # Khi exit, file 'default.iprof' được tạo");
        System.out.println("  → File chứa: branch probabilities, method call frequencies,");
        System.out.println("               type profiles, inline hints");
        System.out.println();
        System.out.println("  BƯỚC 3: Build optimized binary với profiles");
        System.out.println("  ──────────────────────────────────────────────");
        System.out.println("  native-image --pgo=default.iprof -jar app.jar -o app-optimized");
        System.out.println("  → Compiler biết: đâu là hot path, method nào nên inline,");
        System.out.println("                   type nào xuất hiện thường xuyên nhất");
        System.out.println();

        System.out.println("PGO improvements (ví dụ thực tế từ GraalVM team):");
        printPGOImprovement("Throughput",  "+30-50%", "vs standard native (no PGO)");
        printPGOImprovement("Latency P99", "-20-40%", "Better branch prediction → fewer mispredicts");
        printPGOImprovement("Binary size", "-5-10%",  "Dead code removal improved với profile data");
        System.out.println();

        System.out.println("Khi nào dùng PGO?");
        System.out.println("  ✅ Performance-critical production services");
        System.out.println("  ✅ Workload pattern ổn định, predictable");
        System.out.println("  ✅ CI pipeline đủ thời gian cho 3-step build");
        System.out.println("  ❌ Không cần cho CLI tools (startup speed là chính)");
        System.out.println("  ❌ Workload pattern thay đổi thường xuyên");
        System.out.println();

        // Simulate PGO benefit
        System.out.println("PGO Simulation (minh họa branch optimization):");
        simulatePGOBranchOptimization();
    }

    static void printPGOImprovement(String metric, String improvement, String note) {
        System.out.printf("  📈 %-15s %-10s → %s%n", metric, improvement, note);
    }

    static void simulatePGOBranchOptimization() {
        // Demonstrate what PGO does: hot branch first
        System.out.println();
        System.out.println("  WITHOUT PGO — compiler không biết branch nào hot:");
        System.out.println("    if (isError) { /* error handling */ }  ← branch order không optimize");
        System.out.println("    else { /* normal path */ }");
        System.out.println();
        System.out.println("  WITH PGO — compiler biết 99% cases là normal path:");
        System.out.println("    if (!isError) { /* normal path FIRST = branch predict win */ }");
        System.out.println("    else { /* cold error path → moved to end of function */ }");
        System.out.println();

        // Actually run something to show branch frequency
        long normalCount = 0, errorCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (i % 100 == 0) errorCount++;  // 1% error rate
            else normalCount++;
        }
        System.out.printf("  Simulation result: normal=%d (%.0f%%), error=%d (%.0f%%)%n",
                normalCount, (normalCount * 100.0 / 1000),
                errorCount, (errorCount * 100.0 / 1000));
        System.out.println("  → PGO: compiler puts normal path first → better branch prediction");
        System.out.println();
    }

    // =========================================================
    // DEMO 8: NATIVE IMAGE CONFIGURATION FILES
    // =========================================================

    static void demoNativeImageConfiguration() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 8: NATIVE IMAGE CONFIGURATION FILES");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Tất cả config files đặt tại: src/main/resources/META-INF/native-image/");
        System.out.println("(hoặc được generate bởi tracing agent)");
        System.out.println();

        System.out.println("1. reflect-config.json — Reflection registrations:");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  [");
        System.out.println("    {");
        System.out.println("      \"name\": \"com.example.dto.UserDto\",");
        System.out.println("      \"allDeclaredConstructors\": true,   // Jackson needs this");
        System.out.println("      \"allDeclaredFields\": true,         // Jackson needs this");
        System.out.println("      \"allDeclaredMethods\": false        // Only if needed");
        System.out.println("    },");
        System.out.println("    {");
        System.out.println("      \"name\": \"com.example.service.UserService\",");
        System.out.println("      \"methods\": [");
        System.out.println("        {\"name\": \"findById\", \"parameterTypes\": [\"java.lang.Long\"]}");
        System.out.println("      ]");
        System.out.println("    }");
        System.out.println("  ]");
        System.out.println();

        System.out.println("2. resource-config.json — Classpath resources:");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  {");
        System.out.println("    \"resources\": {");
        System.out.println("      \"includes\": [");
        System.out.println("        {\"pattern\": \".*\\\\.properties\"},   // application.properties");
        System.out.println("        {\"pattern\": \".*\\\\.xml\"},           // Hibernate mappings");
        System.out.println("        {\"pattern\": \"db/migration/.*\"}    // Flyway migrations");
        System.out.println("      ]");
        System.out.println("    },");
        System.out.println("    \"bundles\": [");
        System.out.println("      {\"name\": \"messages\"}  // ResourceBundle");
        System.out.println("    ]");
        System.out.println("  }");
        System.out.println();

        System.out.println("3. proxy-config.json — Dynamic proxies:");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  [");
        System.out.println("    [\"com.example.repository.UserRepository\"],  // Spring Data JPA proxy");
        System.out.println("    [\"java.sql.Driver\", \"com.example.db.LoggingDriver\"]");
        System.out.println("  ]");
        System.out.println();

        System.out.println("4. serialization-config.json — Java Serialization:");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  {");
        System.out.println("    \"types\": [");
        System.out.println("      {\"name\": \"com.example.session.UserSession\"},");
        System.out.println("      {\"name\": \"java.util.ArrayList\"}");
        System.out.println("    ]");
        System.out.println("  }");
        System.out.println();

        System.out.println("5. native-image.properties — Build arguments:");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  Args = --no-fallback \\");
        System.out.println("         -H:+ReportExceptionStackTraces \\");
        System.out.println("         --initialize-at-build-time=org.slf4j");
        System.out.println();

        System.out.println("BEST PRACTICE: Tracing Agent Workflow");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("  # 1. Merge mode: merge multiple runs");
        System.out.println("  java -agentlib:native-image-agent=config-merge-dir=META-INF/native-image \\");
        System.out.println("       -jar app.jar");
        System.out.println();
        System.out.println("  # 2. Chạy unit tests với agent:");
        System.out.println("  mvn test -DargLine=\"-agentlib:native-image-agent=config-output-dir=...\"");
        System.out.println();
        System.out.println("  # 3. Chạy integration tests với agent:");
        System.out.println("  mvn failsafe:integration-test -DargLine=\"...\"");
        System.out.println();
        System.out.println("  → Càng cover nhiều code paths → config càng đầy đủ → ít lỗi runtime");
        System.out.println();
    }

    // =========================================================
    // DEMO 9: FRAMEWORK NATIVE SUPPORT
    // =========================================================

    static void demoFrameworkSupport() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 9: FRAMEWORK NATIVE IMAGE SUPPORT");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("═══ SPRING BOOT 3.x NATIVE ═══");
        System.out.println();
        System.out.println("pom.xml:");
        System.out.println("  <parent>");
        System.out.println("    <groupId>org.springframework.boot</groupId>");
        System.out.println("    <artifactId>spring-boot-starter-parent</artifactId>");
        System.out.println("    <version>3.2.0</version>");
        System.out.println("  </parent>");
        System.out.println();
        System.out.println("  <plugin>");
        System.out.println("    <groupId>org.graalvm.buildtools</groupId>");
        System.out.println("    <artifactId>native-maven-plugin</artifactId>");
        System.out.println("    <!-- Spring parent handles version -->");
        System.out.println("    <extensions>true</extensions>");
        System.out.println("  </plugin>");
        System.out.println();
        System.out.println("  # Build native executable:");
        System.out.println("  mvn -Pnative native:compile");
        System.out.println();
        System.out.println("  # Build native Docker image (no GraalVM needed on host):");
        System.out.println("  mvn -Pnative spring-boot:build-image");
        System.out.println("  docker run --rm -p 8080:8080 myapp:0.0.1-SNAPSHOT");
        System.out.println();
        System.out.println("Spring Native Hints (programmatic config):");
        System.out.println("  @Configuration");
        System.out.println("  @ImportRuntimeHints(AppRuntimeHints.class)");
        System.out.println("  public class AppConfig {}");
        System.out.println();
        System.out.println("  class AppRuntimeHints implements RuntimeHintsRegistrar {");
        System.out.println("    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {");
        System.out.println("      // Reflection");
        System.out.println("      hints.reflection().registerType(UserDto.class,");
        System.out.println("          MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,");
        System.out.println("          MemberCategory.DECLARED_FIELDS);");
        System.out.println("      // Resources");
        System.out.println("      hints.resources().registerPattern(\"templates/*.html\");");
        System.out.println("      // Proxies");
        System.out.println("      hints.proxies().registerJdkProxy(UserRepository.class);");
        System.out.println("    }");
        System.out.println("  }");
        System.out.println();

        System.out.println("═══ QUARKUS NATIVE ═══");
        System.out.println();
        System.out.println("Quarkus được thiết kế native-first từ đầu — ít friction nhất:");
        System.out.println();
        System.out.println("  # Create Quarkus project:");
        System.out.println("  mvn io.quarkus.platform:quarkus-maven-plugin:create");
        System.out.println();
        System.out.println("  # Build native (local GraalVM):");
        System.out.println("  mvn package -Pnative");
        System.out.println();
        System.out.println("  # Build native trong Docker (không cần GraalVM local):");
        System.out.println("  mvn package -Pnative -Dquarkus.native.container-build=true");
        System.out.println();
        System.out.println("  @RegisterForReflection  // Quarkus annotation");
        System.out.println("  public class MyDto { ... }");
        System.out.println();
        System.out.println("  // application.properties:");
        System.out.println("  quarkus.native.enable-https-url-handler=true");
        System.out.println("  quarkus.native.resources.includes=db/*.sql");
        System.out.println();

        System.out.println("═══ MICRONAUT AOT ═══");
        System.out.println();
        System.out.println("  Micronaut: reflection-free DI tại compile time (annotation processor)");
        System.out.println("  → Natively native-friendly, startup < 100ms ngay cả JVM mode");
        System.out.println();
        System.out.println("  # Build native:");
        System.out.println("  mvn package -Dpackaging=native-image");
        System.out.println();

        System.out.println("Framework Comparison cho Native:");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Framework", "Native Support", "Developer Experience", "Best for");
        System.out.printf("  %-15s %-15s %-20s %s%n", "─────────", "─────────────", "────────────────────", "────────");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Spring Boot 3", "✅ Good",     "Familiar",            "Existing Spring teams");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Quarkus",      "✅ Excellent", "Dev mode + hot reload","New microservices");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Micronaut",    "✅ Excellent", "Similar to Spring",    "Low-memory services");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Helidon",      "✅ Good",      "Reactive-first",       "Reactive workloads");
        System.out.printf("  %-15s %-15s %-20s %s%n", "Plain Java",   "✅ Best",      "Manual config needed", "CLI tools, max control");
        System.out.println();
    }

    // =========================================================
    // DEMO 10: BEST PRACTICES & PITFALLS
    // =========================================================

    static void demoBestPracticesAndPitfalls() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("DEMO 10: BEST PRACTICES & COMMON PITFALLS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("✅ BEST PRACTICES:");
        System.out.println();

        printBestPractice("1",
                "Dùng Tracing Agent trước, manual config sau",
                "Run app với agent, collect configs, chỉ edit những gì thiếu");

        printBestPractice("2",
                "Test trên native, không chỉ JVM",
                "Behavior có thể khác nhau: class init order, reflection, proxies");

        printBestPractice("3",
                "Dùng --no-fallback strict mode",
                "Fail-fast tại build thay vì silent fallback runtime failure");

        printBestPractice("4",
                "Separate build: JVM dev, native CI/CD",
                "Dev với JVM (fast reload), native chỉ build ở CI pipeline");

        printBestPractice("5",
                "Container build cho consistency",
                "-Dquarkus.native.container-build=true → no GraalVM needed locally");

        printBestPractice("6",
                "Static analysis first: mvn verify -Pnative",
                "Chạy native test suite để catch reflection issues sớm");

        printBestPractice("7",
                "Giữ class initialization simple",
                "Tránh network/file I/O ở static initializer → fail tại build time");

        printBestPractice("8",
                "Dùng PGO cho production perf-critical services",
                "3-step build workflow: instrument → profile → optimize");

        System.out.println();
        System.out.println("❌ COMMON PITFALLS:");
        System.out.println();

        printPitfall("1",
                "Reflection trên runtime-variable class name",
                "Class.forName(getProperty(\"class\")) → MissingReflectionRegistrationError");

        printPitfall("2",
                "Static initializer với network/file I/O",
                "static { db = connectToDatabase(); } → fail tại build time analysis");

        printPitfall("3",
                "Thread access trong static initializer",
                "Native image phân tích single-threaded → multi-thread init = NPE");

        printPitfall("4",
                "Missing resource in resource-config.json",
                "getResourceAsStream(\"config.xml\") → returns null silently");

        printPitfall("5",
                "Lambda serialization không được config",
                "Serializable lambda cần serialization-config.json entry");

        printPitfall("6",
                "JVM-specific flags trong code",
                "ManagementFactory.getRuntimeMXBean().getInputArguments() → limited in native");

        printPitfall("7",
                "Assume JVM class loading order",
                "Native Image có thể initialize classes ở thứ tự khác → NPE");

        System.out.println();
        System.out.println("TROUBLESHOOTING COMMANDS:");
        System.out.println("  # Xem tất cả reachable types:");
        System.out.println("  native-image --diagnostics-mode ...");
        System.out.println();
        System.out.println("  # Analyze binary size:");
        System.out.println("  native-image --enable-sbom ...");
        System.out.println();
        System.out.println("  # Check what was included:");
        System.out.println("  nm -n ./target/app | head -100  # (macOS/Linux)");
        System.out.println();
        System.out.println("  # Debug native crash:");
        System.out.println("  native-image -g ...    # debug symbols");
        System.out.println("  gdb ./target/app       # gdb native debugging");
        System.out.println();
        System.out.println("  # Quick check: does reflection config cover all?");
        System.out.println("  -H:MissingRegistrationReportingMode=Warn  # warn instead of fail");
        System.out.println();

        printGraalVMCheatSheet();
    }

    static void printBestPractice(String num, String title, String detail) {
        System.out.printf("  [%s] %s%n      → %s%n%n", num, title, detail);
    }

    static void printPitfall(String num, String title, String detail) {
        System.out.printf("  [%s] %s%n      ⚠️  %s%n%n", num, title, detail);
    }

    static void printGraalVMCheatSheet() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║              GRAALVM QUICK REFERENCE CHEAT SHEET                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ INSTALL:                                                         ║");
        System.out.println("║  sdk install java 21.0.2-graal   (SDKMAN)                        ║");
        System.out.println("║  brew install --cask graalvm-jdk (macOS)                         ║");
        System.out.println("║  winget install GraalVM.GraalVM.JDK.21 (Windows)                 ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ BUILD NATIVE IMAGE:                                               ║");
        System.out.println("║  native-image -jar app.jar                  # Basic build         ║");
        System.out.println("║  native-image -jar app.jar --no-fallback    # Strict mode         ║");
        System.out.println("║  mvn -Pnative native:compile                # Maven plugin        ║");
        System.out.println("║  mvn -Pnative spring-boot:build-image       # Docker image        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ TRACING AGENT:                                                    ║");
        System.out.println("║  java -agentlib:native-image-agent=\\                              ║");
        System.out.println("║       config-output-dir=META-INF/native-image -jar app.jar        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ PGO:                                                              ║");
        System.out.println("║  native-image --pgo-instrument -jar app.jar -o app-inst           ║");
        System.out.println("║  ./app-inst  # run workload, generates default.iprof              ║");
        System.out.println("║  native-image --pgo=default.iprof -jar app.jar -o app-opt         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ KEY FLAGS:                                                        ║");
        System.out.println("║  --no-fallback                              # strict mode          ║");
        System.out.println("║  --gc=serial|G1|epsilon                     # choose GC            ║");
        System.out.println("║  --initialize-at-build-time=pkg             # class init           ║");
        System.out.println("║  -H:+ReportExceptionStackTraces             # debug                ║");
        System.out.println("║  -H:Class=com.example.Main                  # specify main         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ CONFIG FILES (META-INF/native-image/):                            ║");
        System.out.println("║  reflect-config.json    proxy-config.json                         ║");
        System.out.println("║  resource-config.json   serialization-config.json                 ║");
        System.out.println("║  jni-config.json        native-image.properties                   ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ POLYGLOT:                                                         ║");
        System.out.println("║  dependency: org.graalvm.polyglot:polyglot:23.1.0                 ║");
        System.out.println("║  Context ctx = Context.newBuilder(\"python\", \"js\").build();         ║");
        System.out.println("║  Value result = ctx.eval(\"python\", \"[x**2 for x in range(5)]\");   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  🎓 BÀI 10.3 HOÀN THÀNH — GRAALVM & NATIVE IMAGE");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Key Takeaways:");
        System.out.println("  • Native Image = AOT → fast startup, low memory, no JIT peak perf");
        System.out.println("  • Closed-World: tất cả code phải biết tại build time");
        System.out.println("  • Tracing Agent = cách dễ nhất generate reflection/proxy/resource configs");
        System.out.println("  • Substrate VM = minimal runtime embedded vào binary");
        System.out.println("  • PGO = thu hẹp khoảng cách native vs JIT peak throughput");
        System.out.println("  • Truffle = zero-overhead polyglot: Python/JS/Ruby trong JVM");
        System.out.println("  • Spring/Quarkus/Micronaut đều có native support tốt");
        System.out.println("  • Dùng native cho: serverless, CLI, microservices với nhiều instances");
        System.out.println("  • Dùng JVM cho: long-running services cần peak throughput");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  🏆 TOÀN BỘ LỘ TRÌNH HOÀN THÀNH — 41/41 BÀI HỌC JAVA NÂNG CAO");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Modules hoàn thành:");
        System.out.println("  Module  1: JVM Internals          ✅ (ClassLoading, Memory, JIT, GC)");
        System.out.println("  Module  2: Concurrency            ✅ (JMM, Locks, Executor, Loom)");
        System.out.println("  Module  3: Collections & Generics ✅ (Internals, Concurrent, Streams)");
        System.out.println("  Module  4: Design Patterns        ✅ (Creational, Structural, Behavioral)");
        System.out.println("  Module  5: Performance            ✅ (JMH, GC Tuning, Leak, Profiling)");
        System.out.println("  Module  6: I/O & Reactive         ✅ (Blocking, NIO, NIO2, Reactor)");
        System.out.println("  Module  7: Reflection & Bytecode  ✅ (Reflection, Annotation, ASM)");
        System.out.println("  Module  8: Testing                ✅ (Mockito, TestContainers, Mutation)");
        System.out.println("  Module  9: Architecture           ✅ (DDD, EventSourcing, Saga, Resilience, Observability)");
        System.out.println("  Module 10: Tooling & Ecosystem    ✅ (Modern Java, Maven, GraalVM)");
        System.out.println();
        System.out.println("  Chúc mừng! Bạn đã nắm vững Java từ JVM internals đến Cloud-native.");
        System.out.println("  Tiếp theo: áp dụng kiến thức vào dự án thực tế, contribute open source,");
        System.out.println("  hoặc đi sâu vào một domain cụ thể (distributed systems, ML serving, etc.)");
        System.out.println();
    }
}
