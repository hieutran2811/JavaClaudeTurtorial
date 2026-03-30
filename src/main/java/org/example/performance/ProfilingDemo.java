package org.example.performance;

import java.lang.management.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * =============================================================================
 * BÀI 5.4 — Profiling: async-profiler, JFR, Flame Graph & Hot Path Fix
 * =============================================================================
 *
 * Profiling = "Đo trước, optimize sau" — không đoán mò.
 * Quy trình: Observe → Measure → Identify bottleneck → Fix → Verify
 *
 * PROFILING TOOLS OVERVIEW:
 * ┌──────────────────┬─────────────────────────────────────────────────────────┐
 * │ Tool             │ Đặc điểm                                                │
 * ├──────────────────┼─────────────────────────────────────────────────────────┤
 * │ async-profiler   │ Low-overhead sampling, CPU+alloc+lock, flame graph      │
 * │ JFR              │ Built-in Java 11+, <1% overhead, production-safe        │
 * │ JMC              │ GUI viewer cho JFR recordings                           │
 * │ VisualVM         │ GUI profiling (sampling/instrumentation), heap analysis  │
 * │ JMH              │ Micro-benchmark (đo đúng, không bị JIT trick)           │
 * │ jstack           │ Thread dump — diagnose deadlock, blocking                │
 * │ jstat -gcutil    │ GC stats live                                           │
 * └──────────────────┴─────────────────────────────────────────────────────────┘
 *
 * PROFILING MODES:
 *   CPU Profiling    — tìm hàm nào chiếm nhiều CPU time nhất (hot methods)
 *   Allocation Prof  — tìm code nào allocate nhiều object nhất (GC pressure)
 *   Lock Profiling   — tìm contention điểm nào gây thread waiting
 *   Wall-clock Prof  — tổng thời gian (CPU + I/O + lock wait) — tốt cho latency
 *
 * FLAME GRAPH đọc như thế nào:
 *   • X-axis: % thời gian (KHÔNG phải timeline)
 *   • Y-axis: call stack (bottom = root, top = leaf = where time is spent)
 *   • Màu: không có ý nghĩa cụ thể (chỉ để phân biệt)
 *   • Frame rộng = nhiều time = hot path = nơi cần optimize
 *   • Đọc từ trên xuống: tìm frame rộng ở gần top → đó là bottleneck
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.performance.ProfilingDemo"
 */
