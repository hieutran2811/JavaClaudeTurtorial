package org.example.jvm;

import java.lang.management.*;
import java.util.*;

/**
 * DEMO 4: Garbage Collection — Algorithms, Tuning, GC Log
 *
 * Bài toán thực tế:
 *  - App bị "stop-the-world" pause vài giây mỗi giờ -> chọn sai GC
 *  - Không biết đọc GC log -> không tune được
 *  - OOM xảy ra ở production nhưng không tái hiện được -> thiếu flag
 *
 * ====================================================
 *  GC ALGORITHMS OVERVIEW (Java 17+)
 * ====================================================
 *
 *  1. Serial GC       (-XX:+UseSerialGC)
 *     - Single thread, stop-the-world hoàn toàn
 *     - Dùng cho: CLI tools, app nhỏ < 100MB heap
 *
 *  2. Parallel GC     (-XX:+UseParallelGC)  [default Java 8]
 *     - Multi-thread nhưng vẫn stop-the-world
 *     - Dùng cho: batch jobs, throughput quan trọng hơn latency
 *
 *  3. G1GC            (-XX:+UseG1GC)        [default Java 9-20]
 *     - Region-based, concurrent marking, predictable pause
 *     - Target: MaxGCPauseMillis (default 200ms)
 *     - Dùng cho: server app đa dụng, heap 4GB-32GB
 *
 *  4. ZGC             (-XX:+UseZGC)         [production Java 15+]
 *     - Fully concurrent, pause < 1ms bất kể heap size
 *     - Dùng cho: low-latency (trading, real-time), heap rất lớn (TB)
 *
 *  5. Shenandoah      (-XX:+UseShenandoahGC) [OpenJDK, production Java 15+]
 *     - Tương tự ZGC, concurrent compaction
 *     - Dùng cho: tương tự ZGC, phổ biến hơn trên Red Hat / Amazon
 *
 * ====================================================
 *  GC ANATOMY — Heap structure trong G1GC
 * ====================================================
 *
 *  Heap chia thành các REGION (1MB - 32MB mỗi cái):
 *
 *  [E][E][S][O][O][O][H][E][S][O]...
 *   ^   ^   ^         ^
 *   |   |   |         |-- Humongous: object >= 50% region size
 *   |   |   |-- Survivor: sống qua >= 1 Minor GC
 *   |   |-- Eden: object mới tạo (Minor GC thu hồi)
 *   |-- Eden region khác
 *
 *  GC Types trong G1:
 *  - Young GC (Minor):  thu hồi Eden + Survivor  (stop-the-world, ~5-20ms)
 *  - Mixed GC:          Young + một số Old regions (stop-the-world, ~20-100ms)
 *  - Full GC:           toàn bộ heap              (stop-the-world, NGUY HIỂM)
 *
 * ====================================================
 *  GC LOG FORMAT — Cách đọc
 * ====================================================
 *
 *  Enable bằng flag: -Xlog:gc*:file=gc.log:time,uptime,level,tags
 *
 *  Ví dụ log G1GC:
 *  [2.345s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause)
 *                           40M->12M(256M) 8.231ms
 *                           ^       ^  ^    ^
 *                           |       |  |    |-- Thời gian pause (stop-the-world)
 *                           |       |  |-- Total heap committed
 *                           |       |-- Heap sau GC
 *                           |-- Heap trước GC
 *
 *  Dấu hiệu nguy hiểm cần action:
 *  - "Pause Full" xuất hiện     -> heap quá nhỏ hoặc memory leak
 *  - Pause > MaxGCPauseMillis   -> tune G1 hoặc chuyển ZGC
 *  - "to-space exhausted"       -> Survivor/Old không đủ chỗ
 */
public class GCDemo {

    public static void main(String[] args) throws Exception {

        // === PHẦN 1: Đọc thông tin GC hiện tại của JVM đang chạy ===
        printGCInfo();

        // === PHẦN 2: Quan sát GC hoạt động ===
        System.out.println("\n=== Quan sát GC qua GarbageCollectorMXBean ===");
        observeGCInAction();

        // === PHẦN 3: Humongous Object — bẫy thường gặp với G1GC ===
        System.out.println("\n=== Humongous Object Demo ===");
        demonstrateHumongousObject();

        // === PHẦN 4: GC Tuning Decision Tree ===
        printGCDecisionTree();

        // === PHẦN 5: Các JVM flags quan trọng nhất ===
        printImportantFlags();
    }

