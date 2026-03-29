package org.example.concurrency;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * ============================================================
 * BÀI 2.3 — ReentrantLock, ReadWriteLock, StampedLock
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. ReentrantLock — linh hoạt hơn synchronized (tryLock, lockInterruptibly)
 *   2. tryLock với timeout — giải quyết deadlock không cần lock ordering
 *   3. ReadWriteLock — nhiều reader song song, writer độc quyền
 *   4. StampedLock — optimistic read, hiệu năng cao nhất (Java 8+)
 *   5. Khi nào dùng cái nào?
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.concurrency.LockDemo"
 * ============================================================
 */
public class LockDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 2.3: ReentrantLock, ReadWriteLock, StampedLock ===\n");

        demo1_ReentrantLockBasics();
        demo2_TryLockDeadlockAvoidance();
        demo3_LockInterruptibly();
        demo4_ReadWriteLock();
        demo5_StampedLock();
        demo6_PerformanceComparison();

        System.out.println("\n=== KẾT THÚC BÀI 2.3 ===");
    }

    // ================================================================
    // DEMO 1: ReentrantLock — cơ bản và so sánh với synchronized
    // ================================================================

    /**
     * ReentrantLock vs synchronized:
     *
     *   synchronized:
     *     ✓ Đơn giản, tự động unlock khi thoát block
     *     ✗ Không thể tryLock, không thể interrupt khi đang chờ
     *     ✗ Không biết ai đang giữ lock (debug khó)
     *
     *   ReentrantLock:
     *     ✓ tryLock(timeout) — thử lấy lock, không block mãi
     *     ✓ lockInterruptibly() — có thể cancel khi đang chờ
     *     ✓ lock.getOwner(), lock.getQueueLength() — observable
     *     ✓ Fairness mode — thread chờ lâu nhất được ưu tiên
     *     ✗ PHẢI gọi unlock() trong finally — nếu quên → lock leak!
     *
     * SA INSIGHT: Dùng synchronized mặc định. Chỉ dùng ReentrantLock khi cần
     * tryLock/interrupt/fairness. "Locks are dangerous — use them sparingly."
     */
    static void demo1_ReentrantLockBasics() throws Exception {
        System.out.println("--- DEMO 1: ReentrantLock Basics ---");

        // UNFAIR lock (mặc định): thread nào may mắn thì được — throughput cao hơn
        ReentrantLock unfairLock = new ReentrantLock();

        // FAIR lock: thread chờ lâu nhất được ưu tiên — tránh starvation, throughput thấp hơn
        ReentrantLock fairLock = new ReentrantLock(true);

        // Cách dùng ĐÚNG — luôn unlock trong finally!
        BankAccount account = new BankAccount(1000, unfairLock);

        CountDownLatch latch = new CountDownLatch(20);
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 10; i++) {
            pool.submit(() -> { account.deposit(50);   latch.countDown(); });
            pool.submit(() -> { account.withdraw(50);  latch.countDown(); });
        }

        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("  Số dư sau 10 deposit(+50) & 10 withdraw(-50): " + account.getBalance()
                + " (kỳ vọng: 1000) " + (account.getBalance() == 1000 ? "✓" : "✗"));

        // Observable — rất hữu ích khi debug
        System.out.println("  Lock held: " + unfairLock.isLocked());
        System.out.println("  Queue length: " + unfairLock.getQueueLength());
        System.out.println();
    }

    static class BankAccount {
        private int balance;
        private final ReentrantLock lock;

        BankAccount(int initial, ReentrantLock lock) {
            this.balance = initial;
            this.lock = lock;
        }

        void deposit(int amount) {
            lock.lock();           // Acquire lock
            try {
                balance += amount;
            } finally {
                lock.unlock();     // LUÔN unlock trong finally — dù có exception
            }
        }

        void withdraw(int amount) {
            lock.lock();
            try {
                if (balance >= amount) balance -= amount;
            } finally {
                lock.unlock();
            }
        }

        int getBalance() {
            lock.lock();
            try { return balance; } finally { lock.unlock(); }
        }
    }

    // ================================================================
    // DEMO 2: tryLock — Giải quyết Deadlock không cần Lock Ordering
    // ================================================================

    /**
     * Bài 2.2 dùng Lock Ordering để phá Circular Wait.
     * Nhưng Lock Ordering có hạn chế: phải biết trước thứ tự — không phải lúc nào cũng biết.
     *
     * tryLock(timeout): Thử lấy lock trong timeout, nếu không được thì BUÔNG HẾT và thử lại.
     *   → Phá "Hold and Wait" condition của Coffman
     *   → Thread không bị block vĩnh viễn
     *
     * Kỹ thuật này gọi là "lock backoff" — phổ biến trong database transaction management.
     */
    static void demo2_TryLockDeadlockAvoidance() throws Exception {
        System.out.println("--- DEMO 2: tryLock — Deadlock Avoidance ---");

        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();

        // Thread 1: lấy A trước, rồi B
        Thread t1 = new Thread(() -> {
            int attempts = 0;
            while (true) {
                attempts++;
                boolean gotA = false, gotB = false;
                try {
                    gotA = lockA.tryLock(50, TimeUnit.MILLISECONDS);
                    if (gotA) {
                        Thread.sleep(30); // Giả lập công việc với A
                        gotB = lockB.tryLock(50, TimeUnit.MILLISECONDS);
                        if (gotB) {
                            System.out.println("  T1: giữ cả A & B — hoàn thành sau " + attempts + " lần thử");
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    // Nếu không lấy được cả 2 → buông hết, thử lại
                    if (gotB) lockB.unlock();
                    if (gotA) lockA.unlock();
                }
                // Backoff ngẫu nhiên để tránh livelock (2 thread cứ retry đồng thời mãi)
                try { Thread.sleep((long)(Math.random() * 20)); } catch (InterruptedException e) { return; }
            }
        }, "T1");

        // Thread 2: lấy B trước, rồi A — thứ tự NGƯỢC với T1 → deadlock nếu không dùng tryLock
        Thread t2 = new Thread(() -> {
            int attempts = 0;
            while (true) {
                attempts++;
                boolean gotB = false, gotA = false;
                try {
                    gotB = lockB.tryLock(50, TimeUnit.MILLISECONDS);
                    if (gotB) {
                        Thread.sleep(30);
                        gotA = lockA.tryLock(50, TimeUnit.MILLISECONDS);
                        if (gotA) {
                            System.out.println("  T2: giữ cả B & A — hoàn thành sau " + attempts + " lần thử");
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    if (gotA) lockA.unlock();
                    if (gotB) lockB.unlock();
                }
                try { Thread.sleep((long)(Math.random() * 20)); } catch (InterruptedException e) { return; }
            }
        }, "T2");

        t1.start(); t2.start();
        t1.join(3000); t2.join(3000);

        if (t1.isAlive() || t2.isAlive()) {
            System.out.println("  TIMEOUT — có thể livelock! (hiếm gặp)");
            t1.interrupt(); t2.interrupt();
        } else {
            System.out.println("  tryLock thành công — không deadlock dù thứ tự ngược nhau!\n");
        }
    }

    // ================================================================
    // DEMO 3: lockInterruptibly — Hủy thread đang chờ lock
    // ================================================================

    /**
     * Với synchronized: Nếu thread đang BLOCKED chờ lock → KHÔNG THỂ interrupt được.
     * Thread.interrupt() chỉ set flag, nhưng thread vẫn block mãi.
     *
     * Với lockInterruptibly(): Thread đang chờ lock CÓ THỂ bị cancel qua interrupt().
     * → Quan trọng trong: request timeout, graceful shutdown, user-cancel operations.
     *
     * SA INSIGHT: Trong service với SLA (e.g. 200ms response), nếu lock bị held lâu,
     * ta cần cancel request thay vì để user chờ vô tận.
     */
    static void demo3_LockInterruptibly() throws Exception {
        System.out.println("--- DEMO 3: lockInterruptibly — Cancellable Lock Wait ---");

        ReentrantLock lock = new ReentrantLock();

        // Thread chủ lấy lock và giữ lâu
        lock.lock();
        System.out.println("  Main giữ lock...");

        Thread waiter = new Thread(() -> {
            System.out.println("  Waiter: đang chờ lock...");
            try {
                lock.lockInterruptibly(); // Chờ lock — CÓ THỂ bị interrupt
                try {
                    System.out.println("  Waiter: đã có lock (không nên thấy dòng này trong demo)");
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                // Thread bị cancel trong khi chờ lock
                System.out.println("  Waiter: bị cancel khi đang chờ lock — xử lý gracefully");
            }
        }, "Waiter");

        waiter.start();
        Thread.sleep(200);          // Main giữ lock 200ms
        waiter.interrupt();         // Cancel waiter
        Thread.sleep(100);
        lock.unlock();              // Main unlock
        waiter.join();

        System.out.println("  lockInterruptibly cho phép cancel gracefully\n");
    }

    // ================================================================
    // DEMO 4: ReadWriteLock — Tối ưu cho read-heavy workload
    // ================================================================

    /**
     * Vấn đề với synchronized/ReentrantLock: chỉ 1 thread chạy tại một thời điểm.
     * Nhưng READ không thay đổi state → nhiều reader chạy song song hoàn toàn an toàn!
     *
     * ReadWriteLock chia làm 2 loại lock:
     *   Read Lock:  Nhiều thread có thể cùng giữ đồng thời (shared)
     *   Write Lock: Chỉ 1 thread, độc quyền (exclusive) — block cả read và write khác
     *
     * Rules:
     *   Read + Read  = OK (chạy song song)
     *   Read + Write = BLOCK
     *   Write + Write = BLOCK
     *
     * Phù hợp khi: read >> write (cache, config store, directory service, ...)
     *
     * SA INSIGHT: ConcurrentHashMap dùng nguyên lý tương tự (segment striping).
     * Không phù hợp khi write nhiều — contention cao, overhead lớn.
     */
    static void demo4_ReadWriteLock() throws Exception {
        System.out.println("--- DEMO 4: ReadWriteLock — Read-Heavy Optimization ---");

        Cache cache = new Cache();

        // Populate cache
        cache.put("user:1", "Alice");
        cache.put("user:2", "Bob");
        cache.put("user:3", "Charlie");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(16);
        long start = System.currentTimeMillis();

        // 14 reader threads (nhiều read)
        for (int i = 0; i < 14; i++) {
            int key = (i % 3) + 1;
            pool.submit(() -> {
                String value = cache.get("user:" + key);
                latch.countDown();
            });
        }

        // 2 writer threads (ít write)
        pool.submit(() -> { cache.put("user:4", "Dave");  latch.countDown(); });
        pool.submit(() -> { cache.put("user:5", "Eve");   latch.countDown(); });

        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("  Cache size: " + cache.size());
        System.out.println("  Read stats — concurrent reads cho phép: " + cache.getConcurrentReadCount() + " lần đọc song song");
        System.out.println("  Completed in: " + elapsed + "ms\n");
    }

    static class Cache {
        private final Map<String, String> store = new HashMap<>();
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock  = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();
        private int maxConcurrentReads = 0;
        private int currentReaders = 0;

        void put(String key, String value) {
            writeLock.lock();           // Exclusive write
            try {
                store.put(key, value);
                Thread.sleep(5);        // Giả lập I/O write
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeLock.unlock();
            }
        }

        String get(String key) {
            readLock.lock();            // Shared read — nhiều thread vào cùng lúc
            try {
                synchronized (this) {
                    currentReaders++;
                    maxConcurrentReads = Math.max(maxConcurrentReads, currentReaders);
                }
                Thread.sleep(10);       // Giả lập I/O read
                return store.get(key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                synchronized (this) { currentReaders--; }
                readLock.unlock();
            }
        }

        int size() {
            readLock.lock();
            try { return store.size(); } finally { readLock.unlock(); }
        }

        int getConcurrentReadCount() { return maxConcurrentReads; }
    }

    // ================================================================
    // DEMO 5: StampedLock — Optimistic Read (Java 8+)
    // ================================================================

    /**
     * ReadWriteLock vẫn có overhead: lấy read lock vẫn cần atomic operation.
     * Khi read rất nhiều và write rất hiếm → overhead của read lock trở thành bottleneck.
     *
     * StampedLock giới thiệu OPTIMISTIC READ:
     *   1. Đọc mà KHÔNG lấy lock (stamp = tryOptimisticRead)
     *   2. Sau khi đọc, kiểm tra xem có write nào xảy ra không (validate)
     *   3. Nếu validate OK → dùng kết quả (zero lock overhead!)
     *   4. Nếu validate FAIL → fallback sang read lock thông thường
     *
     * Analogy: Đọc bảng giá niêm yết → không cần hỏi xin phép.
     *          Sau khi đọc, check xem bảng giá có vừa thay đổi không.
     *          Nếu thay đổi → đọc lại chính thức.
     *
     * CẢNH BÁO: StampedLock KHÔNG reentrant (khác với Reentrant*)
     *           Không có Condition support
     *           Chỉ dùng khi read >> write và hiệu năng là vấn đề thực sự.
     *
     * SA INSIGHT: Dùng trong high-read structures như spatial index, config cache.
     *             java.util.concurrent.atomic.LongAdder dùng kỹ thuật tương tự.
     */
    static void demo5_StampedLock() throws Exception {
        System.out.println("--- DEMO 5: StampedLock — Optimistic Read ---");

        Point point = new Point(3.0, 4.0);

        System.out.println("  Initial distance: " + String.format("%.2f", point.distanceFromOrigin()));

        // Concurrent reads + một write
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch latch = new CountDownLatch(6);

        for (int i = 0; i < 5; i++) {
            pool.submit(() -> {
                double d = point.distanceFromOrigin(); // Optimistic read
                latch.countDown();
            });
        }
        pool.submit(() -> {
            point.move(6.0, 8.0); // Write → invalidates optimistic reads
            latch.countDown();
        });

        latch.await(3, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("  After move(6,8) distance: " + String.format("%.2f", point.distanceFromOrigin()));
        System.out.println("  Optimistic reads: " + point.optimisticSuccesses + " thành công, "
                + point.optimisticFallbacks + " fallback sang read lock\n");
    }

    static class Point {
        private double x, y;
        private final StampedLock sl = new StampedLock();
        int optimisticSuccesses = 0;
        int optimisticFallbacks = 0;

        Point(double x, double y) { this.x = x; this.y = y; }

        void move(double deltaX, double deltaY) {
            long stamp = sl.writeLock();    // Exclusive write lock
            try {
                x += deltaX;
                y += deltaY;
            } finally {
                sl.unlockWrite(stamp);
            }
        }

        double distanceFromOrigin() {
            // Bước 1: Thử optimistic read — không acquire lock
            long stamp = sl.tryOptimisticRead();
            double curX = x, curY = y;

            // Bước 2: Validate — có ai write trong khi ta đọc không?
            if (!sl.validate(stamp)) {
                // Bước 3: Fallback — lấy read lock thông thường
                stamp = sl.readLock();
                try {
                    curX = x; curY = y;
                    synchronized (this) { optimisticFallbacks++; }
                } finally {
                    sl.unlockRead(stamp);
                }
            } else {
                synchronized (this) { optimisticSuccesses++; }
            }

            return Math.sqrt(curX * curX + curY * curY);
        }
    }

    // ================================================================
    // DEMO 6: Performance Comparison
    // ================================================================

    /**
     * So sánh thực tế throughput của các lock type.
     * Kịch bản: 90% read, 10% write — điển hình cho cache/config services.
     */
    static void demo6_PerformanceComparison() throws Exception {
        System.out.println("--- DEMO 6: Performance Comparison (90% read / 10% write) ---");

        int THREADS = 8, OPS = 10_000;

        long syncTime     = benchmark("synchronized    ", THREADS, OPS, new SyncStore());
        long rwTime       = benchmark("ReadWriteLock   ", THREADS, OPS, new RWStore());
        long stampedTime  = benchmark("StampedLock     ", THREADS, OPS, new StampedStore());

        System.out.println();
        System.out.println("  === KẾT QUẢ ===");
        System.out.println("  synchronized:  " + syncTime    + "ms");
        System.out.println("  ReadWriteLock: " + rwTime      + "ms");
        System.out.println("  StampedLock:   " + stampedTime + "ms  ← thường nhanh nhất (read-heavy)");

        System.out.println();
        System.out.println("  === HƯỚNG DẪN CHỌN LOCK ===");
        System.out.println("  synchronized    → Mặc định. Đơn giản, safe, JIT optimize tốt.");
        System.out.println("  ReentrantLock   → Cần tryLock / interrupt / fairness.");
        System.out.println("  ReadWriteLock   → Read >> Write (cache, config, directory).");
        System.out.println("  StampedLock     → Read cực nhiều, write rất hiếm, latency quan trọng.");
        System.out.println("  Atomic*         → Counter, flag, single variable — lock-free tốt nhất.");
    }

    interface Store {
        void write(int key, int val);
        int read(int key);
    }

    static class SyncStore implements Store {
        private final Map<Integer, Integer> map = new HashMap<>();
        public synchronized void write(int k, int v) { map.put(k, v); }
        public synchronized int read(int k) { return map.getOrDefault(k, 0); }
    }

    static class RWStore implements Store {
        private final Map<Integer, Integer> map = new HashMap<>();
        private final ReadWriteLock rw = new ReentrantReadWriteLock();
        public void write(int k, int v) {
            rw.writeLock().lock();
            try { map.put(k, v); } finally { rw.writeLock().unlock(); }
        }
        public int read(int k) {
            rw.readLock().lock();
            try { return map.getOrDefault(k, 0); } finally { rw.readLock().unlock(); }
        }
    }

    static class StampedStore implements Store {
        private final Map<Integer, Integer> map = new HashMap<>();
        private final StampedLock sl = new StampedLock();
        public void write(int k, int v) {
            long stamp = sl.writeLock();
            try { map.put(k, v); } finally { sl.unlockWrite(stamp); }
        }
        public int read(int k) {
            long stamp = sl.tryOptimisticRead();
            int val = map.getOrDefault(k, 0);
            if (!sl.validate(stamp)) {
                stamp = sl.readLock();
                try { val = map.getOrDefault(k, 0); } finally { sl.unlockRead(stamp); }
            }
            return val;
        }
    }

    static long benchmark(String name, int threads, int opsPerThread, Store store) throws Exception {
        // Warm up
        for (int i = 0; i < 100; i++) { store.write(i, i); store.read(i); }

        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    if (i % 10 == 0) store.write(tid * 100 + i % 100, i); // 10% write
                    else             store.read(tid * 100 + i % 100);       // 90% read
                }
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("  " + name + ": " + elapsed + "ms");
        return elapsed;
    }
}
