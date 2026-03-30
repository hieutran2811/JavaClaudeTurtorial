package org.example.performance;

import javax.management.NotificationEmitter;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * BÀI 5.2 — GC Tuning: Flags, Log Analysis & Garbage Collection Strategies
 * =============================================================================
 *
 * Tại sao cần hiểu GC Tuning?
 *   • GC là nguồn gốc của "random" latency spike ở production — p99 đột ngột tăng 10x
 *   • "Stop-the-world" pause = toàn bộ app threads bị freeze, user thấy lag
 *   • Wrong GC choice: throughput app dùng ZGC → overhead; latency app dùng SerialGC → disaster
 *   • Memory leak + wrong GC config = OOM trong vài giờ thay vì ngày
 *
 * GC ALGORITHMS OVERVIEW:
 * ┌─────────────────┬────────────────────────────────────────────────────────────┐
 * │ GC              │ Đặc điểm & Use Case                                        │
 * ├─────────────────┼────────────────────────────────────────────────────────────┤
 * │ Serial GC       │ Single-threaded, stop-the-world. Chỉ dùng embedded/tiny JVM│
 * │ Parallel GC     │ Multi-threaded STW. Batch jobs, throughput over latency     │
 * │ G1 GC (default) │ Region-based, balanced. General-purpose ≤4GB heap          │
 * │ ZGC             │ Concurrent, <1ms pause. Low-latency, large heap (≥8GB)     │
 * │ Shenandoah      │ Similar to ZGC, Red Hat. Good for heap 4-16GB              │
 * └─────────────────┴────────────────────────────────────────────────────────────┘
 *
 * KEY JVM FLAGS:
 *   -Xms<size>          Heap initial size (set equal to Xmx để tránh resize overhead)
 *   -Xmx<size>          Heap max size
 *   -Xss<size>          Thread stack size (default 512k-1m)
 *   -XX:+UseG1GC        Force G1 GC (default Java 9+)
 *   -XX:+UseZGC         Force ZGC (Java 15+ production ready)
 *   -XX:MaxGCPauseMillis=200  G1 pause target (soft goal)
 *   -XX:G1HeapRegionSize=16m  G1 region size (1-32MB, power of 2)
 *   -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xlog:gc*:file=gc.log  GC logging
 *   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof  OOM dump
 *   -XX:+UseStringDeduplication  G1 only: deduplicate String objects (20-40% heap saving)
 *
 * GC LOG READING (modern format: -Xlog:gc*):
 *   [0.123s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 128M->45M(256M) 12.345ms
 *    │        │    │    │     └─ Pause type                              │           │    └─ Duration
 *    │        │    │    └─ GC event number                              └─ Heap before/after
 *    │        │    └─ GC phase
 *    └─ Timestamp
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.performance.GCTuningDemo"
 */
public class GCTuningDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 5.2 — GC Tuning: Flags, Log Analysis & Strategies");
        System.out.println("=".repeat(70));
        System.out.println();

        // 1. Đọc thông tin GC hiện tại qua MXBeans
        demo1_readCurrentGCInfo();

        // 2. Quan sát GC triggers và allocation pressure
        demo2_gcTriggerAndAllocationPressure();

        // 3. GC pause measurement — tác động lên app latency
        demo3_gcPauseImpactOnLatency();

        // 4. Object lifecycle — Young vs Old gen, promotion
        demo4_generationalGcConcepts();

        // 5. Memory pressure patterns và detection
        demo5_memoryPressurePatterns();

        // 6. GC log phân tích (simulation)
        demo6_gcLogAnalysis();

        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Đọc GC info từ JMX MXBeans
    // =========================================================================
    static void demo1_readCurrentGCInfo() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — GC Info từ JMX MXBeans (không cần restart JVM)");
        System.out.println("─".repeat(70));

        // GarbageCollectorMXBean cho biết GC nào đang chạy
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("\n[Garbage Collectors đang active]");
        for (GarbageCollectorMXBean gc : gcBeans) {
            System.out.printf("  %-30s | Pools: %-40s%n",
                gc.getName(),
                Arrays.toString(gc.getMemoryPoolNames()));
        }

        // MemoryMXBean — heap usage tổng quan
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        System.out.println("\n[Heap Memory Usage]");
        System.out.printf("  Init:      %,d bytes (%.1f MB)%n", heap.getInit(), heap.getInit() / 1e6);
        System.out.printf("  Used:      %,d bytes (%.1f MB)%n", heap.getUsed(), heap.getUsed() / 1e6);
        System.out.printf("  Committed: %,d bytes (%.1f MB)%n", heap.getCommitted(), heap.getCommitted() / 1e6);
        System.out.printf("  Max:       %,d bytes (%.1f MB)%n", heap.getMax(), heap.getMax() / 1e6);

        // Memory pools chi tiết (Eden, Survivor, Old Gen, Metaspace)
        System.out.println("\n[Memory Pool Details]");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null && u.getMax() > 0) {
                double fillPct = (double) u.getUsed() / u.getMax() * 100;
                System.out.printf("  %-35s | Used: %5.1f MB / %5.1f MB (%5.1f%%)%n",
                    pool.getName(),
                    u.getUsed() / 1e6,
                    u.getMax() / 1e6,
                    fillPct);
            } else {
                System.out.printf("  %-35s | Used: %5.1f MB (no max / unlimited)%n",
                    pool.getName(),
                    u == null ? 0 : u.getUsed() / 1e6);
            }
        }

        // Runtime info
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        System.out.println("\n[JVM Runtime]");
        System.out.println("  JVM Name:    " + runtimeMXBean.getVmName());
        System.out.println("  JVM Version: " + runtimeMXBean.getVmVersion());

        // GC-relevant flags (chỉ đọc được nếu chạy với -XX:+PrintFlagsFinal)
        System.out.println("\n  Tip: Chạy với flag để xem tất cả GC settings:");
        System.out.println("    java -XX:+PrintFlagsFinal -version | grep -i 'gc\\|heap\\|pause'");
    }

    // =========================================================================
    // DEMO 2 — GC trigger: Minor GC và Full GC
    // =========================================================================
    static void demo2_gcTriggerAndAllocationPressure() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2 — GC Triggers: Minor GC (Young) vs Full GC (Old Gen)");
        System.out.println("─".repeat(70));

        System.out.println("""

            PHÂN TÍCH GC TRIGGER:
            ┌───────────────────────────────────────────────────────────────┐
            │ MINOR GC (Young Generation)                                    │
            │   Trigger: Eden space đầy                                      │
            │   Duration: <1ms đến vài ms (proportional to live objects)     │
            │   STW: Yes, nhưng ngắn — young gen nhỏ                        │
            │   Outcome: live objects promoted to Survivor → Old Gen         │
            │                                                                │
            │ MAJOR GC / FULL GC (Old Generation)                           │
            │   Trigger: Old Gen đầy (hoặc promotion failure)               │
            │   Duration: 100ms đến vài giây (large heap)                   │
            │   STW: Yes — đây là nguồn gốc latency spike production        │
            │   Outcome: compact heap, reclaim old gen objects               │
            │                                                                │
            │ G1 CONCURRENT MARKING                                          │
            │   Trigger: Heap occupancy > 45% (IHOP threshold)              │
            │   Duration: Mostly concurrent (không stop-the-world)          │
            │   Outcome: Phân tích đối tượng sống → prepare for evacuation  │
            └───────────────────────────────────────────────────────────────┘
            """);

        // Đo số GC events trước và sau allocation
        long gcCountBefore = getTotalGCCount();
        long gcTimeBefore = getTotalGCTime();

        System.out.println("[Allocating short-lived objects to trigger Minor GC...]");

        // Tạo nhiều short-lived objects → trigger Minor GC
        List<byte[]> sink = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            List<byte[]> temp = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                temp.add(new byte[1024]);   // 1KB × 1000 = ~1MB per round
            }
            // temp goes out of scope → becomes garbage → Minor GC candidate
            sink.add(new byte[512]);        // Keep one object alive
            Thread.sleep(10);
        }

        System.gc(); // Suggest GC (hint only, JVM may ignore)
        Thread.sleep(100);

        long gcCountAfter = getTotalGCCount();
        long gcTimeAfter = getTotalGCTime();

        System.out.printf("  GC events triggered: %d%n", gcCountAfter - gcCountBefore);
        System.out.printf("  Total GC time:       %d ms%n", gcTimeAfter - gcTimeBefore);
        System.out.println("  → Short-lived objects → Young Gen → Minor GC only (fast)");
        System.out.println("  → Long-lived objects  → Old Gen   → Full GC eventually (slow)");
    }

    // =========================================================================
    // DEMO 3 — GC Pause ảnh hưởng đến latency như thế nào
    // =========================================================================
    static void demo3_gcPauseImpactOnLatency() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — GC Pause Impact on Latency (STW visualization)");
        System.out.println("─".repeat(70));

        System.out.println("""

            STW (Stop-The-World) PAUSE tác động:
              • Tất cả application threads bị suspend
              • Request đang xử lý → bị hold → latency tăng đột ngột
              • p99 latency cao → GC thường là thủ phạm
              • Kubernetes liveness probe timeout → pod restart → false alarm

            Đo GC pause notification (Java 9+ GarbageCollectionNotification):
            """);

        // Lắng nghe GC notifications
        List<String> gcEvents = new CopyOnWriteArrayList<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener((notification, handback) -> {
                    com.sun.management.GarbageCollectionNotificationInfo info =
                        com.sun.management.GarbageCollectionNotificationInfo
                            .from((javax.management.openmbean.CompositeData) notification.getUserData());

                    long duration = info.getGcInfo().getDuration();
                    long before = info.getGcInfo().getMemoryUsageBeforeGc()
                        .values().stream().mapToLong(MemoryUsage::getUsed).sum();
                    long after = info.getGcInfo().getMemoryUsageAfterGc()
                        .values().stream().mapToLong(MemoryUsage::getUsed).sum();

                    String event = String.format(
                        "[GC Event] %-30s | Action: %-20s | Cause: %-20s | Duration: %3dms | Freed: %.1fMB",
                        info.getGcName(), info.getGcAction(), info.getGcCause(),
                        duration, (before - after) / 1e6);
                    gcEvents.add(event);
                    System.out.println("  " + event);
                }, null, null);
            }
        }

        // Trigger allocation để tạo GC events
        System.out.println("  [Triggering allocation pressure to capture GC events...]");
        Random rng = new Random(42);
        List<byte[]> longLived = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            // Mix of short-lived và long-lived allocations
            List<byte[]> shortLived = new ArrayList<>();
            for (int j = 0; j < 500; j++) {
                shortLived.add(new byte[rng.nextInt(2048) + 512]);
            }

            // Giữ một số object trong Old Gen (promote từ Survivor)
            if (i % 5 == 0 && longLived.size() < 10) {
                longLived.add(new byte[64 * 1024]); // 64KB long-lived
            }
            Thread.sleep(20);
        }

        System.gc();
        Thread.sleep(200);

        if (gcEvents.isEmpty()) {
            System.out.println("  (Không có GC event nào được capture — heap đủ lớn)");
            System.out.println("  Thử chạy với: java -Xmx64m để force GC sớm hơn");
        }

        System.out.println("\n  SA Insight: Dùng GarbageCollectionNotificationInfo để:");
        System.out.println("    • Alert khi GC pause > 100ms (SLA breach indicator)");
        System.out.println("    • Track GC frequency trend (tăng dần = memory leak)");
        System.out.println("    • Export sang Prometheus/Micrometer metrics dashboard");
    }

    // =========================================================================
    // DEMO 4 — Generational GC: Young → Old promotion
    // =========================================================================
    static void demo4_generationalGcConcepts() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — Generational GC: Object Lifecycle & Promotion");
        System.out.println("─".repeat(70));

        System.out.println("""

            GENERATIONAL HYPOTHESIS: "Hầu hết objects die young"
            → Chia heap thành Young Gen (Eden + Survivors) và Old Gen
            → Young Gen nhỏ → Minor GC nhanh
            → Old Gen lớn → Full GC hiếm nhưng tốn thời gian

            OBJECT LIFECYCLE:

              new Object()
                   │
                   ▼
              [Eden Space] ←──── hầu hết objects die here (fast Minor GC)
                   │ Eden đầy → Minor GC
                   ▼
              [Survivor S0] ←── age = 1
                   │ Minor GC
                   ▼
              [Survivor S1] ←── age = 2
                   │ ...
                   │ age > MaxTenuringThreshold (default: 15)
                   ▼
              [Old Generation] ←── long-lived objects, promoted here
                   │ Old Gen full
                   ▼
              [Full GC / G1 Mixed GC]

            KEY TUNING LEVERS:
              -XX:NewRatio=2            Old:New ratio (default 2 = 2/3 old, 1/3 young)
              -XX:SurvivorRatio=8       Eden:Survivor ratio (default 8 = 80% Eden)
              -XX:MaxTenuringThreshold  Age trước khi promote (default 15)
              -XX:+UseAdaptiveSizePolicy JVM tự điều chỉnh sizes (G1: always on)

            PROMOTION FAILURE:
              → Survivor space đầy → object được promoted sớm dù chưa đủ age
              → Old Gen nhận quá nhiều objects → Full GC sớm
              → Fix: tăng Survivor size hoặc giảm allocation rate
            """);

        // Demo: đo memory before/after tạo long-lived vs short-lived objects
        Runtime rt = Runtime.getRuntime();
        long usedBefore = rt.totalMemory() - rt.freeMemory();

        // Short-lived: sẽ bị GC collect trong Young Gen
        @SuppressWarnings("unused")
        Object shortLived;
        for (int i = 0; i < 10_000; i++) {
            shortLived = new int[100];  // ~400 bytes, unreachable immediately
        }

        System.gc();
        long usedAfterShort = rt.totalMemory() - rt.freeMemory();

        // Long-lived: sẽ survive và promote to Old Gen
        List<int[]> longLived = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            longLived.add(new int[1000]);  // ~4KB × 100 = ~400KB
        }

        System.gc();
        long usedAfterLong = rt.totalMemory() - rt.freeMemory();

        System.out.printf("  Memory used before: %.1f MB%n", usedBefore / 1e6);
        System.out.printf("  After short-lived:  %.1f MB (should be ≈ before after GC)%n", usedAfterShort / 1e6);
        System.out.printf("  After long-lived:   %.1f MB (remains in heap)%n", usedAfterLong / 1e6);
        System.out.printf("  Long-lived objects: %d arrays still referenced → not GC'd%n", longLived.size());
    }

    // =========================================================================
    // DEMO 5 — Memory Pressure Patterns
    // =========================================================================
    static void demo5_memoryPressurePatterns() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Memory Pressure Patterns & Detection");
        System.out.println("─".repeat(70));

        System.out.println("""

            PATTERN 1: Sawtooth (bình thường)
              Heap ↑ (allocate) → GC → Heap ↓ → ↑ → GC → ↓
              • Regular tăng/giảm sau mỗi GC = healthy
              • Baseline sau GC không tăng dần = không có leak

            PATTERN 2: Memory Leak (nguy hiểm)
              Heap: ──/──/──/──/──/ (không giảm về baseline)
              • Sau mỗi GC, heap floor tăng dần
              • Sau vài giờ → OOM: Java heap space
              • Nguyên nhân: static collection, unclosed resource, listener leak

            PATTERN 3: Promotion Failure (tuning cần thiết)
              Nhiều Full GC liên tiếp → throughput giảm 50%+
              • Old Gen không đủ chỗ nhận từ Young Gen
              • Fix: tăng Xmx, giảm NewRatio, hoặc fix allocation rate

            PATTERN 4: GC Thrashing (critical)
              GC time > 98% CPU time → JVM throw GC Overhead Limit Exceeded
              • App gần như không làm được gì ngoài GC
              • Fix: tăng heap ngay, sau đó fix root cause
            """);

        // Mimic memory pressure monitoring
        Runtime rt = Runtime.getRuntime();
        System.out.println("  [Monitoring heap usage over 10 snapshots]");
        System.out.println("  Snapshot | Used MB | Free MB | Total MB | Usage%");
        System.out.println("  " + "─".repeat(55));

        List<Long> usageHistory = new ArrayList<>();
        List<byte[]> accumulator = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            // Allocate increasing amounts to show pressure
            if (i < 7) {
                accumulator.add(new byte[100 * 1024]); // 100KB per snapshot
            } else {
                // Release at the end (simulating cleanup)
                accumulator.clear();
            }

            long used = rt.totalMemory() - rt.freeMemory();
            long free = rt.freeMemory();
            long total = rt.totalMemory();
            double pct = (double) used / total * 100;
            usageHistory.add(used);

            System.out.printf("  %8d | %7.1f | %7.1f | %8.1f | %5.1f%%%n",
                i + 1, used / 1e6, free / 1e6, total / 1e6, pct);
        }

        // Phân tích trend
        long firstUsage = usageHistory.get(0);
        long peakUsage = usageHistory.stream().mapToLong(Long::longValue).max().orElse(0);
        long lastUsage = usageHistory.get(usageHistory.size() - 1);

        System.out.printf("%n  Peak usage:    %.1f MB%n", peakUsage / 1e6);
        System.out.printf("  Final usage:   %.1f MB%n", lastUsage / 1e6);
        System.out.printf("  Memory freed:  %.1f MB (after releasing references)%n",
            (peakUsage - lastUsage) / 1e6);

        System.out.println("""

            DETECTION TOOLS:
              • jstat -gcutil <pid> 1000    — GC stats mỗi 1 giây
              • jmap -heap <pid>            — Heap summary
              • jcmd <pid> GC.heap_info    — Heap info (Java 9+)
              • VisualVM / JMC             — GUI heap monitoring
              • Micrometer + Prometheus    — Production metrics: jvm_memory_used_bytes
            """);
    }

    // =========================================================================
    // DEMO 6 — GC Log Analysis (simulation với real log format)
    // =========================================================================
    static void demo6_gcLogAnalysis() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — GC Log Analysis: Đọc hiểu GC Log production");
        System.out.println("─".repeat(70));

        System.out.println("""

            CÁCH BẬT GC LOGGING (Java 9+ Unified Logging):
              java -Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=10,filesize=50m

            Hoặc chi tiết hơn:
              java -Xlog:gc+heap=debug:file=gc.log:time,uptime
                   -XX:+HeapDumpOnOutOfMemoryError
                   -XX:HeapDumpPath=/var/log/app/heapdump.hprof

            GC LOG SAMPLES (G1 GC):
            """);

        // Simulate GC log output với annotation
        String[] gcLogSamples = {
            "[2.123s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 25M->10M(256M) 8.234ms",
            "[5.456s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 51M->12M(256M) 9.102ms",
            "[8.789s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 78M->15M(256M) 10.512ms",
            "[15.001s][info][gc] GC(3) Pause Young (Concurrent Start) (Metadata GC Threshold) 102M->20M(256M) 11.234ms",
            "[15.002s][info][gc] GC(3) Concurrent Mark Cycle",
            "[15.890s][info][gc] GC(3) Pause Remark 45M->45M(256M) 2.123ms",
            "[16.100s][info][gc] GC(3) Pause Cleanup 45M->22M(256M) 0.234ms",
            "[16.101s][info][gc] GC(3) Concurrent Mark Cycle 1099.234ms",
            "[20.234s][info][gc] GC(4) Pause Young (Mixed) (G1 Evacuation Pause) 89M->25M(256M) 15.678ms",
            "[35.000s][info][gc] GC(5) Pause Full (System.gc()) 140M->30M(256M) 245.123ms"
        };

        for (String log : gcLogSamples) {
            System.out.println("  " + log);
        }

        System.out.println("""

            PHÂN TÍCH GC LOG Ở TRÊN:
            ┌────────────────────────────────────────────────────────────────────┐
            │ GC(0-2): Pause Young (Normal) — Minor GC                          │
            │   • Tốt: pause 8-10ms, heap giảm đáng kể mỗi lần                │
            │   • Trend: heap floor tăng (10→12→15MB) = có objects promoted    │
            │                                                                    │
            │ GC(3): Concurrent Start (Metadata GC Threshold)                   │
            │   • Cause: Metaspace đang đầy → kích hoạt Concurrent Marking     │
            │   • Fix: -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m        │
            │                                                                    │
            │ GC(3): Pause Remark 2.12ms + Cleanup 0.23ms                      │
            │   • STW nhỏ trong Concurrent Marking cycle — acceptable           │
            │                                                                    │
            │ GC(4): Pause Young (Mixed)                                        │
            │   • G1 bắt đầu collect Old Gen regions cùng Young Gen            │
            │   • Pause dài hơn (15ms) vì làm nhiều việc hơn                   │
            │                                                                    │
            │ GC(5): Pause Full (System.gc()) — 245ms!                         │
            │   • RED FLAG: ai đó gọi System.gc() trong code!                  │
            │   • Fix: -XX:+DisableExplicitGC hoặc remove System.gc() call     │
            │   • 245ms = 245ms STW = user thấy hang trong 0.25 giây           │
            └────────────────────────────────────────────────────────────────────┘

            GC ANALYSIS TOOLS:
              • GCEasy.io   — Upload GC log → visual report + recommendations
              • GCViewer    — Open source GC log visualizer
              • async-profiler --alloc — Allocation profiling để tìm hot allocator
              • jstat -gc <pid> 1000  — Live GC stats

            GC LOG METRICS CẦN THEO DÕI:
              • GC frequency (events/minute) — tăng dần = memory pressure
              • GC duration (ms) — > 200ms = cần tuning
              • GC cause — System.gc(), Allocation Failure, Metadata GC Threshold
              • Heap recovery rate — (before - after) / before — giảm dần = leak
            """);

        // Simulate phân tích simple GC log
        System.out.println("  [Automated GC Log Analysis Simulation]");
        analyzeSimulatedGCLog(gcLogSamples);
    }

    static void analyzeSimulatedGCLog(String[] logs) {
        int pauseCount = 0;
        double totalPauseMs = 0;
        double maxPauseMs = 0;
        List<String> redFlags = new ArrayList<>();

        for (String log : logs) {
            if (log.contains("Pause") && !log.contains("Cycle")) {
                // Extract duration (last number before "ms")
                String[] parts = log.split("\\s+");
                for (String part : parts) {
                    if (part.endsWith("ms")) {
                        try {
                            double ms = Double.parseDouble(part.replace("ms", ""));
                            pauseCount++;
                            totalPauseMs += ms;
                            maxPauseMs = Math.max(maxPauseMs, ms);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (log.contains("System.gc()")) {
                    redFlags.add("EXPLICIT System.gc() call detected — remove from code!");
                }
                if (log.contains("Metadata GC Threshold")) {
                    redFlags.add("Metaspace pressure — tune -XX:MaxMetaspaceSize");
                }
            }
        }

        System.out.printf("%n  GC Pause Events:    %d%n", pauseCount);
        System.out.printf("  Total Pause Time:   %.1f ms%n", totalPauseMs);
        System.out.printf("  Average Pause:      %.1f ms%n", pauseCount > 0 ? totalPauseMs / pauseCount : 0);
        System.out.printf("  Max Pause (p100):   %.1f ms%n", maxPauseMs);

        if (!redFlags.isEmpty()) {
            System.out.println("\n  RED FLAGS:");
            redFlags.forEach(f -> System.out.println("    ⚠ " + f));
        }
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================
    static long getTotalGCCount() {
        return ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .filter(c -> c >= 0).sum();
    }

    static long getTotalGCTime() {
        return ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionTime)
            .filter(t -> t >= 0).sum();
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 5.2 — GC Tuning Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            GC SELECTION GUIDE (SA Decision Matrix):
            ┌─────────────────────────────────────────────────────────────────┐
            │ Scenario                          → GC Recommendation           │
            ├─────────────────────────────────────────────────────────────────┤
            │ General-purpose app (≤8GB)        → G1 (default, no tuning)     │
            │ Low-latency API (<10ms p99)       → ZGC (-XX:+UseZGC)           │
            │ Batch/ETL (throughput > latency)  → Parallel GC                 │
            │ Large heap (>32GB)                → ZGC hoặc Shenandoah         │
            │ Microservices (small, fast start) → G1 + -Xms=Xmx              │
            └─────────────────────────────────────────────────────────────────┘

            PRODUCTION GC FLAGS TEMPLATE:
              java \\
                -Xms4g -Xmx4g \\                       ← Set equal: no resize overhead
                -XX:+UseG1GC \\                         ← Explicit (default Java 9+)
                -XX:MaxGCPauseMillis=200 \\             ← Soft pause target
                -XX:G1HeapRegionSize=16m \\             ← For 4GB heap
                -XX:+UseStringDeduplication \\          ← Save 20-40% heap (String-heavy apps)
                -Xlog:gc*:file=/logs/gc.log:time,uptime:filecount=10,filesize=50m \\
                -XX:+HeapDumpOnOutOfMemoryError \\
                -XX:HeapDumpPath=/logs/heapdump.hprof \\
                -jar app.jar

            SA RULES:
              ✓ Set -Xms = -Xmx để tránh heap resize overhead ở production
              ✓ LUÔN bật GC logging ở production (chi phí <1% CPU)
              ✓ LUÔN bật HeapDumpOnOutOfMemoryError — không có dump = không debug được
              ✓ Thêm -XX:+DisableExplicitGC để chặn code gọi System.gc()
              ✓ Monitor: GC frequency, pause duration, heap after GC trend
              ✓ Tune GC chỉ sau khi đã fix memory leak — tuning mà leak thì vô nghĩa
              ✓ Test GC config trên staging với production load trước khi deploy

            KEY INSIGHT:
              "GC pause spike thường là symptom, không phải root cause.
               Root cause thường là: memory leak, excessive allocation,
               hay wrong object lifecycle design."
            """);
    }
}