public class ProfilingDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 5.4 — Profiling: JFR, Flame Graph & Hot Path Fix");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_profilingToolsOverview();
        demo2_jfrProgrammaticRecording();
        demo3_cpuHotPathSimulation();
        demo4_allocationHotPath();
        demo5_lockContentionProfiling();
        demo6_wallClockVsCpuTime();
        demo7_flameGraphInterpretation();
        demo8_hotPathBeforeAfterFix();
        demo9_profilingInProduction();
        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Profiling Tools Overview & Commands
    // =========================================================================
    static void demo1_profilingToolsOverview() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Profiling Tools: Commands & When to Use");
        System.out.println("─".repeat(70));

        System.out.println("""

            ═══════════════════════════════════════════════════════════════════
            TOOL 1: async-profiler (best-in-class, open source)
            ═══════════════════════════════════════════════════════════════════
            # Download: https://github.com/async-profiler/async-profiler

            # CPU profiling — 30 giây, output flame graph HTML
            ./asprof -e cpu -d 30 -f cpu-flame.html <pid>

            # Allocation profiling — tìm ai allocate nhiều nhất
            ./asprof -e alloc -d 30 -f alloc-flame.html <pid>

            # Lock profiling — tìm contention
            ./asprof -e lock -d 30 -f lock-flame.html <pid>

            # Wall-clock (includes I/O wait, lock wait)
            ./asprof -e wall -d 30 -t -f wall-flame.html <pid>

            # Output formats: html (interactive), jfr, collapsed, flamegraph
            ./asprof -e cpu -d 30 -f output.jfr <pid>   # JFR format for JMC

            ═══════════════════════════════════════════════════════════════════
            TOOL 2: JFR — Java Flight Recorder (built-in Java 11+)
            ═══════════════════════════════════════════════════════════════════
            # Start recording từ command line:
            jcmd <pid> JFR.start duration=60s filename=recording.jfr

            # Hoặc JVM flag để auto-record:
            java -XX:StartFlightRecording=duration=60s,filename=app.jfr,\\
                 settings=profile -jar app.jar

            # Dump recording đang chạy:
            jcmd <pid> JFR.dump filename=snapshot.jfr

            # Stop recording:
            jcmd <pid> JFR.stop name=1

            # Analyze với JMC (Java Mission Control):
            #   Download: https://adoptium.net/jmc/
            #   Open recording.jfr → Method Profiling → Flame Graph view

            # Analyze với command line (JFR Summary):
            jfr print --events jdk.CPULoad,jdk.GarbageCollection recording.jfr

            ═══════════════════════════════════════════════════════════════════
            TOOL 3: jstack — Thread Dump Analysis
            ═══════════════════════════════════════════════════════════════════
            # Single thread dump:
            jstack <pid> > thread-dump.txt

            # Take 3 dumps, 5 seconds apart (find persisting blocked threads):
            for i in 1 2 3; do jstack <pid> >> thread-dump.txt; sleep 5; done

            # Analyze: tìm threads ở state BLOCKED, WAITING lâu
            grep -A 5 "BLOCKED\\|WAITING" thread-dump.txt

            ═══════════════════════════════════════════════════════════════════
            TOOL 4: jstat — Live GC Stats
            ═══════════════════════════════════════════════════════════════════
            # Print GC utilization mỗi 1 giây:
            jstat -gcutil <pid> 1000

            # Output: S0%  S1%  E%   O%   M%   YGC  YGCT  FGC  FGCT  GCT
            # S0/S1 = Survivor, E = Eden, O = Old, M = Metaspace
            # YGC = Young GC count, FGC = Full GC count

            ═══════════════════════════════════════════════════════════════════
            TOOL 5: jmap — Heap Info
            ═══════════════════════════════════════════════════════════════════
            # Object histogram (top classes by memory):
            jmap -histo:live <pid> | head -30

            # Full heap dump:
            jmap -dump:live,format=b,file=heap.hprof <pid>
            """);
    }

    // =========================================================================
    // DEMO 2 — JFR Programmatic Recording (Java 14+)
    // =========================================================================
    static void demo2_jfrProgrammaticRecording() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 2 — JFR Programmatic Recording (Java 14+ API)");
        System.out.println("─".repeat(70));

        System.out.println("""

            JFR (Java Flight Recorder) — tích hợp trong JDK, overhead <1%:
              • Thu thập: CPU usage, GC events, method profiling, thread info
              • Profile cả JVM internal (GC, JIT, class loading) — không chỉ app code
              • Production-safe: thiết kế để chạy LIÊN TỤC ở production

            JFR Event API — tạo custom events:
            """);

        // Custom JFR Event (Java 14+)
        // Sử dụng jdk.jfr.Event nếu available, fallback nếu không
        try {
            Class<?> eventClass = Class.forName("jdk.jfr.Event");
            System.out.println("  JFR API available (Java 9+)");
            System.out.println("""
                  Custom JFR Event pattern:
                    @Label("Database Query")
                    @Category({"Database", "SQL"})
                    @StackTrace(false)                          // tắt stack trace → lower overhead
                    public class QueryEvent extends Event {
                        @Label("SQL") public String sql;
                        @Label("Duration MS") public long durationMs;
                        @Label("Row Count") public int rowCount;
                    }

                    // Usage in code:
                    QueryEvent event = new QueryEvent();
                    event.begin();
                    try {
                        ResultSet rs = stmt.executeQuery(sql);
                        event.sql = sql;
                        event.rowCount = rs.getFetchSize();
                    } finally {
                        event.commit();   // only recorded if event.isEnabled()
                    }

                  → Custom events xuất hiện trong JMC → correlate với GC, CPU spike
                  → Dùng để track slow queries, cache misses, circuit breaker trips
                """);
        } catch (ClassNotFoundException e) {
            System.out.println("  JFR API: requires JDK (not JRE). Showing API reference only.");
        }

        System.out.println("""
              JFR RECORDING PROFILES:
              ┌────────────────┬──────────────────────────────────────────────┐
              │ Profile        │ Use Case                                      │
              ├────────────────┼──────────────────────────────────────────────┤
              │ default        │ Low overhead, production monitoring           │
              │ profile        │ Higher detail, short-term profiling (staging) │
              │ custom .jfc    │ Fine-grained control over which events to log  │
              └────────────────┴──────────────────────────────────────────────┘

              CONTINUOUS JFR IN PRODUCTION (best practice):
                java -XX:StartFlightRecording=\\
                     disk=true,\\
                     maxage=1h,\\
                     maxsize=500m,\\
                     filename=/logs/jfr/,\\
                     settings=default\\
                     -jar app.jar
                → Ghi vào disk liên tục, giữ 1 giờ cuối
                → Khi incident xảy ra: jcmd <pid> JFR.dump → có recording ngay
            """);
    }

    // =========================================================================
    // DEMO 3 — CPU Hot Path Simulation & Identification
    // =========================================================================

    // Simulate a CPU-intensive workload with an intentional bottleneck
    static long inefficientSort(List<Integer> list) {
        // Intentionally bad: sort inside loop, creating new ArrayList each time
        long checksum = 0;
        for (int i = 0; i < 100; i++) {
            List<Integer> copy = new ArrayList<>(list);      // allocation hot path
            Collections.sort(copy);                           // CPU hot path
            checksum += copy.get(copy.size() / 2);           // median
        }
        return checksum;
    }

    static long efficientSort(List<Integer> sortedList) {
        // Fix: sort once, reuse
        long checksum = 0;
        int median = sortedList.get(sortedList.size() / 2);
        for (int i = 0; i < 100; i++) {
            checksum += median;    // O(1) per iteration
        }
        return checksum;
    }

    static void demo3_cpuHotPathSimulation() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — CPU Hot Path: Identify & Fix");
        System.out.println("─".repeat(70));

        System.out.println("""

            HOW TO READ CPU FLAME GRAPH:
              • Profile: tìm frame RỘNG NHẤT gần đỉnh stack
              • Frame rộng = nhiều CPU time = hot path
              • Click frame → filter view → xem callers
              • "Frozen" top = CPU spin (tight loop, serialization, regex)

            Simulating workload với intentional bottleneck:
            """);

        Random rng = new Random(42);
        List<Integer> data = IntStream.range(0, 1000)
            .map(i -> rng.nextInt(10000))
            .boxed()
            .collect(Collectors.toList());
        List<Integer> sortedData = new ArrayList<>(data);
        Collections.sort(sortedData);

        // Warm up
        inefficientSort(data);
        efficientSort(sortedData);

        // Measure BEFORE fix
        int RUNS = 200;
        long start = System.nanoTime();
        long checksum1 = 0;
        for (int i = 0; i < RUNS; i++) checksum1 += inefficientSort(data);
        long beforeMs = (System.nanoTime() - start) / 1_000_000;

        // Measure AFTER fix
        start = System.nanoTime();
        long checksum2 = 0;
        for (int i = 0; i < RUNS; i++) checksum2 += efficientSort(sortedData);
        long afterMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("  BEFORE fix (sort-in-loop):    %,d ms (checksum=%d)%n", beforeMs, checksum1);
        System.out.printf("  AFTER fix  (sort-once reuse): %,d ms (checksum=%d)%n", afterMs, checksum2);
        System.out.printf("  Speedup: %.1fx%n", (double) beforeMs / Math.max(afterMs, 1));

        System.out.println("""

            FLAME GRAPH WOULD SHOW:
              BEFORE:
                main → inefficientSort [████████████████████] ← WIDE = hot
                  └─ ArrayList.sort [███████████] ← most CPU time
                  └─ new ArrayList  [████]        ← allocation

              AFTER:
                main → efficientSort [█] ← narrow = fast
                  └─ array access [░]    ← negligible

            CPU PROFILING TIPS:
              • Đừng profile với debugger attached (alters timing)
              • Profile với production-like data size
              • Run warm-up trước khi profile (JIT steady state)
              • async-profiler -i 1ms (interval) cho high-resolution sampling
            """);
    }

    // =========================================================================
    // DEMO 4 — Allocation Hot Path: Reduce GC Pressure
    // =========================================================================
    static void demo4_allocationHotPath() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — Allocation Hot Path: Reduce Object Creation");
        System.out.println("─".repeat(70));

        System.out.println("""

            ALLOCATION PROFILING với async-profiler:
              ./asprof -e alloc -d 30 -f alloc.html <pid>

            Flame graph sẽ hiển thị: ai đang tạo nhiều object nhất
            → Fix bằng cách: reuse, pool, value types, primitives

            COMMON ALLOCATION HOT PATHS:
            """);

        // Pattern 1: String concatenation in loop (creates many String objects)
        System.out.println("  Pattern 1: String concat vs StringBuilder");
        int N = 50_000;

        long start = System.nanoTime();
        String resultPlus = "";
        for (int i = 0; i < N; i++) {
            resultPlus += i; // creates N intermediate String objects → GC pressure
        }
        long plusMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        StringBuilder sb = new StringBuilder(N * 6);
        for (int i = 0; i < N; i++) sb.append(i);
        String resultSB = sb.toString();
        long sbMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("    String +   : %,d ms (created ~%,d temp objects)%n", plusMs, N);
        System.out.printf("    StringBuilder: %,d ms (1 backing array, amortized)%n", sbMs);
        System.out.printf("    Speedup: %.0fx%n", (double) plusMs / Math.max(sbMs, 1));

        // Pattern 2: Autoboxing in tight loop
        System.out.println("\n  Pattern 2: Autoboxing vs primitives");
        start = System.nanoTime();
        Long boxedSum = 0L;
        for (int i = 0; i < 1_000_000; i++) {
            boxedSum += i;  // unbox Long, add, box Long → 1M Long objects!
        }
        long boxedMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        long primitiveSum = 0L;
        for (int i = 0; i < 1_000_000; i++) {
            primitiveSum += i;  // no boxing, stack only
        }
        long primMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("    Long (boxed):    %,d ms%n", boxedMs);
        System.out.printf("    long (primitive): %,d ms%n", primMs);

        // Pattern 3: Object reuse via ThreadLocal pool
        System.out.println("\n  Pattern 3: ThreadLocal reuse vs new object per call");

        ThreadLocal<StringBuilder> tlSB = ThreadLocal.withInitial(() -> new StringBuilder(256));

        start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            StringBuilder reused = tlSB.get();
            reused.setLength(0);          // reset without new allocation
            reused.append("prefix-").append(i).append("-suffix");
            String s = reused.toString(); // only one allocation: final String
        }
        long reuseMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            String s = new StringBuilder(256)  // new SB per call
                .append("prefix-").append(i).append("-suffix")
                .toString();
        }
        long newPerCallMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("    New StringBuilder per call: %,d ms%n", newPerCallMs);
        System.out.printf("    ThreadLocal reuse:          %,d ms%n", reuseMs);

        System.out.println("""

            ALLOCATION REDUCTION STRATEGIES:
            ┌─────────────────────────────┬────────────────────────────────────┐
            │ Anti-pattern                │ Fix                                │
            ├─────────────────────────────┼────────────────────────────────────┤
            │ String + in loop            │ StringBuilder / StringJoiner       │
            │ Autoboxing Long/Integer     │ Use primitives long/int            │
            │ new ArrayList() per call    │ Clear and reuse via ThreadLocal    │
            │ new BigDecimal(string)      │ Precompute constants               │
            │ Stream.toList() per request │ Collect to reusable structure      │
            │ UUID.fromString() hot path  │ Cache parsed UUIDs                 │
            └─────────────────────────────┴────────────────────────────────────┘
            """);
    }

    // =========================================================================
    // DEMO 5 — Lock Contention Profiling
    // =========================================================================

    static final Object SHARED_LOCK = new Object();
    static long sharedCounter = 0;
    static final AtomicLong atomicCounter = new AtomicLong(0);
    static final LongAdder adderCounter = new LongAdder();

    static void demo5_lockContentionProfiling() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Lock Contention Profiling & Fix");
        System.out.println("─".repeat(70));

        System.out.println("""

            LOCK PROFILING với async-profiler:
              ./asprof -e lock -d 30 -f lock-flame.html <pid>

            Output shows:
              • Which locks are most contended
              • Which threads are waiting on which monitors
              • How long threads spend blocked (vs doing useful work)

            Simulating counter increment contention:
            """);

        int THREADS = 8;
        int OPS_PER_THREAD = 100_000;

        // Option A: synchronized (high contention)
        sharedCounter = 0;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(THREADS);

        long start = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    synchronized (SHARED_LOCK) { sharedCounter++; }
                }
                latch.countDown();
            });
        }
        latch.await();
        long syncMs = (System.nanoTime() - start) / 1_000_000;

        // Option B: AtomicLong (CAS — no lock)
        atomicCounter.set(0);
        CountDownLatch latch2 = new CountDownLatch(THREADS);
        start = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    atomicCounter.incrementAndGet();  // CAS — no mutex
                }
                latch2.countDown();
            });
        }
        latch2.await();
        long atomicMs = (System.nanoTime() - start) / 1_000_000;

        // Option C: LongAdder (best for high-contention increment)
        adderCounter.reset();
        CountDownLatch latch3 = new CountDownLatch(THREADS);
        start = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    adderCounter.increment();   // per-thread cell → near-zero contention
                }
                latch3.countDown();
            });
        }
        latch3.await();
        long adderMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();

        long total = (long) THREADS * OPS_PER_THREAD;
        System.out.printf("  %d threads × %,d ops = %,d total ops%n", THREADS, OPS_PER_THREAD, total);
        System.out.printf("  synchronized:  %,d ms  (counter=%,d)%n", syncMs,   sharedCounter);
        System.out.printf("  AtomicLong:    %,d ms  (counter=%,d)%n", atomicMs, atomicCounter.get());
        System.out.printf("  LongAdder:     %,d ms  (counter=%,d)%n", adderMs,  adderCounter.sum());
        System.out.printf("  AtomicLong vs synchronized: %.1fx faster%n",
            (double) syncMs / Math.max(atomicMs, 1));
        System.out.printf("  LongAdder vs synchronized:  %.1fx faster%n",
            (double) syncMs / Math.max(adderMs, 1));

        System.out.println("""

            LOCK CONTENTION REDUCTION:
            ┌──────────────────────┬──────────────────────────────────────────┐
            │ Pattern              │ Fix                                       │
            ├──────────────────────┼──────────────────────────────────────────┤
            │ synchronized counter │ AtomicLong / LongAdder                   │
            │ synchronized Map     │ ConcurrentHashMap                        │
            │ Global lock          │ Stripe locks (lock per key hash bucket)  │
            │ synchronized method  │ Reduce scope to synchronized block       │
            │ write-heavy lock     │ ReadWriteLock / StampedLock optimistic   │
            └──────────────────────┴──────────────────────────────────────────┘

            LongAdder vs AtomicLong:
              • AtomicLong: single cell, CAS spin under contention
              • LongAdder: per-thread cells, sum() merges at read time
              • Use AtomicLong: need exact value frequently (e.g., IDs)
              • Use LongAdder: high-write, low-read counters (metrics, rate limiters)
            """);
    }

    // =========================================================================
    // DEMO 6 — Wall Clock vs CPU Time
    // =========================================================================
    static void demo6_wallClockVsCpuTime() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — Wall-Clock vs CPU Time: Phân biệt I/O vs CPU bound");
        System.out.println("─".repeat(70));

        System.out.println("""

            CPU TIME  = thời gian thread thực sự dùng CPU
            WALL TIME = CPU time + I/O wait + lock wait + sleep

            CPU Profiling: chỉ sample khi thread RUNNING → miss I/O wait
            Wall-clock:    sample tất cả states → thấy cả blocking time

            KỊCH BẢN:
              Nếu wall >> cpu → bottleneck là I/O hoặc lock (không phải logic)
              Nếu wall ≈ cpu → bottleneck là computation (hot method)

            Ví dụ thực tế:
              Thread dump: 80% threads ở WAITING → không phải CPU problem!
              Fix: giảm I/O latency, tăng connection pool, async I/O

            Simulating both scenarios:
            """);

        ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        boolean cpuTimeSupported = threadMX.isCurrentThreadCpuTimeSupported();

        // Scenario A: CPU-bound (computation)
        System.out.println("  Scenario A: CPU-bound computation");
        long wallStart = System.nanoTime();
        long cpuStart  = cpuTimeSupported ? threadMX.getCurrentThreadCpuTime() : 0;

        // Pure CPU work: math operations
        double result = 0;
        for (int i = 0; i < 5_000_000; i++) {
            result += Math.sqrt(i) * Math.log(i + 1);
        }

        long wallEnd = System.nanoTime();
        long cpuEnd  = cpuTimeSupported ? threadMX.getCurrentThreadCpuTime() : 0;

        long wallMs = (wallEnd - wallStart) / 1_000_000;
        long cpuMs  = cpuTimeSupported ? (cpuEnd - cpuStart) / 1_000_000 : -1;

        System.out.printf("    Wall time: %,d ms%n", wallMs);
        System.out.printf("    CPU  time: %s%n", cpuMs >= 0 ? cpuMs + " ms" : "N/A");
        if (cpuMs > 0) {
            System.out.printf("    CPU utilization: %.0f%% (close to 100%% = CPU-bound)%n",
                (double) cpuMs / wallMs * 100);
        }

        // Scenario B: I/O-bound (sleep simulating network/disk)
        System.out.println("\n  Scenario B: I/O-bound (simulating network calls with sleep)");
        wallStart = System.nanoTime();
        cpuStart  = cpuTimeSupported ? threadMX.getCurrentThreadCpuTime() : 0;

        // Simulate I/O: mostly sleeping (thread in TIMED_WAITING state)
        for (int i = 0; i < 5; i++) {
            Thread.sleep(20); // simulate 20ms network call each
        }

        wallEnd = System.nanoTime();
        cpuEnd  = cpuTimeSupported ? threadMX.getCurrentThreadCpuTime() : 0;

        wallMs = (wallEnd - wallStart) / 1_000_000;
        cpuMs  = cpuTimeSupported ? (cpuEnd - cpuStart) / 1_000_000 : -1;

        System.out.printf("    Wall time: %,d ms%n", wallMs);
        System.out.printf("    CPU  time: %s%n", cpuMs >= 0 ? cpuMs + " ms" : "N/A");
        if (cpuMs >= 0) {
            System.out.printf("    CPU utilization: %.1f%% (near 0%% = I/O-bound, not CPU issue)%n",
                wallMs > 0 ? (double) cpuMs / wallMs * 100 : 0);
        }

        System.out.println("""

            PROFILING STRATEGY:
              CPU-bound (util ≈ 100%) → CPU flame graph → optimize hot methods
              I/O-bound  (util ≈ 0%)  → Wall-clock profile → reduce I/O latency
              Lock-bound (threads BLOCKED) → lock profile → reduce contention

            TOP DOWN APPROACH:
              1. jstat -gcutil → xem GC overhead
              2. top -H → thread CPU usage
              3. jstack → xem thread states
              4. async-profiler cpu OR wall → xem hot path
              5. Fix → verify với JMH hoặc load test
            """);
    }

    // =========================================================================
    // DEMO 7 — Flame Graph Interpretation Guide
    // =========================================================================
    static void demo7_flameGraphInterpretation() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — Flame Graph: Cách đọc & Interpret");
        System.out.println("─".repeat(70));

        System.out.println("""

            FLAME GRAPH ANATOMY (ASCII representation):
            ┌──────────────────────────────────────────────────────────────────┐
            │ % CPU time →                                                     │
            │                                                                  │
            │ Level 4 (leaf):   [regexMatch][    ][hashCode][   ][inflate]    │
            │ Level 3:          [  Pattern.compile  ][HashMap.get][GZIPStream]│
            │ Level 2:          [      validateInput()       ][cacheLoad()  ] │
            │ Level 1:          [              processRequest()              ] │
            │ Level 0 (root):   [                   main                    ] │
            └──────────────────────────────────────────────────────────────────┘
                                 ↑
                         regexMatch frame RỘNG NHẤT → hot path!

            READING RULES:
              1. Tìm frame RỘNG NHẤT gần ĐỈNH (leaf) → đó là thực sự tốn CPU
              2. Frame rộng ở GIỮA nhưng child hẹp → overhead của function call
              3. BẰNG PHẲNG ở top (flat top) → CPU spinning / tight loop
              4. Nhiều frame màu khác nhau ở cùng level → many code paths
              5. "all" frame rộng → nhiều threads active (check parallelism)

            COMMON FLAME GRAPH PATTERNS:
            ┌─────────────────────────────┬──────────────────────────────────┐
            │ Pattern                     │ Diagnosis & Fix                   │
            ├─────────────────────────────┼──────────────────────────────────┤
            │ regex/Pattern.compile wide  │ Precompile Pattern constant       │
            │ JSON serialize wide         │ Reuse ObjectMapper (not new each) │
            │ GC frames wide              │ Reduce allocation, tune GC        │
            │ lock/park/wait wide         │ Contention → ConcurrentHashMap    │
            │ Class.forName wide          │ Cache reflection results          │
            │ String.format wide          │ Use String.valueOf, StringBuilder  │
            │ ArrayList.toArray wide      │ Pre-size collection               │
            └─────────────────────────────┴──────────────────────────────────┘

            DIFF FLAME GRAPH (async-profiler feature):
              Compare before vs after optimization:
                ./asprof -e cpu -d 30 -f before.jfr <pid>
                # Deploy fix
                ./asprof -e cpu -d 30 -f after.jfr <pid>
                # Diff (negative = improvement, positive = regression)
                ./FlameGraph/difffolded.pl before.collapsed after.collapsed | \\
                  ./FlameGraph/flamegraph.pl --colors=java > diff.html

            INTERACTIVE FLAME GRAPH TIPS:
              • Click frame → zoom in to that subtree
              • Ctrl+F → search for class/method name
              • Hover → exact percentage + sample count
              • Right-click → reverse flame (icicle graph, root at top)
            """);
    }

    // =========================================================================
    // DEMO 8 — Hot Path: Before vs After Fix (measurable example)
    // =========================================================================

    static final Map<String, java.util.regex.Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    static boolean validateEmail_slow(String email) {
        // ANTI-PATTERN: compile Pattern on every call — extremely expensive!
        return email.matches("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    }

    static final java.util.regex.Pattern EMAIL_PATTERN =
        java.util.regex.Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    static boolean validateEmail_fast(String email) {
        // FIX: pre-compiled Pattern as static constant
        return EMAIL_PATTERN.matcher(email).matches();
    }

    static void demo8_hotPathBeforeAfterFix() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8 — Hot Path Fix: Pattern.compile Anti-pattern");
        System.out.println("─".repeat(70));

        System.out.println("""

            STRING.matches() ANTI-PATTERN:
              email.matches(regex)
              ≡ Pattern.compile(regex).matcher(email).matches()
              ≡ new Pattern object on EVERY call!

            In a high-traffic endpoint (10,000 req/s):
              → 10,000 Pattern.compile() calls per second
              → Pattern.compile is expensive (NFA construction, O(N²) worst case)
              → Flame graph: Pattern.compile takes 60-80% CPU time!

            FIX: static final Pattern constant (compiled once at class load)
            """);

        String[] emails = {
            "user@example.com", "bad-email", "test.user+tag@domain.co.uk",
            "invalid@", "@nodomain.com", "valid123@test.org"
        };

        int REPS = 30_000;

        // Warm up
        for (String e : emails) {
            validateEmail_slow(e);
            validateEmail_fast(e);
        }

        // Measure SLOW
        long start = System.nanoTime();
        int matches1 = 0;
        for (int i = 0; i < REPS; i++) {
            for (String e : emails) {
                if (validateEmail_slow(e)) matches1++;
            }
        }
        long slowMs = (System.nanoTime() - start) / 1_000_000;

        // Measure FAST
        start = System.nanoTime();
        int matches2 = 0;
        for (int i = 0; i < REPS; i++) {
            for (String e : emails) {
                if (validateEmail_fast(e)) matches2++;
            }
        }
        long fastMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("  %,d validations × %d emails = %,d calls%n",
            REPS, emails.length, (long) REPS * emails.length);
        System.out.printf("  String.matches() [compile each time]: %,d ms%n", slowMs);
        System.out.printf("  static Pattern   [compile once]:      %,d ms%n", fastMs);
        System.out.printf("  Speedup: %.1fx%n", (double) slowMs / Math.max(fastMs, 1));
        System.out.printf("  Both give same results: %b%n", matches1 == matches2);

        System.out.println("""

            OTHER COMMON HOT PATH FIXES:
            ┌──────────────────────────────────────┬────────────────────────────────┐
            │ Anti-pattern                         │ Fix                            │
            ├──────────────────────────────────────┼────────────────────────────────┤
            │ new ObjectMapper() per request       │ @Bean singleton ObjectMapper   │
            │ String.format("%.2f", val) in loop   │ String.valueOf + manual format │
            │ Class.forName("Foo") in hot path     │ Cache Class<?> in static field │
            │ new Random() per call                │ ThreadLocalRandom.current()    │
            │ Map.containsKey + Map.get            │ Map.computeIfAbsent single op  │
            │ list.size() in for condition         │ int size = list.size() before  │
            └──────────────────────────────────────┴────────────────────────────────┘
            """);
    }

    // =========================================================================
    // DEMO 9 — Profiling in Production: Best Practices
    // =========================================================================
    static void demo9_profilingInProduction() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 9 — Profiling in Production: Safe Practices");
        System.out.println("─".repeat(70));

        System.out.println("""

            SAFE PRODUCTION PROFILING CHECKLIST:
            ═══════════════════════════════════════════════════════════════════

            ✅ USE (safe):
              • JFR with "default" settings: <1% overhead, designed for production
              • async-profiler with 10ms interval: ~1-2% overhead
              • jstack: single thread dump, negligible impact
              • jstat -gcutil: read-only GC stats, no application impact

            ⚠ USE WITH CAUTION:
              • async-profiler with 1ms interval: ~5-10% overhead
              • jmap -histo:live: triggers a full GC first → STW pause!
              • JFR with "profile" settings: higher overhead, use on staging

            ❌ AVOID IN PRODUCTION:
              • jmap -dump (live): triggers full GC + writes large file → impact
              • VisualVM instrumentation mode: 20-100x slowdown
              • System.out.println in hot path: I/O in critical path
              • AOP/AspectJ tracing every method: huge overhead

            ═══════════════════════════════════════════════════════════════════
            PROFILING WORKFLOW FOR PRODUCTION INCIDENT:
            ═══════════════════════════════════════════════════════════════════

              STEP 1 — Identify symptom (5 min):
                • High CPU: top / htop → which PID/thread
                • High latency: Grafana → p99 spike
                • Memory leak: heap_after_gc metric trending up

              STEP 2 — Non-invasive first pass (2 min):
                jstack <pid> > threads.txt           # thread states
                jstat -gcutil <pid> 1000 30          # GC stats for 30s
                jcmd <pid> VM.native_memory          # native memory

              STEP 3 — Light profiling (5-10 min):
                jcmd <pid> JFR.start duration=60s filename=/tmp/rec.jfr
                # Wait 60 seconds...
                # Download rec.jfr → open in JMC

              STEP 4 — Targeted profiling if needed:
                ./asprof -e cpu -d 30 -f cpu.html <pid>    # CPU
                ./asprof -e alloc -d 30 -f alloc.html <pid> # Allocation

              STEP 5 — Analyze & fix:
                • JMC: Method Profiling → Hot Methods tab
                • Flame graph: find widest frame at top
                • Cross-reference with code → PR fix → staging verify → deploy

            ═══════════════════════════════════════════════════════════════════
            CONTINUOUS OBSERVABILITY (prevent incidents):
            ═══════════════════════════════════════════════════════════════════

              METRICS (Micrometer → Prometheus → Grafana):
                jvm_memory_used_bytes{area="heap"}       // heap trend
                jvm_gc_pause_seconds_max                 // GC pause spike
                jvm_threads_states_threads{state="blocked"} // contention
                process_cpu_usage                        // CPU trend
                http_server_requests_seconds{quantile="0.99"} // p99 latency

              ALERTS (PagerDuty/OpsGenie):
                heap_used/heap_max > 0.85 for 5m → WARN
                gc_pause_max > 500ms → WARN
                blocked_threads > 10 for 2m → WARN
                p99_latency > SLA_threshold → CRITICAL
            """);

        // Live metrics snapshot using JMX
        System.out.println("  [Live JVM Metrics Snapshot]");
        ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();
        MemoryMXBean memMX = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osMX = ManagementFactory.getOperatingSystemMXBean();

        System.out.printf("  Heap Used:        %.1f MB / %.1f MB%n",
            memMX.getHeapMemoryUsage().getUsed() / 1e6,
            memMX.getHeapMemoryUsage().getMax() / 1e6);
        System.out.printf("  Live Threads:     %d%n", threadMX.getThreadCount());
        System.out.printf("  Peak Threads:     %d%n", threadMX.getPeakThreadCount());
        System.out.printf("  Deadlocked:       %s%n",
            threadMX.findDeadlockedThreads() == null ? "none" : "DETECTED!");
        System.out.printf("  CPU Load (system): %.1f%%%n",
            osMX.getSystemLoadAverage() * 100 / osMX.getAvailableProcessors());

        long[] blockedThreads = Arrays.stream(threadMX.getAllThreadIds())
            .filter(id -> {
                ThreadInfo info = threadMX.getThreadInfo(id);
                return info != null && info.getThreadState() == Thread.State.BLOCKED;
            }).toArray();
        System.out.printf("  Blocked Threads:  %d%n", blockedThreads.length);

        // GC summary
        System.out.print("  GC Collectors:    ");
        ManagementFactory.getGarbageCollectorMXBeans().forEach(gc ->
            System.out.printf("%s(count=%d,time=%dms) ",
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        System.out.println();
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 5.4 — Profiling Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            PROFILING DECISION TREE:
              Latency spike?
                ├─ CPU usage high? → CPU flame graph (async-profiler -e cpu)
                ├─ GC pause?       → GC log + GCTuningDemo (bài 5.2)
                ├─ Thread BLOCKED? → Lock profile + jstack
                └─ I/O slow?       → Wall-clock profile (-e wall)

              Memory growing?
                ├─ Heap floor after GC rising? → MemoryLeakDemo (bài 5.3)
                └─ GC frequency increasing?    → Allocation profile (-e alloc)

            TOOL SELECTION GUIDE:
            ┌─────────────────────────┬──────────────────────────────────────┐
            │ Problem                 │ Tool                                  │
            ├─────────────────────────┼──────────────────────────────────────┤
            │ CPU bottleneck          │ async-profiler -e cpu + flame graph   │
            │ Memory leak             │ jmap -histo + MAT heap dump           │
            │ Allocation pressure     │ async-profiler -e alloc               │
            │ Lock contention         │ async-profiler -e lock + jstack       │
            │ Production monitoring   │ JFR continuous + JMC offline          │
            │ Micro-benchmark         │ JMH (bài 5.1)                         │
            │ Long-term observability │ Micrometer + Prometheus + Grafana     │
            └─────────────────────────┴──────────────────────────────────────┘

            SA RULES:
              ✓ Profile trước khi optimize — không đoán mò, data-driven
              ✓ JFR continuous recording ở production — không có overhead lo ngại
              ✓ Flame graph là kỹ năng thiết yếu — 30 phút học = 10x debug speed
              ✓ Wall-clock profile cho latency issues, CPU profile cho throughput
              ✓ Fix dựa trên profiling data → verify với JMH → commit với baseline
              ✓ Đặt performance baseline trong CI: nếu p99 tăng >10% → PR blocked

            MODULE 5 COMPLETE — KEY TAKEAWAYS:
              5.1 JMH:          Benchmark đúng cách — JIT/dead-code/constant-fold aware
              5.2 GC Tuning:    G1 default, ZGC low-latency, flags, log analysis
              5.3 Memory Leak:  Reachable ≠ useful; ThreadLocal.remove(); Cleaner API
              5.4 Profiling:    Measure first, flame graph, JFR production-safe

            "Performance engineering = Scientific method:
             Hypothesis → Measure → Analyze → Fix → Verify → Repeat"
            """);
    }
}
