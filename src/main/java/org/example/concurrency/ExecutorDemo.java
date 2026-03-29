package org.example.concurrency;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * ============================================================
 * BÀI 2.4 — ThreadPool, ExecutorService, ForkJoinPool
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Tại sao KHÔNG nên tạo Thread thủ công — dùng ThreadPool
 *   2. ThreadPool sizing: CPU-bound vs IO-bound (công thức thực chiến)
 *   3. ExecutorService lifecycle đúng cách (submit, shutdown, awaitTermination)
 *   4. Các loại Executor built-in và khi nào dùng cái nào
 *   5. ForkJoinPool & work-stealing — chia bài toán nhỏ song song
 *   6. ThreadPool pitfalls: rejection, thread leak, task queue overflow
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.concurrency.ExecutorDemo"
 * ============================================================
 */
public class ExecutorDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 2.4: ThreadPool, ExecutorService, ForkJoinPool ===\n");

        demo1_WhyThreadPool();
        demo2_ThreadPoolSizing();
        demo3_ExecutorServiceLifecycle();
        demo4_BuiltInExecutors();
        demo5_ForkJoinPool();
        demo6_Pitfalls();

        System.out.println("\n=== KẾT THÚC BÀI 2.4 ===");
    }

    // ================================================================
    // DEMO 1: Tại sao KHÔNG tạo Thread thủ công
    // ================================================================

    /**
     * Thread thủ công (new Thread()) có vấn đề:
     *   - Tạo thread tốn ~1MB stack memory mỗi thread
     *   - Context switch giữa quá nhiều thread = overhead lớn
     *   - Không giới hạn số thread → server có thể bị OOM hoặc thrash
     *   - Không có lifecycle management (ai dọn thread khi xong?)
     *
     * ThreadPool giải quyết:
     *   - Tái sử dụng thread (reuse) → tránh chi phí tạo/huỷ
     *   - Giới hạn tối đa số thread đang chạy
     *   - Task queue: task vượt capacity được xếp hàng
     *   - Lifecycle rõ ràng: submit → execute → done
     *
     * SA INSIGHT: "Every thread is a resource. Treat them like DB connections — pool them."
     */
    static void demo1_WhyThreadPool() throws Exception {
        System.out.println("--- DEMO 1: Thread thủ công vs ThreadPool ---");

        int TASKS = 50;

        // CÁCH SAI: tạo 50 thread thủ công
        long start = System.currentTimeMillis();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(10); } catch (InterruptedException e) {}
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        System.out.println("  50 Thread thủ công: " + (System.currentTimeMillis() - start) + "ms"
                + "  (tạo/huỷ 50 thread, stack memory ~50MB)");

        // CÁCH ĐÚNG: ThreadPool với 8 thread, tái sử dụng
        start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(TASKS);
        for (int i = 0; i < TASKS; i++) {
            pool.submit(() -> {
                try { Thread.sleep(10); } catch (InterruptedException e) {}
                latch.countDown();
            });
        }
        latch.await();
        shutdownGracefully(pool);
        System.out.println("  ThreadPool (8 threads): " + (System.currentTimeMillis() - start) + "ms"
                + "  (8 thread tái sử dụng, stack ~8MB)\n");
    }

    // ================================================================
    // DEMO 2: ThreadPool Sizing — Công thức thực chiến
    // ================================================================

    /**
     * CÔNG THỨC SIZING (từ "Java Concurrency in Practice" — Brian Goetz):
     *
     *   CPU-bound tasks (tính toán, không chờ I/O):
     *     nThreads = nCPU + 1
     *     Lý do: 1 extra thread để tận dụng khi thread khác bị page fault/GC pause
     *
     *   IO-bound tasks (HTTP call, DB query, file read):
     *     nThreads = nCPU × (1 + W/C)
     *     W = thời gian chờ I/O (wait time)
     *     C = thời gian tính toán (compute time)
     *
     *     Ví dụ: API call 100ms, xử lý response 10ms
     *     → W/C = 10 → nThreads = nCPU × 11
     *     Trên 8-core → ~88 threads là hợp lý
     *
     * THỰC TẾ: Đây là điểm khởi đầu, phải đo thực tế bằng load test.
     * Dùng async/reactive (Bài 2.5, 2.6) để giải phóng thread hoàn toàn.
     *
     * SA INSIGHT: Sizing sai = hoặc CPU idle (under-sized) hoặc context switch hell (over-sized).
     *             Spring Boot mặc định: Tomcat = 200 threads, HikariCP = 10 connections.
     *             Đây là lý do pool mismatch gây bottleneck phổ biến.
     */
    static void demo2_ThreadPoolSizing() throws Exception {
        System.out.println("--- DEMO 2: ThreadPool Sizing ---");

        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("  CPU cores: " + cpuCores);

        // CPU-bound: sort, encrypt, compress
        int cpuBoundSize = cpuCores + 1;
        System.out.println("  CPU-bound pool size: " + cpuBoundSize + " (nCPU + 1)");

        // IO-bound: HTTP call, DB query
        double waitMs = 100.0, computeMs = 10.0;
        int ioBoundSize = (int) (cpuCores * (1 + waitMs / computeMs));
        System.out.println("  IO-bound pool size:  " + ioBoundSize
                + " (nCPU × (1 + " + (int)waitMs + "ms/" + (int)computeMs + "ms))");

        // Đo thực tế: cùng 200 IO tasks với pool size khác nhau
        System.out.println("\n  Benchmark 200 IO-tasks (each 20ms) với pool size khác nhau:");

        int[] sizes = {2, cpuCores, ioBoundSize, ioBoundSize * 2};
        for (int size : sizes) {
            long elapsed = runIOTasks(size, 200);
            String label = size == cpuCores ? " ← CPU-bound size (quá nhỏ cho IO)"
                         : size == ioBoundSize ? " ← IO-bound formula"
                         : "";
            System.out.println("    pool=" + String.format("%3d", size) + " threads: " + elapsed + "ms" + label);
        }
        System.out.println();
    }

    static long runIOTasks(int poolSize, int taskCount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        CountDownLatch latch = new CountDownLatch(taskCount);
        long start = System.currentTimeMillis();

        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                try { Thread.sleep(20); } catch (InterruptedException e) {} // Giả lập IO
                latch.countDown();
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        shutdownGracefully(pool);
        return System.currentTimeMillis() - start;
    }

    // ================================================================
    // DEMO 3: ExecutorService Lifecycle đúng cách
    // ================================================================

    /**
     * ExecutorService có 3 trạng thái:
     *   RUNNING   → nhận task mới, xử lý task trong queue
     *   SHUTDOWN  → KHÔNG nhận task mới, nhưng hoàn thành task đang có
     *   TERMINATED → tất cả task xong, tất cả thread đã stop
     *
     * Cách shutdown đúng (từ Javadoc):
     *   1. pool.shutdown()              — gửi tín hiệu stop, không block
     *   2. pool.awaitTermination(...)   — chờ task xong (có timeout)
     *   3. pool.shutdownNow()           — force kill nếu timeout (interrupt các task đang chạy)
     *
     * Future: đại diện cho kết quả của task bất đồng bộ
     *   future.get()         — block chờ kết quả
     *   future.get(timeout)  — block có timeout → tránh chờ vô tận
     *   future.cancel()      — hủy task nếu chưa chạy
     *   future.isDone()      — kiểm tra không block
     */
    static void demo3_ExecutorServiceLifecycle() throws Exception {
        System.out.println("--- DEMO 3: ExecutorService Lifecycle & Future ---");

        ExecutorService pool = Executors.newFixedThreadPool(4);

        // submit Callable → nhận Future với kết quả trả về
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            Future<Integer> future = pool.submit(() -> {
                Thread.sleep(taskId * 20);   // Task lâu hơn theo ID
                return taskId * taskId;      // Trả về i²
            });
            futures.add(future);
        }

        // invokeAll — submit tất cả và chờ tất cả xong
        List<Callable<String>> callables = List.of(
            () -> "Task A done",
            () -> "Task B done",
            () -> "Task C done"
        );
        List<Future<String>> allResults = pool.invokeAll(callables, 5, TimeUnit.SECONDS);
        System.out.print("  invokeAll kết quả: ");
        for (Future<String> f : allResults) System.out.print(f.get() + " | ");
        System.out.println();

        // invokeAny — lấy kết quả của task nào xong trước
        String fastest = pool.invokeAny(List.of(
            () -> { Thread.sleep(100); return "slow"; },
            () -> { Thread.sleep(10);  return "fast"; },
            () -> { Thread.sleep(50);  return "medium"; }
        ));
        System.out.println("  invokeAny (ai về trước): " + fastest);

        // Đọc kết quả Future — với timeout để tránh block vô tận
        System.out.print("  Future results (i²): ");
        for (Future<Integer> f : futures) {
            try {
                System.out.print(f.get(1, TimeUnit.SECONDS) + " ");
            } catch (TimeoutException e) {
                System.out.print("TIMEOUT ");
                f.cancel(true);
            }
        }
        System.out.println();

        // Shutdown đúng cách
        shutdownGracefully(pool);
        System.out.println("  Pool terminated: " + pool.isTerminated() + "\n");
    }

    // ================================================================
    // DEMO 4: Các loại Executor built-in
    // ================================================================

    /**
     * Java cung cấp nhiều loại Executor qua Executors factory:
     *
     *   newFixedThreadPool(n)    — n thread cố định, queue không giới hạn
     *                             ✓ Workload đều, throughput ổn định
     *                             ✗ Queue có thể tràn memory nếu task đến nhanh hơn xử lý
     *
     *   newCachedThreadPool()    — Thread tạo theo demand, idle 60s thì huỷ
     *                             ✓ Task burst ngắn, I/O-heavy
     *                             ✗ Không giới hạn thread → có thể tạo hàng nghìn thread
     *
     *   newSingleThreadExecutor() — 1 thread, đảm bảo task chạy tuần tự
     *                             ✓ Ordered processing, event loop, sequential consumer
     *
     *   newScheduledThreadPool(n) — Chạy task theo schedule (fixed rate hoặc delay)
     *                             ✓ Periodic jobs, retry với delay
     *
     *   newVirtualThreadPerTaskExecutor() — Java 21+, mỗi task 1 virtual thread
     *                             ✓ Massive IO concurrency (xem bài 2.6)
     *
     * SA INSIGHT: Trong production, KHÔNG dùng Executors.newFixedThreadPool với
     * unbounded queue. Dùng ThreadPoolExecutor trực tiếp để kiểm soát queue size
     * và rejection policy — tránh OOM khi tải đột biến.
     */
    static void demo4_BuiltInExecutors() throws Exception {
        System.out.println("--- DEMO 4: Built-in Executor Types ---");

        // SingleThreadExecutor: task chạy tuần tự, thứ tự đảm bảo
        ExecutorService single = Executors.newSingleThreadExecutor();
        for (int i = 1; i <= 4; i++) {
            final int n = i;
            single.submit(() -> System.out.println("  SingleThread task " + n + " — thread: " + Thread.currentThread().getName()));
        }
        shutdownGracefully(single);

        // ScheduledExecutorService: delay và periodic
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Chạy 1 lần sau 100ms
        scheduler.schedule(() -> System.out.println("  Scheduled (delay 100ms): " + System.currentTimeMillis() % 10000), 100, TimeUnit.MILLISECONDS);

        // Chạy định kỳ mỗi 200ms (fixed rate = tính từ lần chạy trước BẮT ĐẦU)
        AtomicInteger count = new AtomicInteger(0);
        ScheduledFuture<?> periodic = scheduler.scheduleAtFixedRate(() -> {
            if (count.incrementAndGet() <= 3)
                System.out.println("  Periodic tick #" + count.get());
        }, 0, 200, TimeUnit.MILLISECONDS);

        Thread.sleep(700);
        periodic.cancel(false);
        shutdownGracefully(scheduler);

        // ThreadPoolExecutor trực tiếp — full control (production chuẩn)
        System.out.println("\n  ThreadPoolExecutor (production config):");
        ThreadPoolExecutor customPool = new ThreadPoolExecutor(
            2,                              // corePoolSize: luôn duy trì 2 thread
            8,                              // maximumPoolSize: tối đa 8 khi queue đầy
            30, TimeUnit.SECONDS,           // keepAliveTime: thread dư sẽ idle 30s rồi huỷ
            new ArrayBlockingQueue<>(50),   // bounded queue: tối đa 50 task xếp hàng
            new ThreadFactory() {           // custom thread name — quan trọng để debug
                final AtomicInteger idx = new AtomicInteger(0);
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "worker-" + idx.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // Rejection: task bị reject → caller tự chạy
        );

        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            customPool.submit(() -> {
                System.out.println("  Running on: " + Thread.currentThread().getName());
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        shutdownGracefully(customPool);
        System.out.println();
    }

    // ================================================================
    // DEMO 5: ForkJoinPool & Work-Stealing
    // ================================================================

    /**
     * ForkJoinPool: Thiết kế cho bài toán "divide-and-conquer" (chia để trị)
     *
     * CÁCH HOẠT ĐỘNG (Work-Stealing):
     *   - Mỗi thread có 1 deque (double-ended queue) riêng
     *   - Thread đẩy task vào đầu deque của mình (LIFO — cache-friendly)
     *   - Khi thread hết việc → nó STEAL task từ ĐUÔI deque của thread khác
     *   - Kết quả: không bao giờ có thread nhàn rỗi khi còn task
     *
     * RecursiveTask<T>:  task có return value → dùng compute()
     * RecursiveAction:   task không return   → dùng compute()
     *
     * fork()  — submit subtask vào pool (bất đồng bộ)
     * join()  — chờ subtask hoàn thành và lấy kết quả
     * invoke()— fork + join trong 1 lần gọi
     *
     * SA INSIGHT: Parallel streams (bài 3.4) dùng ForkJoinPool.commonPool() bên dưới.
     * Tránh blocking I/O trong ForkJoin task — sẽ block thread và giảm hiệu năng.
     * ForkJoin tốt nhất cho CPU-bound work với data locality tốt (array, tree).
     */
    static void demo5_ForkJoinPool() throws Exception {
        System.out.println("--- DEMO 5: ForkJoinPool & Work-Stealing ---");

        int[] data = IntStream.rangeClosed(1, 1_000_000).toArray(); // 1 → 1,000,000

        // Sequential sum
        long start = System.currentTimeMillis();
        long seqSum = 0;
        for (int v : data) seqSum += v;
        long seqTime = System.currentTimeMillis() - start;

        // Parallel sum với ForkJoin
        ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        start = System.currentTimeMillis();
        long parSum = pool.invoke(new SumTask(data, 0, data.length));
        long parTime = System.currentTimeMillis() - start;
        pool.shutdown();

        System.out.println("  Array: 1 → 1,000,000");
        System.out.println("  Sequential sum: " + seqSum + " (" + seqTime + "ms)");
        System.out.println("  ForkJoin sum:   " + parSum + " (" + parTime + "ms)");
        System.out.println("  Kết quả khớp: " + (seqSum == parSum ? "✓" : "✗ BUG!"));

        // Note: ForkJoin overhead lớn với array nhỏ.
        // ForkJoin thường win khi: dữ liệu lớn + chia nhỏ phù hợp + CPU nhiều core.
        System.out.println("  (ForkJoin có overhead — tốt nhất với large data & nhiều core)\n");
    }

    /**
     * SumTask: Tính tổng mảng theo chiến lược chia đôi đệ quy.
     * Khi đoạn ngắn (< THRESHOLD) → tính trực tiếp (base case).
     * Khi dài → chia đôi → fork 2 subtask → join lấy kết quả.
     */
    static class SumTask extends RecursiveTask<Long> {
        private static final int THRESHOLD = 10_000; // Chia nhỏ đến khi < 10k phần tử
        private final int[] array;
        private final int from, to;

        SumTask(int[] array, int from, int to) {
            this.array = array; this.from = from; this.to = to;
        }

        @Override
        protected Long compute() {
            int size = to - from;
            if (size <= THRESHOLD) {
                // Base case: tính trực tiếp
                long sum = 0;
                for (int i = from; i < to; i++) sum += array[i];
                return sum;
            }
            // Divide: chia đôi
            int mid = from + size / 2;
            SumTask left  = new SumTask(array, from, mid);
            SumTask right = new SumTask(array, mid, to);

            // Fork: chạy left bất đồng bộ, right chạy trên thread hiện tại (optimization)
            left.fork();
            long rightResult = right.compute();
            long leftResult  = left.join();   // Chờ left xong

            return leftResult + rightResult;
        }
    }

    // ================================================================
    // DEMO 6: Pitfalls phổ biến khi dùng ThreadPool
    // ================================================================

    /**
     * PITFALL 1 — Thread Leak: Không shutdown pool → thread sống mãi dù app "xong"
     * PITFALL 2 — Task Rejection: Queue đầy → RejectedExecutionException
     * PITFALL 3 — Unbounded Queue: newFixedThreadPool dùng LinkedBlockingQueue không giới hạn
     *             → Task tích lũy vô hạn → OOM khi tải cao
     * PITFALL 4 — Exception nuốt im lặng: Exception trong submit() bị nuốt nếu không gọi future.get()
     * PITFALL 5 — ThreadLocal leak: ThreadLocal set trong task, không remove → data bị dùng lại
     *             bởi task tiếp theo trên cùng thread (reuse!)
     */
    static void demo6_Pitfalls() throws Exception {
        System.out.println("--- DEMO 6: ThreadPool Pitfalls ---");

        // PITFALL 4: Exception bị nuốt im lặng
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> silentFail = pool.submit(() -> {
            throw new RuntimeException("Lỗi này bị nuốt nếu không gọi future.get()!");
        });

        // Không gọi future.get() → exception biến mất, không ai biết task fail

        Future<?> explicitCheck = pool.submit(() -> {
            throw new RuntimeException("Lỗi được bắt đúng cách");
        });

        try {
            explicitCheck.get(); // Chỉ khi gọi get() mới thấy exception
        } catch (ExecutionException e) {
            System.out.println("  Pitfall 4 — Exception bị nuốt: " + e.getCause().getMessage());
        }

        // PITFALL 5: ThreadLocal leak
        ThreadLocal<String> requestId = new ThreadLocal<>();
        Future<?> task1 = pool.submit(() -> {
            requestId.set("req-abc-123");
            // Quên remove() → thread trả về pool với requestId còn đó
        });
        task1.get();

        Future<String> task2 = pool.submit(() -> {
            // Thread tái sử dụng → vẫn còn requestId của task1!
            String leaked = requestId.get();
            requestId.remove(); // Phải remove sau khi dùng
            return leaked;
        });

        String leakedValue = task2.get();
        System.out.println("  Pitfall 5 — ThreadLocal leak: task2 đọc được requestId = \""
                + leakedValue + "\" (từ task1!)");
        System.out.println("             Fix: LUÔN gọi threadLocal.remove() trong finally");

        shutdownGracefully(pool);

        // PITFALL 2: Task rejection khi queue đầy
        ThreadPoolExecutor tightPool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),         // Queue chỉ chứa 2 task
            new ThreadPoolExecutor.AbortPolicy() // Reject bằng exception (mặc định)
        );

        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            try {
                tightPool.submit(() -> { try { Thread.sleep(100); } catch (InterruptedException e) {} });
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        shutdownGracefully(tightPool);
        System.out.println("  Pitfall 2 — Rejection: " + rejected + "/10 tasks bị reject (pool=1, queue=2)");
        System.out.println("             Fix: dùng CallerRunsPolicy hoặc tăng queue/pool size");

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 2.4 ===");
        System.out.println("  ✓ ThreadPool: reuse thread, giới hạn concurrency, quản lý lifecycle");
        System.out.println("  ✓ Sizing: CPU-bound = nCPU+1 | IO-bound = nCPU × (1 + W/C)");
        System.out.println("  ✓ Shutdown đúng: shutdown() → awaitTermination() → shutdownNow()");
        System.out.println("  ✓ ForkJoin: divide-and-conquer, work-stealing, CPU-bound tasks");
        System.out.println("  ✓ Pitfalls: exception nuốt im lặng, ThreadLocal leak, unbounded queue");
        System.out.println("  → Bài tiếp: 2.5 CompletableFutureDemo — async chaining, thenCompose, exceptionally");
    }

    // ================================================================
    // HELPER
    // ================================================================

    /**
     * Shutdown đúng cách theo Javadoc — pattern này dùng lại ở mọi nơi.
     */
    static void shutdownGracefully(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();                           // Force kill sau 5s
                pool.awaitTermination(2, TimeUnit.SECONDS);  // Chờ interrupt xong
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