    // ------------------------------------------------------------------
    // PHẦN 1: Đọc GC info từ MXBean
    // ------------------------------------------------------------------
    static void printGCInfo() {
        System.out.println("=== GC Algorithm đang chạy ===");

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            System.out.println("GC Name      : " + gc.getName());
            System.out.println("Memory Pools : " + Arrays.toString(gc.getMemoryPoolNames()));
            System.out.println("Collection Count: " + gc.getCollectionCount());
            System.out.println("Collection Time : " + gc.getCollectionTime() + " ms");
            System.out.println("---");
        }

        // Nhận diện GC đang dùng từ tên
        String gcNames = gcBeans.stream()
                .map(GarbageCollectorMXBean::getName)
                .reduce("", (a, b) -> a + " " + b);

        String gcType;
        if (gcNames.contains("G1")) {
            gcType = "G1GC (Region-based, default Java 9+)";
        } else if (gcNames.contains("ZGC")) {
            gcType = "ZGC (Low-latency, pause < 1ms)";
        } else if (gcNames.contains("Shenandoah")) {
            gcType = "ShenandoahGC (Concurrent compaction)";
        } else if (gcNames.contains("Parallel")) {
            gcType = "Parallel GC (High throughput)";
        } else if (gcNames.contains("Serial")) {
            gcType = "Serial GC (Single thread)";
        } else {
            gcType = "Unknown: " + gcNames;
        }
        System.out.println("-> Đang dùng: " + gcType);
        System.out.println("-> Thay đổi bằng JVM flag, ví dụ: -XX:+UseZGC");
    }

    // ------------------------------------------------------------------
    // PHẦN 2: Quan sát GC hoạt động qua trước/sau allocation
    // ------------------------------------------------------------------
    static void observeGCInAction() throws InterruptedException {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // Chụp trạng thái GC trước
        long countBefore = totalGCCount(gcBeans);
        long timeBefore  = totalGCTime(gcBeans);

        System.out.println("Trước allocation: GC count=" + countBefore + ", GC time=" + timeBefore + "ms");

        // Tạo áp lực lên Heap để kích hoạt GC
        System.out.println("Đang tạo ~200MB objects ngắn hạn...");
        for (int i = 0; i < 200; i++) {
            byte[] pressure = new byte[1024 * 1024]; // 1MB mỗi vòng
            // Không giữ reference -> GC có thể thu hồi
        }

        System.gc(); // Gợi ý GC (không đảm bảo, nhưng thường chạy trong demo)
        Thread.sleep(200);

        // Chụp trạng thái GC sau
        long countAfter = totalGCCount(gcBeans);
        long timeAfter  = totalGCTime(gcBeans);

        System.out.println("Sau allocation:   GC count=" + countAfter + ", GC time=" + timeAfter + "ms");
        System.out.println("-> GC đã chạy " + (countAfter - countBefore) + " lần");
        System.out.println("-> Tổng thời gian pause thêm: " + (timeAfter - timeBefore) + " ms");
        System.out.println();
        System.out.println("INSIGHT: Đây chính là 'GC overhead' mà app phải chịu.");
        System.out.println("         Nếu GC time > 5% total time -> cần tune hoặc đổi GC algorithm.");
    }

    static long totalGCCount(List<GarbageCollectorMXBean> beans) {
        return beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(c -> c >= 0).sum();
    }

    static long totalGCTime(List<GarbageCollectorMXBean> beans) {
        return beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(t -> t >= 0).sum();
    }

    // ------------------------------------------------------------------
    // PHẦN 3: Humongous Object — bẫy phổ biến với G1GC
    // ------------------------------------------------------------------
    static void demonstrateHumongousObject() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long heapBefore = memBean.getHeapMemoryUsage().getUsed();

        // Object lớn hơn 50% region size của G1 (thường >= 512KB) -> Humongous region
        // Humongous objects KHÔNG đi qua Eden -> tốn Old Generation ngay lập tức
        // -> Gây ra Mixed GC sớm hơn bình thường
        byte[] bigObject = new byte[2 * 1024 * 1024]; // 2MB -> chắc chắn Humongous

        long heapAfter = memBean.getHeapMemoryUsage().getUsed();
        System.out.println("Heap tăng: " + (heapAfter - heapBefore) / 1024 + " KB");
        System.out.println();
        System.out.println("INSIGHT về Humongous Objects:");
        System.out.println("  - Object >= ~50% G1 region size -> được phân bổ vào Humongous region");
        System.out.println("  - Humongous region nằm trong Old Generation NGAY LẬP TỨC");
        System.out.println("  - Không được compacted -> gây memory fragmentation");
        System.out.println("  - Giải pháp: tăng G1 region size  (-XX:G1HeapRegionSize=16m)");
        System.out.println("             hoặc tránh tạo object lớn (dùng streaming thay buffer)");

        // Ngăn compiler optimize away
        if (bigObject.length == 0) System.out.println("never");
    }

    // ------------------------------------------------------------------
    // PHẦN 4: Decision Tree chọn GC
    // ------------------------------------------------------------------
    static void printGCDecisionTree() {
        System.out.println("\n=== GC Decision Tree (SA Guide) ===");
        System.out.println("""
                Câu hỏi 1: Heap size bao nhiêu?
                  < 1GB  -> Serial GC hoặc Parallel GC (overhead thấp)
                  1-32GB -> G1GC (cân bằng latency vs throughput)
                  > 32GB -> ZGC hoặc Shenandoah (low-latency concurrent)

                Câu hỏi 2: Ưu tiên gì?
                  Throughput (batch, ETL)   -> Parallel GC (-XX:+UseParallelGC)
                  Balanced (web server)     -> G1GC (default, không cần flag)
                  Low latency (API < 10ms)  -> ZGC (-XX:+UseZGC)
                  Ultra low (trading, RT)   -> ZGC + large heap + NUMA-aware

                Câu hỏi 3: Có Full GC không?
                  Có -> Tìm memory leak trước khi tune GC
                     -> Heap có thể quá nhỏ (tăng -Xmx)
                     -> Có thể cần tune G1 Mixed GC threshold

                Câu hỏi 4: GC pause có vượt SLA không?
                  Có với G1 -> Thử -XX:MaxGCPauseMillis=100 (giảm xuống)
                            -> Hoặc chuyển ZGC
                  Không     -> Giữ nguyên, đừng over-engineer
                """);
    }

    // ------------------------------------------------------------------
    // PHẦN 5: JVM Flags thực tế quan trọng nhất
    // ------------------------------------------------------------------
    static void printImportantFlags() {
        System.out.println("=== JVM Flags thực tế cho Production ===");
        System.out.println("""
                # === HEAP SIZING ===
                -Xms2g                    # Initial heap (set = Xmx để tránh resize overhead)
                -Xmx2g                    # Max heap
                -XX:MetaspaceSize=256m    # Initial Metaspace (tránh resize lúc startup)
                -XX:MaxMetaspaceSize=512m # Cap Metaspace (tránh leak class loader)

                # === GC ALGORITHM ===
                -XX:+UseG1GC              # Explicit G1 (default từ Java 9)
                -XX:MaxGCPauseMillis=200  # Target pause time cho G1
                -XX:G1HeapRegionSize=16m  # Tăng nếu có nhiều Humongous objects

                -XX:+UseZGC               # Low-latency (Java 15+ production ready)
                -XX:SoftMaxHeapSize=6g    # ZGC: để lại room cho spikes (heap max 8g)

                # === GC LOGGING (BẮT BUỘC ở production) ===
                -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20m

                # === OOM HANDLING (BẮT BUỘC ở production) ===
                -XX:+HeapDumpOnOutOfMemoryError
                -XX:HeapDumpPath=/dumps/heapdump.hprof
                -XX:+ExitOnOutOfMemoryError   # Thoát nhanh thay vì zombie process

                # === PERFORMANCE ===
                -XX:+UseStringDeduplication   # G1 only: tiết kiệm heap cho app nhiều String
                -XX:+OptimizeStringConcat     # JIT optimize string concatenation

                # === DEBUG (chỉ dùng khi troubleshoot, không dùng production) ===
                -XX:+PrintCompilation          # Xem JIT compile method nào
                -XX:+PrintGCDetails            # GC log chi tiết (Java 8, dùng -Xlog ở 11+)
                -XX:+PrintEscapeAnalysis       # Xem Escape Analysis decision
                """);

        System.out.println("INSIGHT quan trọng nhất:");
        System.out.println("  1. LUÔN set -Xms = -Xmx ở production (tránh heap resize giữa chừng)");
        System.out.println("  2. LUÔN bật HeapDumpOnOutOfMemoryError (không có dump = không debug được)");
        System.out.println("  3. LUÔN bật GC logging (overhead < 1%, lợi ích vô giá khi incident)");
        System.out.println("  4. Đừng tune GC trước khi fix memory leak (tune GC trên leak = vô ích)");
    }
}
