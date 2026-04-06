package org.example.patterns;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 4.4 — Concurrent Patterns thực chiến
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Producer-Consumer — variants: single/multi producer, priority, poison pill
 *   2. Thread-per-Request vs Reactor — mô hình server, trade-off thực tế
 *   3. Active Object — async method call, decouple invocation từ execution
 *   4. Half-Sync/Half-Async — giao tiếp giữa async I/O và sync business logic
 *   5. Read-Write Lock Pattern & Object Pool
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.patterns.ConcurrentPatternsDemo"
 * ============================================================
 */
public class ConcurrentPatternsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 4.4: Concurrent Patterns ===\n");

        demo1_ProducerConsumer();
        demo2_ThreadPerRequestVsReactor();
        demo3_ActiveObject();
        demo4_HalfSyncHalfAsync();
        demo5_ObjectPool();

        System.out.println("\n=== KẾT THÚC BÀI 4.4 ===");
    }

    // ================================================================
    // DEMO 1: Producer-Consumer — Variants thực tế
    // ================================================================

    /**
     * Producer-Consumer: tách producer (tạo data) khỏi consumer (xử lý data)
     *   qua một buffer (BlockingQueue). Giải quyết speed mismatch giữa 2 bên.
     *
     * VARIANTS:
     *   1. Single Producer / Single Consumer  — đơn giản nhất
     *   2. Multi Producer / Multi Consumer    — pipeline thực tế
     *   3. Priority Queue                     — high-priority task trước
     *   4. Poison Pill shutdown               — signal dừng gracefully
     *   5. Bounded Buffer back-pressure       — throttle producer tự nhiên
     *
     * BOUNDED vs UNBOUNDED:
     *   LinkedBlockingQueue()      — unbounded, OOM risk khi consumer chậm
     *   ArrayBlockingQueue(n)      — bounded, put() block → back-pressure
     *   → Luôn dùng bounded trong production!
     *
     * SA INSIGHT: Kafka là Producer-Consumer pattern ở quy mô distributed.
     *   Topic = BlockingQueue | Partition = parallelism unit
     *   Consumer group = multi-consumer | Offset = ACK mechanism
     *   Back-pressure = consumer.pause() khi xử lý không kịp
     */
    static void demo1_ProducerConsumer() throws Exception {
        System.out.println("--- DEMO 1: Producer-Consumer Variants ---");

        // Variant 1: Multi Producer + Multi Consumer + Poison Pill shutdown
        System.out.println("  Multi Producer / Multi Consumer + Poison Pill:");
        final int PRODUCERS = 2, CONSUMERS = 3, TASKS_PER_PRODUCER = 5;
        final Task POISON_PILL = new Task(-1, "POISON", Priority.LOW); // sentinel

        BlockingQueue<Task> queue = new ArrayBlockingQueue<>(10);
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);

        // Producers
        ExecutorService producerPool = Executors.newFixedThreadPool(PRODUCERS);
        for (int p = 0; p < PRODUCERS; p++) {
            final int pid = p + 1;
            producerPool.submit(() -> {
                for (int i = 1; i <= TASKS_PER_PRODUCER; i++) {
                    try {
                        Task task = new Task(pid * 100 + i, "P" + pid + "-Task" + i,
                            i % 3 == 0 ? Priority.HIGH : Priority.NORMAL);
                        queue.put(task); // blocks if full — back-pressure
                        produced.incrementAndGet();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
        }

        // Consumers phải khởi động TRƯỚC khi gửi poison pill
        ExecutorService consumerPool = Executors.newFixedThreadPool(CONSUMERS);
        CountDownLatch done = new CountDownLatch(CONSUMERS);
        for (int c = 0; c < CONSUMERS; c++) {
            final int cid = c + 1;
            consumerPool.submit(() -> {
                while (true) {
                    try {
                        Task task = queue.take();
                        if (task == POISON_PILL) { done.countDown(); return; }
                        sleep(20); // simulate processing
                        consumed.incrementAndGet();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
        }

        // Shutdown: chờ producer xong rồi mới gửi poison pill
        producerPool.shutdown();
        producerPool.awaitTermination(5, TimeUnit.SECONDS);
        for (int c = 0; c < CONSUMERS; c++) queue.put(POISON_PILL);

        done.await(10, TimeUnit.SECONDS);
        consumerPool.shutdown();
        System.out.println("  Produced: " + produced.get() + " | Consumed: " + consumed.get()
                + " | Queue empty: " + queue.isEmpty());

        // Variant 2: Priority-based processing
        System.out.println("\n  Priority Queue (HIGH tasks first):");
        PriorityBlockingQueue<Task> priorityQueue = new PriorityBlockingQueue<>(20,
            Comparator.comparing((Task t) -> t.priority).reversed()); // HIGH first

        priorityQueue.offer(new Task(1, "normal-1",  Priority.NORMAL));
        priorityQueue.offer(new Task(2, "high-1",    Priority.HIGH));
        priorityQueue.offer(new Task(3, "low-1",     Priority.LOW));
        priorityQueue.offer(new Task(4, "high-2",    Priority.HIGH));
        priorityQueue.offer(new Task(5, "normal-2",  Priority.NORMAL));

        System.out.print("  Processing order: ");
        while (!priorityQueue.isEmpty()) {
            Task t = priorityQueue.poll();
            System.out.print(t.name + "[" + t.priority + "] ");
        }
        System.out.println();

        // Variant 3: Delay queue — retry with exponential backoff
        System.out.println("\n  DelayQueue — Retry with backoff:");
        DelayQueue<RetryTask> retryQueue = new DelayQueue<>();
        long now = System.currentTimeMillis();
        retryQueue.put(new RetryTask("failed-A", now + 0));    // retry immediately
        retryQueue.put(new RetryTask("failed-B", now + 100));  // retry after 100ms
        retryQueue.put(new RetryTask("failed-C", now + 200));  // retry after 200ms

        for (int i = 0; i < 3; i++) {
            RetryTask rt = retryQueue.take();
            System.out.println("  Retry: " + rt.name + " at +" + (System.currentTimeMillis() - now) + "ms");
        }
        System.out.println();
    }

    enum Priority { LOW, NORMAL, HIGH }
    record Task(int id, String name, Priority priority) {}

    static class RetryTask implements Delayed {
        final String name; final long readyAt;
        RetryTask(String name, long readyAt) { this.name = name; this.readyAt = readyAt; }
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(readyAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public int compareTo(Delayed o) {
            return Long.compare(readyAt, ((RetryTask) o).readyAt);
        }
    }

    // ================================================================
    // DEMO 2: Thread-per-Request vs Reactor Model
    // ================================================================

    /**
     * 2 MÔ HÌNH SERVER CHÍNH:
     *
     * THREAD-PER-REQUEST (Traditional):
     *   - Mỗi request = 1 thread (hoặc từ pool)
     *   - Thread block khi I/O: sleep, DB query, HTTP call
     *   - Simple to code: sequential, easy to debug
     *   - Giới hạn: pool size = max concurrent requests
     *   - Ví dụ: Spring MVC (Tomcat), classic JDBC
     *   - Sweet spot: N < 1.000 concurrent, CPU-bound or simple I/O
     *
     * REACTOR (Event-Loop / Non-blocking):
     *   - Ít thread (nCPU), không block
     *   - I/O hoàn thành → callback được gọi
     *   - Complex to code: callback hell, reactive chain
     *   - Scale: hàng chục nghìn concurrent với ít thread
     *   - Ví dụ: Spring WebFlux, Node.js, Netty, Vert.x
     *   - Sweet spot: N >> 10.000, I/O-bound, streaming
     *
     * VIRTUAL THREAD (Java 21) — Middle ground:
     *   - Code style: sequential (như Thread-per-request)
     *   - Scale: như Reactor (JVM unmount khi block)
     *   - Sweet spot: I/O-bound + code readability quan trọng
     *
     * SA INSIGHT: Không có winner tuyệt đối.
     *   Reactor có overhead cao hơn cho đơn giản request (context switching, callback)
     *   Thread-per-request có ceiling thấp hơn Reactor
     *   Virtual thread đang thu hẹp gap — nhiều team migrate Spring MVC → Virtual Thread
     *   thay vì Spring WebFlux để giữ code đơn giản mà vẫn scale tốt.
     */
    static void demo2_ThreadPerRequestVsReactor() throws Exception {
        System.out.println("--- DEMO 2: Thread-per-Request vs Reactor Model ---");

        int CONCURRENT_REQUESTS = 500;
        int IO_DELAY_MS = 50;

        // Thread-per-Request model
        ExecutorService threadPool = Executors.newFixedThreadPool(50); // traditional pool
        CountDownLatch latch1 = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger completed1 = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            threadPool.submit(() -> {
                sleep(IO_DELAY_MS); // blocking I/O simulation — thread bị giữ
                completed1.incrementAndGet();
                latch1.countDown();
            });
        }
        latch1.await(30, TimeUnit.SECONDS);
        long tprTime = System.currentTimeMillis() - start;
        threadPool.shutdown();

        // Reactor-like model (simulated với CompletableFuture + small pool)
        ExecutorService reactorPool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()); // nCPU threads only
        CountDownLatch latch2 = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger completed2 = new AtomicInteger(0);
        start = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            // Non-blocking style: schedule callback sau IO_DELAY_MS
            CompletableFuture
                .runAsync(() -> sleep(IO_DELAY_MS), reactorPool) // simulated async I/O
                .thenRunAsync(() -> {
                    // business logic chạy trên event loop thread
                    completed2.incrementAndGet();
                    latch2.countDown();
                }, reactorPool);
        }
        latch2.await(30, TimeUnit.SECONDS);
        long reactorTime = System.currentTimeMillis() - start;
        reactorPool.shutdown();

        // Virtual Thread model
        ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch3 = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger completed3 = new AtomicInteger(0);
        start = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            vtPool.submit(() -> {
                sleep(IO_DELAY_MS); // blocking OK — VT unmounts carrier thread
                completed3.incrementAndGet();
                latch3.countDown();
            });
        }
        latch3.await(30, TimeUnit.SECONDS);
        long vtTime = System.currentTimeMillis() - start;
        vtPool.shutdown();

        System.out.println("  " + CONCURRENT_REQUESTS + " requests × " + IO_DELAY_MS + "ms I/O:");
        System.out.println("  Thread-per-Request (pool=50):  " + tprTime + "ms  ← sequential, simple code");
        System.out.println("  Reactor (pool=nCPU):           " + reactorTime + "ms  ← async, complex code");
        System.out.println("  Virtual Thread:                " + vtTime + "ms  ← sequential code + scale ✓");
        System.out.println();
        System.out.println("  Model comparison:");
        System.out.println("  Thread-per-Request: code đơn giản, debug dễ, ceiling = pool size");
        System.out.println("  Reactor:            scale tốt, code phức tạp, debug khó (stack trace cụt)");
        System.out.println("  Virtual Thread:     scale tốt, code đơn giản = best of both worlds\n");
    }

    // ================================================================
    // DEMO 3: Active Object — Async Method Call
    // ================================================================

    /**
     * Active Object: tách invocation (gọi method) khỏi execution (thực thi).
     *   - Client gọi method → nhận Future ngay lập tức (non-blocking)
     *   - Method thực sự chạy trên thread riêng của Active Object
     *   - Tất cả request được xếp vào queue, xử lý tuần tự (hoặc concurrent)
     *
     * CẤU TRÚC:
     *   Proxy:     Client gọi → tạo MethodRequest → đưa vào Activation Queue → trả Future
     *   Scheduler: lấy request từ queue, dispatch cho Servant
     *   Servant:   thực sự thực thi logic
     *   Future:    client dùng để lấy kết quả khi cần
     *
     * SO SÁNH VỚI:
     *   ExecutorService.submit(): giống Active Object đơn giản
     *   Active Object thêm: encapsulation của Servant, single object interface
     *
     * THỰC TẾ:
     *   Akka Actor = Active Object + mailbox + supervision
     *   JavaScript Web Worker
     *   Android Handler + Looper
     *
     * SA INSIGHT: Active Object giải quyết shared mutable state bằng cách
     *   đảm bảo chỉ 1 thread (Servant thread) access state của object.
     *   Không cần synchronized, không cần lock — single-threaded isolation.
     */
    static void demo3_ActiveObject() throws Exception {
        System.out.println("--- DEMO 3: Active Object ---");

        // Active Object: AccountService chạy trên thread riêng
        // Client gọi method → nhận Future → không block
        ActiveAccountService account = new ActiveAccountService(1000.0);

        System.out.println("  Active Object — async method calls:");
        System.out.println("  [Client] Gọi deposit(500) — không block");
        Future<Double> balanceAfterDeposit = account.deposit(500.0);

        System.out.println("  [Client] Gọi withdraw(200) — không block");
        Future<Double> balanceAfterWithdraw = account.withdraw(200.0);

        System.out.println("  [Client] Gọi getBalance() — không block");
        Future<Double> balance = account.getBalance();

        System.out.println("  [Client] Làm việc khác trong khi Active Object xử lý...");
        sleep(100);

        System.out.println("  [Client] Lấy kết quả:");
        System.out.printf("  After deposit(500):  $%.2f%n", balanceAfterDeposit.get());
        System.out.printf("  After withdraw(200): $%.2f%n", balanceAfterWithdraw.get());
        System.out.printf("  Final balance:       $%.2f%n", balance.get());

        account.shutdown();

        // Multi-client: tất cả request serialize qua Activation Queue
        System.out.println("\n  Multi-client access — không race condition (single Servant thread):");
        ActiveAccountService shared = new ActiveAccountService(0.0);
        ExecutorService clients = Executors.newFixedThreadPool(5);
        List<Future<Double>> deposits = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            final double amount = i * 100.0;
            deposits.add(clients.submit(() -> shared.deposit(amount).get()));
        }
        clients.shutdown();
        clients.awaitTermination(5, TimeUnit.SECONDS);

        double finalBalance = shared.getBalance().get();
        shared.shutdown();
        System.out.printf("  5 concurrent deposits (100+200+300+400+500): $%.2f (kỳ vọng $1500)%n%n",
                finalBalance);
    }

    /**
     * ActiveAccountService: Account chạy trên dedicated thread.
     * Mọi method call được serialize qua internal queue.
     */
    static class ActiveAccountService {
        private double balance;
        private final ExecutorService servant = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "AccountServant"));

        ActiveAccountService(double initial) { this.balance = initial; }

        Future<Double> deposit(double amount) {
            return servant.submit(() -> { // enqueue MethodRequest
                balance += amount;
                System.out.println("  [Servant] deposit(" + amount + ") → balance=" + balance);
                return balance;
            });
        }

        Future<Double> withdraw(double amount) {
            return servant.submit(() -> {
                if (balance < amount) throw new IllegalStateException("Insufficient funds");
                balance -= amount;
                System.out.println("  [Servant] withdraw(" + amount + ") → balance=" + balance);
                return balance;
            });
        }

        Future<Double> getBalance() {
            return servant.submit(() -> balance);
        }

        void shutdown() { servant.shutdown(); }
    }

    // ================================================================
    // DEMO 4: Half-Sync/Half-Async — Bridge async I/O và sync logic
    // ================================================================

    /**
     * Half-Sync/Half-Async: chia system thành 2 tầng giao tiếp qua queue:
     *
     *   ASYNC TIER (I/O layer):
     *     - Nhận events từ network/file/timer — non-blocking, event-driven
     *     - KHÔNG xử lý business logic
     *     - Đưa request vào Sync Queue
     *
     *   SYNC QUEUE: Buffer giữa 2 tầng, decoupling
     *
     *   SYNC TIER (Business layer):
     *     - Thread pool xử lý request tuần tự, blocking code OK
     *     - Lấy request từ Sync Queue, xử lý, trả kết quả
     *
     * VÍ DỤ THỰC TẾ:
     *   Netty + Spring:  Netty (async I/O) → queue → Spring thread pool (sync logic)
     *   Node.js:         Event loop (async) → Worker threads (sync/CPU-bound)
     *   Tomcat NIO:      NIO acceptor → request queue → servlet threads
     *   Kafka:           async receive → commit queue → sync consumer processing
     *
     * SA INSIGHT: Đây là foundation của hầu hết high-performance server.
     *   Async I/O = không waste thread khi chờ network
     *   Sync business logic = code đơn giản, dễ test, dễ debug
     *   Queue giữa 2 tầng = back-pressure, buffering, ordering
     */
    static void demo4_HalfSyncHalfAsync() throws Exception {
        System.out.println("--- DEMO 4: Half-Sync / Half-Async ---");

        int ASYNC_REQUESTS = 20;
        BlockingQueue<WebRequest> syncQueue = new ArrayBlockingQueue<>(50);
        AtomicInteger processed = new AtomicInteger(0);
        CountDownLatch allProcessed = new CountDownLatch(ASYNC_REQUESTS);

        // ASYNC TIER: simulate network event loop
        // Nhận connection, không block, đưa vào queue ngay
        Thread asyncTier = new Thread(() -> {
            System.out.println("  [Async Tier] Event loop started");
            for (int i = 1; i <= ASYNC_REQUESTS; i++) {
                // Non-blocking accept — trong thực tế là NIO Selector
                WebRequest request = new WebRequest(i, "/api/endpoint-" + i,
                    i % 5 == 0 ? "POST" : "GET");
                try {
                    syncQueue.put(request); // giao cho sync tier
                    System.out.println("  [Async I/O] Received request #" + i + " → queued");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                sleep(10); // simulate arrival rate
            }
            System.out.println("  [Async Tier] All requests received");
        }, "AsyncEventLoop");

        // SYNC TIER: thread pool xử lý business logic — blocking code OK
        ExecutorService syncTier = Executors.newFixedThreadPool(4);
        for (int w = 0; w < 4; w++) {
            syncTier.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WebRequest req = syncQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (req == null) return;

                        // Business logic — blocking, sequential, easy to reason about
                        sleep(20);
                        processed.incrementAndGet();
                        System.out.printf("  [Sync Worker] %s %s → processed (queue=%d)%n",
                            req.method(), req.path(), syncQueue.size());
                        allProcessed.countDown();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
        }

        asyncTier.start();
        asyncTier.join();
        allProcessed.await(10, TimeUnit.SECONDS);
        syncTier.shutdown();

        System.out.println("  Total processed: " + processed.get() + "/" + ASYNC_REQUESTS);
        System.out.println("  → Async tier không block khi nhận; Sync tier code đơn giản\n");
    }

    record WebRequest(int id, String path, String method) {}

    // ================================================================
    // DEMO 5: Object Pool — Tái sử dụng expensive object
    // ================================================================

    /**
     * Object Pool: duy trì tập object đã tạo sẵn, cho mượn và thu hồi.
     *   Thay vì new() mỗi lần → borrow() từ pool → trả lại release()
     *
     * DÙNG KHI:
     *   - Object tốn kém để tạo: DB connection, thread, SSL context, HTTP client
     *   - Object cần limit số lượng: connection limit, license limit
     *   - Object tái sử dụng được sau khi reset state
     *
     * THỰC TẾ:
     *   HikariCP / c3p0 / DBCP  — database connection pool
     *   ThreadPoolExecutor       — thread pool (bài 2.4)
     *   Apache HttpClient        — HTTP connection pool
     *   Netty ByteBuf allocator  — byte buffer pool
     *
     * IMPLEMENTATION CONCERNS:
     *   Validation:  object còn healthy không trước khi cho mượn?
     *   Eviction:    idle object quá lâu → close và remove
     *   Max size:    borrow() block khi pool đầy (back-pressure)
     *   Leak detection: object mượn nhưng không trả → timeout + alert
     *
     * SA INSIGHT: HikariCP là connection pool nhanh nhất Java.
     *   Key insight: dùng ConcurrentBag (không phải Queue) — LIFO per-thread.
     *   Thread thường lấy lại connection nó vừa trả → cache locality cao.
     *   Không có volatile read trong hot path → throughput cực cao.
     */
    static void demo5_ObjectPool() throws Exception {
        System.out.println("--- DEMO 5: Object Pool ---");

        // Generic Object Pool
        ObjectPool<DatabaseConnection2> pool = new ObjectPool<>(
            () -> new DatabaseConnection2(),  // factory
            conn -> conn.isAlive(),           // validation
            conn -> conn.reset(),             // reset before reuse
            5,                                // max size
            2000                              // borrow timeout ms
        );

        System.out.println("  Pool created with max=5 connections");

        // Concurrent borrow/release
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(12);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger timeout = new AtomicInteger(0);

        for (int i = 0; i < 12; i++) {
            final int reqId = i + 1;
            workers.submit(() -> {
                DatabaseConnection2 conn = null;
                try {
                    conn = pool.borrow(); // blocks if pool exhausted
                    System.out.printf("  [Request-%2d] Borrowed conn#%d → executing query%n",
                        reqId, conn.getId());
                    sleep(50 + (reqId % 3) * 20); // simulate query
                    success.incrementAndGet();
                } catch (TimeoutException e) {
                    System.out.println("  [Request-" + reqId + "] TIMEOUT — no connection available");
                    timeout.incrementAndGet();
                } finally {
                    if (conn != null) pool.release(conn);
                    latch.countDown();
                }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        workers.shutdown();

        System.out.println("  Success: " + success.get() + " | Timeout: " + timeout.get());
        System.out.println("  Pool stats: size=" + pool.size() + " | created=" + pool.totalCreated());

        pool.shutdown();

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 4.4 ===");
        System.out.println("  ✓ Producer-Consumer: Poison Pill shutdown, Priority Queue, DelayQueue retry");
        System.out.println("  ✓ Thread-per-Request: simple, ceiling=pool. Reactor: scale, complex. VT: best of both");
        System.out.println("  ✓ Active Object: method call → queue → Servant thread → no shared state lock");
        System.out.println("  ✓ Half-Sync/Half-Async: async I/O tier → queue → sync business tier");
        System.out.println("  ✓ Object Pool: borrow/release, validation, max size back-pressure, leak detection");
        System.out.println("  → Bài tiếp: 4.5 AntiPatternsDemo — God Object, Service Locator, Lemon patterns");
    }

    // ================================================================
    // Object Pool Implementation
    // ================================================================

    static class ObjectPool<T> {
        private final Supplier<T> factory;
        private final Predicate<T> validator;
        private final Consumer<T> resetter;
        private final int maxSize;
        private final long borrowTimeoutMs;

        private final BlockingDeque<T> idle = new LinkedBlockingDeque<>();
        private final AtomicInteger currentSize = new AtomicInteger(0);
        private final AtomicInteger created = new AtomicInteger(0);
        private final Semaphore semaphore;

        ObjectPool(Supplier<T> factory, Predicate<T> validator,
                   Consumer<T> resetter, int maxSize, long borrowTimeoutMs) {
            this.factory = factory;
            this.validator = validator;
            this.resetter = resetter;
            this.maxSize = maxSize;
            this.borrowTimeoutMs = borrowTimeoutMs;
            this.semaphore = new Semaphore(maxSize, true);
        }

        T borrow() throws TimeoutException {
            try {
                if (!semaphore.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS))
                    throw new TimeoutException("Pool exhausted after " + borrowTimeoutMs + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Interrupted while waiting for pool");
            }

            // Try to reuse idle object
            T obj = idle.pollFirst();
            if (obj != null && validator.test(obj)) {
                resetter.accept(obj);
                return obj;
            }

            // Create new
            T newObj = factory.get();
            currentSize.incrementAndGet();
            created.incrementAndGet();
            return newObj;
        }

        void release(T obj) {
            if (obj != null && validator.test(obj)) {
                idle.offerFirst(obj); // LIFO — thread thường lấy lại obj nó vừa trả (cache-friendly)
            } else {
                currentSize.decrementAndGet(); // invalid → discard
            }
            semaphore.release();
        }

        int size()         { return currentSize.get(); }
        int totalCreated() { return created.get(); }

        void shutdown() {
            idle.clear();
            System.out.println("  Pool shutdown — " + created.get() + " connections were created total");
        }
    }

    static class DatabaseConnection2 {
        private static final AtomicInteger counter = new AtomicInteger(0);
        private final int id = counter.incrementAndGet();
        private boolean alive = true;
        private int queryCount = 0;

        int getId() { return id; }
        boolean isAlive() { return alive && queryCount < 100; }
        void reset() { queryCount = 0; }
        void executeQuery(String sql) { if (!alive) throw new IllegalStateException("Connection closed"); queryCount++; }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
