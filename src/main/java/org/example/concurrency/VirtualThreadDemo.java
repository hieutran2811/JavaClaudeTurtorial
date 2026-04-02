package org.example.concurrency;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * ============================================================
 * BÀI 2.6 — Virtual Threads & Project Loom (Java 21)
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Platform thread vs Virtual thread — cấu trúc khác nhau thế nào
 *   2. Tại sao Virtual thread giải quyết "thread-per-request" bottleneck
 *   3. Cách tạo và dùng Virtual thread
 *   4. Structured Concurrency — StructuredTaskScope (Java 21 preview)
 *   5. Pinning — khi nào Virtual thread bị "dính" vào platform thread
 *   6. Virtual thread KHÔNG phải silver bullet — khi nào KHÔNG nên dùng
 *
 * YÊU CẦU: Java 21+
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.concurrency.VirtualThreadDemo"
 * ============================================================
 */
public class VirtualThreadDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 2.6: Virtual Threads & Project Loom ===");
        System.out.println("Java version: " + System.getProperty("java.version") + "\n");

        demo1_PlatformVsVirtual();
        demo2_MassiveConcurrency();
        demo3_VirtualThreadAPIs();
        demo4_StructuredConcurrency();
        demo5_PinningAndLimitations();
        demo6_WhenToUse();

        System.out.println("\n=== KẾT THÚC BÀI 2.6 — MODULE 2 HOÀN THÀNH ===");
    }

    // ================================================================
    // Helper: chạy N task đồng thời, trả về thời gian ms
    // ================================================================

    /**
     * Chạy {@code count} task trên {@code exec}, mỗi task là {@code task},
     * đợi tất cả hoàn thành và trả về thời gian tổng (ms).
     */
    static long runConcurrent(ExecutorService exec, int count, CheckedRunnable task) throws Exception {
        CountDownLatch latch = new CountDownLatch(count);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            exec.submit(() -> {
                try { task.run(); } catch (Exception e) { Thread.currentThread().interrupt(); }
                finally { latch.countDown(); }
            });
        }
        latch.await(120, TimeUnit.SECONDS);
        return System.currentTimeMillis() - start;
    }

    @FunctionalInterface
    interface CheckedRunnable { void run() throws Exception; }

    // ================================================================
    // DEMO 1: Platform Thread vs Virtual Thread — Cấu trúc khác nhau
    // ================================================================

    /**
     * PLATFORM THREAD (Java truyền thống):
     *   - Là wrapper trực tiếp của OS thread
     *   - Stack size: 512KB – 2MB (mặc định 1MB trên Linux)
     *   - Tạo/huỷ: tốn kém (~1ms, syscall vào kernel)
     *   - Context switch: do OS kernel thực hiện (~1-10µs)
     *   - Giới hạn: thực tế ~2.000 – 10.000 thread/JVM (tuỳ RAM)
     *   - Blocking I/O: giữ OS thread → OS thread ngồi không, lãng phí
     *
     * VIRTUAL THREAD (Project Loom, Java 21):
     *   - Là "lightweight thread" được JVM quản lý, KHÔNG mapping 1-1 với OS thread
     *   - Stack size: ~200 bytes lúc tạo, grow dynamically trên heap
     *   - Tạo/huỷ: cực rẻ (ns range, không cần syscall)
     *   - Scheduling: JVM scheduler (ForkJoinPool) chạy virtual thread trên platform thread
     *   - Giới hạn: hàng triệu virtual thread/JVM
     *   - Blocking I/O: JVM tự động "unmount" virtual thread khỏi platform thread
     *     → Platform thread tự do chạy virtual thread khác → utilization 100%
     *
     * CARRIER THREAD: Platform thread đang "carry" (chạy) một virtual thread.
     *   Virtual thread MOUNT lên carrier → chạy → gặp blocking → UNMOUNT khỏi carrier
     *   → Carrier chạy virtual thread khác → I/O xong → virtual thread được mount lại
     *
     * SA INSIGHT: Virtual thread = giải pháp cho C10K problem mà không cần
     *   reactive/async code. Viết code blocking style, JVM tự scale.
     *   Spring Boot 3.2+ hỗ trợ virtual thread với 1 dòng config.
     */
    static void demo1_PlatformVsVirtual() throws Exception {
        System.out.println("--- DEMO 1: Platform Thread vs Virtual Thread ---");

        // Platform thread — OS thread thực sự
        Thread platformThread = new Thread(() ->
            System.out.println("  Platform thread: " + Thread.currentThread()
                    + " | virtual=" + Thread.currentThread().isVirtual()));
        platformThread.start();
        platformThread.join();

        // Virtual thread — lightweight, JVM-managed
        Thread virtualThread = Thread.ofVirtual()
            .name("my-virtual-thread")
            .start(() ->
                System.out.println("  Virtual thread:  " + Thread.currentThread()
                        + " | virtual=" + Thread.currentThread().isVirtual()));
        virtualThread.join();

        // So sánh chi phí tạo thread
        int count = 10_000;

        try (ExecutorService ptPool = Executors.newCachedThreadPool()) {
            long platformTime = runConcurrent(ptPool, count, () -> {});
            try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
                long virtualTime = runConcurrent(vtPool, count, () -> {});
                System.out.println("  Tạo " + count + " platform threads: " + platformTime + "ms");
                System.out.println("  Tạo " + count + " virtual threads:  " + virtualTime + "ms");
                System.out.println("  Virtual thread nhanh hơn ~" + (platformTime / Math.max(virtualTime, 1)) + "x\n");
            }
        }
    }

    // ================================================================
    // DEMO 2: Massive Concurrency — 10.000 concurrent "requests"
    // ================================================================

    /**
     * Bài toán kinh điển: Server phải xử lý 10.000 request đồng thời,
     * mỗi request mất 100ms chờ I/O (DB, HTTP call...).
     *
     * Với Platform Thread pool (n threads):
     *   10.000 / n = nhiều batch × 100ms → tổng thời gian rất lớn
     *
     * Với Virtual Thread:
     *   10.000 virtual thread × ~200 bytes = ~2MB → trivial
     *   Tất cả "chờ" I/O đồng thời, JVM tự unmount → platform thread free
     *   → Thời gian xấp xỉ 100ms (cộng scheduling overhead)
     *
     * Đây chính là lý do Spring Boot, Quarkus, Micronaut đều adopt virtual thread.
     */
    static void demo2_MassiveConcurrency() throws Exception {
        System.out.println("--- DEMO 2: Massive Concurrency ---");

        int requests   = 10_000;
        int ioDelayMs  = 100;
        int poolSize   = Runtime.getRuntime().availableProcessors() * 2;

        long platformTime, virtualTime;
        try (ExecutorService ptPool = Executors.newFixedThreadPool(poolSize)) {
            platformTime = runConcurrent(ptPool, requests, () -> Thread.sleep(ioDelayMs));
        }
        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualTime = runConcurrent(vtPool, requests, () -> Thread.sleep(ioDelayMs));
        }

        System.out.println("  " + requests + " requests, mỗi request I/O " + ioDelayMs + "ms:");
        System.out.println("  Platform pool (" + poolSize + " threads): " + platformTime + "ms");
        System.out.println("  Virtual thread pool:                  " + virtualTime + "ms");
        System.out.printf("  Virtual thread nhanh hơn: %.1fx%n%n", (double) platformTime / virtualTime);
    }

    // ================================================================
    // DEMO 3: Virtual Thread APIs
    // ================================================================

    /**
     * Các cách tạo Virtual Thread:
     *
     *   Thread.ofVirtual().start(runnable)           — tạo và start ngay
     *   Thread.ofVirtual().name("name").unstarted(r) — tạo chưa start
     *   Thread.startVirtualThread(runnable)           — shortcut nhanh nhất
     *   Executors.newVirtualThreadPerTaskExecutor()   — ExecutorService wrapper
     *   Executors.newThreadPerTaskExecutor(factory)   — custom factory
     *
     * Virtual thread LUÔN là daemon thread — không ngăn JVM thoát.
     * Virtual thread KHÔNG hỗ trợ ThreadGroup (deprecated concept).
     * Thread.currentThread().isVirtual() — kiểm tra loại thread.
     */
    static void demo3_VirtualThreadAPIs() throws Exception {
        System.out.println("--- DEMO 3: Virtual Thread APIs ---");

        // Cách 1: Thread.ofVirtual() với auto-increment name
        Thread t1 = Thread.ofVirtual()
            .name("vt-worker-", 1)
            .start(() -> System.out.println("  [1] Thread.ofVirtual(): " + Thread.currentThread().getName()));
        t1.join();

        // Cách 2: startVirtualThread — shortcut
        Thread t2 = Thread.startVirtualThread(() ->
            System.out.println("  [2] startVirtualThread: " + Thread.currentThread().getName()));
        t2.join();

        // Cách 3: ExecutorService — tương thích với code cũ
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> f = exec.submit(() -> {
                Thread.sleep(50);
                return "  [3] Via ExecutorService: virtual=" + Thread.currentThread().isVirtual();
            });
            System.out.println(f.get());
        } // try-with-resources tự shutdown

        // Cách 4: ThreadFactory — tích hợp với framework (Spring, etc.)
        ThreadFactory factory = Thread.ofVirtual().name("app-", 0).factory();
        Thread t4 = factory.newThread(() ->
            System.out.println("  [4] ThreadFactory: " + Thread.currentThread().getName()));
        t4.start();
        t4.join();

        Thread vt = Thread.ofVirtual().unstarted(() -> {});
        System.out.println("  Virtual thread isDaemon: " + vt.isDaemon() + " (luôn true)");
        System.out.println("  Virtual thread isVirtual: " + vt.isVirtual() + "\n");
    }

    // ================================================================
    // DEMO 4: Structured Concurrency (Java 21)
    // ================================================================

    /**
     * STRUCTURED CONCURRENCY: Đảm bảo subtask không "thoát khỏi" scope của parent.
     *   → Không còn task leak, không còn phải track Future thủ công
     *   → Error handling rõ ràng: nếu 1 subtask fail → cancel subtask còn lại
     *
     * StructuredTaskScope có 2 policy:
     *
     *   ShutdownOnFailure:  Nếu BẤT KỲ subtask nào fail → cancel tất cả
     *     → Dùng khi cần TẤT CẢ kết quả (giống allOf nhưng structured)
     *
     *   ShutdownOnSuccess:  Khi BẤT KỲ subtask nào thành công → cancel tất cả
     *     → Dùng khi chỉ cần 1 kết quả nhanh nhất (hedged request)
     *
     * So sánh với CompletableFuture.allOf():
     *   allOf: task chạy trên thread pool riêng, phải track Future thủ công
     *   StructuredTaskScope: subtask gắn với scope, tự cleanup khi thoát scope
     *
     * NOTE: Java 21 vẫn là Preview API — cần --enable-preview để compile.
     *   Java 23+ sẽ finalize. Demo này dùng CompletableFuture minh hoạ concept.
     */
    static void demo4_StructuredConcurrency() throws Exception {
        System.out.println("--- DEMO 4: Structured Concurrency ---");

        // ShutdownOnFailure pattern: cần TẤT CẢ task thành công
        System.out.println("  Concept: StructuredTaskScope.ShutdownOnFailure");
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.currentTimeMillis();

            Future<String>  userFuture    = exec.submit(() -> { Thread.sleep(80);  return "Alice"; });
            Future<Integer> orderFuture   = exec.submit(() -> { Thread.sleep(120); return 5; });
            Future<Double>  balanceFuture = exec.submit(() -> { Thread.sleep(60);  return 1500.0; });

            try {
                String user    = userFuture.get(2, TimeUnit.SECONDS);
                int    orders  = orderFuture.get(2, TimeUnit.SECONDS);
                double balance = balanceFuture.get(2, TimeUnit.SECONDS);
                System.out.printf("  ShutdownOnFailure: user=%s, orders=%d, balance=%.1f (%dms)%n",
                        user, orders, balance, System.currentTimeMillis() - start);
            } catch (Exception e) {
                userFuture.cancel(true);
                orderFuture.cancel(true);
                balanceFuture.cancel(true);
                System.out.println("  1 subtask fail → cancel tất cả: " + e.getMessage());
            }
        }

        // ShutdownOnSuccess pattern: chỉ cần 1 task nhanh nhất (hedged request)
        System.out.println("  Concept: StructuredTaskScope.ShutdownOnSuccess (hedged request)");
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.currentTimeMillis();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            // Gửi đến 3 replica — lấy replica nào trả về trước
            for (int[] cfg : new int[][]{{300, 1}, {80, 2}, {200, 3}}) {
                int delay = cfg[0], id = cfg[1];
                exec.submit(() -> {
                    try {
                        Thread.sleep(delay);
                        result.compareAndSet(null, "replica-" + id);
                        done.countDown();
                    } catch (InterruptedException ignored) {}
                });
            }

            done.await(5, TimeUnit.SECONDS);
            exec.shutdownNow(); // cancel các task còn lại
            System.out.println("  ShutdownOnSuccess: '" + result.get() + "' về trước ("
                    + (System.currentTimeMillis() - start) + "ms)\n");
        }
    }

    // ================================================================
    // DEMO 5: Pinning — Khi Virtual Thread bị "dính" vào platform thread
    // ================================================================

    /**
     * PINNING: Virtual thread bị "ghim" vào carrier (platform) thread,
     *   KHÔNG thể unmount dù đang blocking.
     *   → Carrier thread bị block → giống platform thread truyền thống → mất lợi ích
     *
     * 2 NGUYÊN NHÂN PINNING:
     *
     *   1. synchronized block/method:
     *      synchronized(obj) { Thread.sleep(1000); }  → PIN!
     *      JVM không thể unmount khi trong synchronized (Java 21)
     *      Fix: Thay synchronized bằng ReentrantLock (bài 2.3)
     *      Note: Java 23+ đang fix vấn đề này
     *
     *   2. Native method (JNI):
     *      Gọi C/C++ code qua JNI → PIN trong suốt thời gian native call
     *      Fix: Minimize JNI scope, wrap trong platform thread riêng
     *
     * PHÁT HIỆN PINNING:
     *   JVM flag: -Djdk.tracePinnedThreads=full
     *   → In stack trace mỗi khi virtual thread bị pin
     *
     * SA INSIGHT: Đây là vấn đề thực tế khi migrate sang virtual thread.
     *   Libraries dùng synchronized (JDBC drivers, cũ): pin virtual thread
     *   Java 21: Nhiều JDK class đã fix (Socket, I/O, etc.)
     *   Spring Boot 3.2 + virtual thread: recommend dùng HikariCP 5.1+ (đã fix pinning)
     */
    static void demo5_PinningAndLimitations() throws Exception {
        System.out.println("--- DEMO 5: Pinning & Limitations ---");

        int tasks   = 200;
        int sleepMs = 50;

        final Object     lock          = new Object();
        final ReentrantLock reentrantLock = new ReentrantLock();

        long noPin, withPin, lockFixed;

        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            // Thread.sleep() trong virtual thread → unmount bình thường, NO PIN
            noPin = runConcurrent(vtPool, tasks, () -> Thread.sleep(sleepMs));

            // synchronized + sleep → PIN trong Java 21
            withPin = runConcurrent(vtPool, tasks, () -> {
                synchronized (lock) { Thread.sleep(sleepMs); }
            });

            // ReentrantLock + sleep → KHÔNG pin → unmount được
            lockFixed = runConcurrent(vtPool, tasks, () -> {
                reentrantLock.lock();
                try { Thread.sleep(sleepMs); }
                finally { reentrantLock.unlock(); }
            });
        }

        System.out.println("  " + tasks + " virtual threads, sleep " + sleepMs + "ms mỗi task:");
        System.out.println("  Thread.sleep (no pin):    " + noPin    + "ms  ← baseline tốt");
        System.out.println("  synchronized + sleep PIN: " + withPin  + "ms  ← bị pin, chậm!");
        System.out.println("  ReentrantLock (no pin):   " + lockFixed + "ms  ← fix pin bằng Lock");
        System.out.println("  → Fix pinning: thay synchronized bằng ReentrantLock\n");
    }

    // ================================================================
    // DEMO 6: Khi nào dùng Virtual Thread — Decision Guide
    // ================================================================

    /**
     * DÙNG VIRTUAL THREAD KHI:
     *   ✓ Ứng dụng IO-bound: HTTP server, microservices, REST client
     *   ✓ "Thread-per-request" model (Spring MVC, Jakarta EE Servlet)
     *   ✓ Nhiều concurrent request chờ I/O (DB, HTTP, file)
     *   ✓ Muốn viết code blocking-style (dễ đọc) mà vẫn scale tốt
     *   ✓ Spring Boot 3.2+: thêm spring.threads.virtual.enabled=true là xong
     *
     * KHÔNG dùng VIRTUAL THREAD KHI:
     *   ✗ CPU-bound tasks: encryption, compression, image processing
     *     → Virtual thread không giúp gì (CPU không được giải phóng khi tính toán)
     *     → Dùng ForkJoinPool (bài 2.4) với nCPU threads
     *
     *   ✗ Code dùng synchronized nhiều (pinning) mà không thể refactor
     *     → Vẫn dùng platform thread pool + ReentrantLock cho critical section
     *
     *   ✗ ThreadLocal cần per-thread data cố định
     *     → Virtual thread có thể chạy trên nhiều carrier thread khác nhau
     *     → Dùng ScopedValue (Java 21 preview) thay ThreadLocal
     *
     * SA INSIGHT: Virtual thread KHÔNG thay thế reactive (Webflux/Reactor).
     *   - Virtual thread: blocking code, dễ debug stack trace
     *   - Reactive: non-blocking pipeline, backpressure, composable operators
     *   - Throughput tương đương ở nhiều workload IO-bound
     *   - Reactive vẫn tốt hơn khi cần backpressure, streaming, operator composability
     */
    static void demo6_WhenToUse() throws Exception {
        System.out.println("--- DEMO 6: CPU-bound vs IO-bound với Virtual Thread ---");

        int ioTasks  = 200;
        int cpuTasks = Runtime.getRuntime().availableProcessors() * 2;

        long vtIO, ptIO, vtCPU, ptCPU;

        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService ptPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {

            // IO-bound: virtual thread WINS
            vtIO  = runConcurrent(vtPool, ioTasks,  () -> Thread.sleep(100));
            ptIO  = runConcurrent(ptPool, ioTasks,  () -> Thread.sleep(100));

            // CPU-bound: virtual thread KHÔNG giúp được
            vtCPU = runConcurrent(vtPool, cpuTasks, () -> IntStream.range(0, 1_000_000).asLongStream().sum());
            ptCPU = runConcurrent(ptPool, cpuTasks, () -> IntStream.range(0, 1_000_000).asLongStream().sum());
        }

        System.out.println("  IO-bound (" + ioTasks + " tasks × sleep 100ms):");
        System.out.println("  Virtual thread pool:  " + vtIO  + "ms  ← WINNER");
        System.out.println("  Platform thread pool: " + ptIO  + "ms");

        System.out.println("\n  CPU-bound (" + cpuTasks + " tasks × sum 1M numbers):");
        System.out.println("  Virtual thread pool:  " + vtCPU + "ms");
        System.out.println("  Platform thread pool: " + ptCPU + "ms  ← tương đương (CPU không được giải phóng)");

        System.out.println();
        System.out.println("=== TỔNG KẾT MODULE 2 — CONCURRENCY & MULTITHREADING ===");
        System.out.println();
        System.out.println("  2.1 JMM        → volatile fix visibility, AtomicInteger fix atomicity");
        System.out.println("  2.2 Synchronized → intrinsic lock, deadlock = lock ordering fix");
        System.out.println("  2.3 Lock        → tryLock avoid deadlock, ReadWriteLock read-heavy");
        System.out.println("  2.4 Executor    → pool sizing nCPU×(1+W/C), ForkJoin work-stealing");
        System.out.println("  2.5 CF          → thenCompose chain, allOf fan-in, anyOf hedged");
        System.out.println("  2.6 VirtualThread → IO-bound scaling, pinning = dùng ReentrantLock");
        System.out.println();
        System.out.println("  SA DECISION TREE:");
        System.out.println("  Cần scale IO?        → Virtual Thread (Spring Boot 3.2: 1 dòng config)");
        System.out.println("  Cần async pipeline?  → CompletableFuture / Reactive");
        System.out.println("  Cần divide-conquer?  → ForkJoinPool");
        System.out.println("  Shared mutable state → synchronized/ReentrantLock + minimize scope");
        System.out.println("  Single variable?     → AtomicInteger/AtomicReference");
        System.out.println();
        System.out.println("  → Bài tiếp: Module 3 — Collections & Generics nâng cao");
    }
}
