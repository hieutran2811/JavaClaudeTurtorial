package org.example.concurrency;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
 *   Nếu dùng Java 17: các API Virtual Thread chưa có,
 *   chỉ chạy được demo 1-2. Demo 3-5 cần --enable-preview (Java 19/20)
 *   hoặc Java 21 GA.
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
        Thread platformThread = new Thread(() -> {
            System.out.println("  Platform thread: " + Thread.currentThread()
                    + " | virtual=" + Thread.currentThread().isVirtual());
        });
        platformThread.start();
        platformThread.join();

        // Virtual thread — lightweight, JVM-managed
        Thread virtualThread = Thread.ofVirtual()
            .name("my-virtual-thread")
            .start(() -> {
                System.out.println("  Virtual thread:  " + Thread.currentThread()
                        + " | virtual=" + Thread.currentThread().isVirtual());
            });
        virtualThread.join();

        // So sánh chi phí tạo thread
        int COUNT = 10_000;

        long start = System.currentTimeMillis();
        List<Thread> platformThreads = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            Thread t = new Thread(() -> { /* no-op */ });
            platformThreads.add(t);
            t.start();
        }
        for (Thread t : platformThreads) t.join();
        long platformTime = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        List<Thread> virtualThreads = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            Thread t = Thread.ofVirtual().start(() -> { /* no-op */ });
            virtualThreads.add(t);
        }
        for (Thread t : virtualThreads) t.join();
        long virtualTime = System.currentTimeMillis() - start;

        System.out.println("  Tạo " + COUNT + " platform threads: " + platformTime + "ms");
        System.out.println("  Tạo " + COUNT + " virtual threads:  " + virtualTime + "ms");
        System.out.println("  Virtual thread nhanh hơn ~" + (platformTime / Math.max(virtualTime, 1)) + "x\n");
    }

    // ================================================================
    // DEMO 2: Massive Concurrency — 100.000 concurrent "requests"
    // ================================================================

    /**
     * Bài toán kinh điển: Server phải xử lý 100.000 request đồng thời,
     * mỗi request mất 100ms chờ I/O (DB, HTTP call...).
     *
     * Với Platform Thread:
     *   100.000 thread × 1MB stack = 100GB RAM → IMPOSSIBLE
     *   ThreadPool 500 thread: 100.000 / 500 = 200 batch × 100ms = 20 giây
     *
     * Với Virtual Thread:
     *   100.000 virtual thread × ~200 bytes = ~20MB → trivial
     *   Tất cả "chờ" I/O đồng thời, JVM tự unmount → platform thread free
     *   → Thời gian xấp xỉ 100ms (cộng scheduling overhead)
     *
     * Đây chính là lý do Spring Boot, Quarkus, Micronaut đều adopt virtual thread.
     */
    static void demo2_MassiveConcurrency() throws Exception {
        System.out.println("--- DEMO 2: Massive Concurrency ---");

        int REQUESTS = 10_000; // Dùng 10k để demo nhanh (thực tế có thể dùng 100k+)
        int SIMULATED_IO_MS = 100;

        // Platform thread pool — giới hạn bởi thread count
        int poolSize = Runtime.getRuntime().availableProcessors() * 2;
        ExecutorService platformPool = Executors.newFixedThreadPool(poolSize);

        long start = System.currentTimeMillis();
        CountDownLatch latch1 = new CountDownLatch(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            platformPool.submit(() -> {
                try { Thread.sleep(SIMULATED_IO_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                latch1.countDown();
            });
        }
        latch1.await(120, TimeUnit.SECONDS);
        platformPool.shutdown();
        long platformTime = System.currentTimeMillis() - start;

        // Virtual thread executor — 1 virtual thread per task, không giới hạn
        ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();

        start = System.currentTimeMillis();
        CountDownLatch latch2 = new CountDownLatch(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            virtualPool.submit(() -> {
                try { Thread.sleep(SIMULATED_IO_MS); }  // Blocking OK! JVM tự unmount
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                latch2.countDown();
            });
        }
        latch2.await(30, TimeUnit.SECONDS);
        virtualPool.shutdown();
        long virtualTime = System.currentTimeMillis() - start;

        System.out.println("  " + REQUESTS + " requests, mỗi request I/O " + SIMULATED_IO_MS + "ms:");
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

        // Cách 1: Thread.ofVirtual()
        Thread t1 = Thread.ofVirtual()
            .name("vt-worker-", 1)   // Auto-increment name: vt-worker-1, vt-worker-2...
            .start(() -> System.out.println("  [1] Thread.ofVirtual(): " + Thread.currentThread().getName()));
        t1.join();

        // Cách 2: startVirtualThread — shortcut
        Thread t2 = Thread.startVirtualThread(() ->
            System.out.println("  [2] startVirtualThread: " + Thread.currentThread().getName()));
        t2.join();

        // Cách 3: ExecutorService — tương thích với code cũ dùng ExecutorService
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
        t4.start(); t4.join();

        // Virtual thread là daemon
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
     *   Java 23+ sẽ finalize. Demo này minh hoạ concept, chạy được với Java 21+preview.
     */
    static void demo4_StructuredConcurrency() throws Exception {
        System.out.println("--- DEMO 4: Structured Concurrency ---");

        // Pattern tương đương StructuredTaskScope với Java 21 stable API
        // (Dùng CompletableFuture để minh hoạ concept — behavior giống nhau)

        System.out.println("  Concept: StructuredTaskScope.ShutdownOnFailure");
        System.out.println("  (Minh hoạ bằng CompletableFuture — concept tương đương)");

        // ShutdownOnFailure pattern: cần TẤT CẢ task thành công
        // Nếu 1 fail → cancel các task còn lại
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.currentTimeMillis();

            Future<String> userFuture    = exec.submit(() -> { Thread.sleep(80);  return "Alice"; });
            Future<Integer> orderFuture  = exec.submit(() -> { Thread.sleep(120); return 5; });
            Future<Double>  balanceFuture = exec.submit(() -> { Thread.sleep(60);  return 1500.0; });

            try {
                String user    = userFuture.get(2, TimeUnit.SECONDS);
                int orders     = orderFuture.get(2, TimeUnit.SECONDS);
                double balance = balanceFuture.get(2, TimeUnit.SECONDS);

                System.out.printf("  ShutdownOnFailure result: user=%s, orders=%d, balance=%.1f (%dms)%n",
                        user, orders, balance, System.currentTimeMillis() - start);
            } catch (Exception e) {
                userFuture.cancel(true);
                orderFuture.cancel(true);
                balanceFuture.cancel(true);
                System.out.println("  1 subtask fail → cancel tất cả: " + e.getMessage());
            }
        }

        // ShutdownOnSuccess pattern: chỉ cần 1 task thành công (hedged request)
        System.out.println("  Concept: StructuredTaskScope.ShutdownOnSuccess (hedged request)");
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.currentTimeMillis();

            // Gửi đến 3 replica — lấy replica nào trả về trước
            Future<String> replica1 = exec.submit(() -> { Thread.sleep(300); return "replica-1"; });
            Future<String> replica2 = exec.submit(() -> { Thread.sleep(80);  return "replica-2"; });
            Future<String> replica3 = exec.submit(() -> { Thread.sleep(200); return "replica-3"; });

            // anyOf tương đương ShutdownOnSuccess
            @SuppressWarnings("unchecked")
            CompletableFuture<String>[] cfs = new CompletableFuture[]{
                CompletableFuture.supplyAsync(() -> { try { return replica1.get(); } catch (Exception e) { throw new RuntimeException(e); } }),
                CompletableFuture.supplyAsync(() -> { try { return replica2.get(); } catch (Exception e) { throw new RuntimeException(e); } }),
                CompletableFuture.supplyAsync(() -> { try { return replica3.get(); } catch (Exception e) { throw new RuntimeException(e); } })
            };
            String winner = (String) CompletableFuture.anyOf(cfs).get();
            replica1.cancel(true); replica2.cancel(true); replica3.cancel(true);

            System.out.println("  ShutdownOnSuccess: '" + winner + "' về trước ("
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

        int TASKS = 200;
        int SLEEP_MS = 50;

        // Không pin: Thread.sleep() trong virtual thread → unmount bình thường
        ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor();
        long start = System.currentTimeMillis();
        CountDownLatch latch1 = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            vtPool.submit(() -> {
                try { Thread.sleep(SLEEP_MS); } // Thread.sleep → unmount, NO PIN
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                latch1.countDown();
            });
        }
        latch1.await(30, TimeUnit.SECONDS);
        vtPool.shutdown();
        long noPin = System.currentTimeMillis() - start;

        // Mô phỏng pin: synchronized + sleep (trong Java 21 sẽ pin)
        Object lock = new Object();
        vtPool = Executors.newVirtualThreadPerTaskExecutor();
        start = System.currentTimeMillis();
        CountDownLatch latch2 = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            vtPool.submit(() -> {
                synchronized (lock) {             // synchronized → PIN khi blocking bên trong
                    try { Thread.sleep(SLEEP_MS); } // ← Thread bị pin trong suốt thời gian này
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                latch2.countDown();
            });
        }
        latch2.await(30, TimeUnit.SECONDS);
        vtPool.shutdown();
        long withPin = System.currentTimeMillis() - start;

        // Fix: dùng ReentrantLock thay synchronized
        ReentrantLock reentrantLock = new ReentrantLock();
        vtPool = Executors.newVirtualThreadPerTaskExecutor();
        start = System.currentTimeMillis();
        CountDownLatch latch3 = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            vtPool.submit(() -> {
                reentrantLock.lock();
                try { Thread.sleep(SLEEP_MS); }   // ReentrantLock → KHÔNG pin → unmount được
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { reentrantLock.unlock(); }
                latch3.countDown();
            });
        }
        latch3.await(30, TimeUnit.SECONDS);
        vtPool.shutdown();
        long lockFixed = System.currentTimeMillis() - start;

        System.out.println("  " + TASKS + " virtual threads, sleep " + SLEEP_MS + "ms mỗi task:");
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

        int TASKS = 200;

        // IO-bound: virtual thread WINS
        ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService ptPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        long start = System.currentTimeMillis();
        CountDownLatch latch1 = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            vtPool.submit(() -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}  // IO simulation
                latch1.countDown();
            });
        }
        latch1.await(30, TimeUnit.SECONDS);
        long vtIO = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        CountDownLatch latch2 = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            ptPool.submit(() -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}  // IO simulation
                latch2.countDown();
            });
        }
        latch2.await(30, TimeUnit.SECONDS);
        long ptIO = System.currentTimeMillis() - start;

        System.out.println("  IO-bound (" + TASKS + " tasks × sleep 100ms):");
        System.out.println("  Virtual thread pool: " + vtIO + "ms  ← WINNER");
        System.out.println("  Platform thread pool: " + ptIO + "ms");

        // CPU-bound: virtual thread KHÔNG giúp được
        int cpuTasks = Runtime.getRuntime().availableProcessors() * 2;

        start = System.currentTimeMillis();
        CountDownLatch latch3 = new CountDownLatch(cpuTasks);
        for (int i = 0; i < cpuTasks; i++) {
            vtPool.submit(() -> {
                long sum = IntStream.range(0, 1_000_000).asLongStream().sum(); // CPU work
                latch3.countDown();
            });
        }
        latch3.await(30, TimeUnit.SECONDS);
        long vtCPU = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        CountDownLatch latch4 = new CountDownLatch(cpuTasks);
        for (int i = 0; i < cpuTasks; i++) {
            ptPool.submit(() -> {
                long sum = IntStream.range(0, 1_000_000).asLongStream().sum(); // CPU work
                latch4.countDown();
            });
        }
        latch4.await(30, TimeUnit.SECONDS);
        long ptCPU = System.currentTimeMillis() - start;

        System.out.println("\n  CPU-bound (" + cpuTasks + " tasks × sum 1M numbers):");
        System.out.println("  Virtual thread pool:  " + vtCPU + "ms");
        System.out.println("  Platform thread pool: " + ptCPU + "ms  ← tương đương (CPU không được giải phóng)");

        vtPool.shutdown(); ptPool.shutdown();

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
