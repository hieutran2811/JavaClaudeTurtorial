package org.example.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ============================================================
 * BÀI 2.2 — SYNCHRONIZED & INTRINSIC LOCK
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Hiểu synchronized hoạt động như thế nào bên trong JVM (monitor/mutex)
 *   2. Phân biệt: lock trên instance vs lock trên class
 *   3. Tại sao synchronized là "reentrant"
 *   4. Phát hiện và tránh DEADLOCK
 *   5. So sánh synchronized vs java.util.concurrent.locks.Lock
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.concurrency.SynchronizedDemo"
 * ============================================================
 */
public class SynchronizedDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 2.2: SYNCHRONIZED & INTRINSIC LOCK ===\n");

        demo1_RaceConditionAndFix();
        demo2_InstanceVsClassLock();
        demo3_ReentrantNature();
        demo4_DeadlockAndFix();
        demo5_SynchronizedBlockVsMethod();

        System.out.println("\n=== KẾT THÚC BÀI 2.2 ===");
    }

    // ================================================================
    // DEMO 1: Race Condition và cách fix bằng synchronized
    // ================================================================

    /**
     * RACE CONDITION: Khi 2 thread cùng đọc-tính-ghi một biến mà không có lock,
     * kết quả sẽ sai vì các bước đó không phải là atomic.
     *
     * JVM chạy i++ thành 3 bytecode instructions:
     *   GETFIELD  (đọc giá trị hiện tại)
     *   IADD      (cộng 1)
     *   PUTFIELD  (ghi lại)
     *
     * Thread A có thể bị interrupt giữa GETFIELD và PUTFIELD,
     * Thread B đọc giá trị cũ → cả 2 cùng ghi lại cùng 1 giá trị → mất 1 increment.
     */
    static void demo1_RaceConditionAndFix() throws Exception {
        System.out.println("--- DEMO 1: Race Condition vs synchronized ---");

        // KHÔNG an toàn — không có synchronization
        UnsafeCounter unsafe = new UnsafeCounter();
        runConcurrently(100, unsafe::increment);
        System.out.println("UnsafeCounter (không sync) — kỳ vọng: 100, thực tế: " + unsafe.count);

        // AN TOÀN — synchronized method
        SafeCounter safe = new SafeCounter();
        runConcurrently(100, safe::increment);
        System.out.println("SafeCounter (synchronized)  — kỳ vọng: 100, thực tế: " + safe.count);

        // AN TOÀN — AtomicInteger (không dùng lock, dùng CAS)
        AtomicCounter atomic = new AtomicCounter();
        runConcurrently(100, atomic::increment);
        System.out.println("AtomicCounter (CAS)         — kỳ vọng: 100, thực tế: " + atomic.count.get());

        System.out.println();
    }

    static class UnsafeCounter {
        int count = 0;
        void increment() { count++; } // NOT thread-safe: 3 bytecode ops
    }

    static class SafeCounter {
        int count = 0;
        // synchronized dùng `this` làm intrinsic lock (monitor)
        synchronized void increment() { count++; }
    }

    static class AtomicCounter {
        AtomicInteger count = new AtomicInteger(0);
        void increment() { count.incrementAndGet(); } // CAS = Compare-And-Swap, lock-free
    }

    // ================================================================
    // DEMO 2: Lock trên Instance vs Lock trên Class
    // ================================================================

    /**
     * INSTANCE LOCK: synchronized method / synchronized(this)
     *   → Khóa trên một đối tượng cụ thể
     *   → Các instance khác nhau KHÔNG block lẫn nhau
     *
     * CLASS LOCK: synchronized(MyClass.class) hoặc static synchronized method
     *   → Khóa trên Class object (1 object duy nhất trong JVM)
     *   → Mọi thread đều block nhau dù dùng instance khác nhau
     *
     * SA INSIGHT: Instance lock = per-object mutex | Class lock = global mutex (dùng cẩn thận!)
     */
    static void demo2_InstanceVsClassLock() throws InterruptedException {
        System.out.println("--- DEMO 2: Instance Lock vs Class Lock ---");

        LockDemo obj1 = new LockDemo("obj1");
        LockDemo obj2 = new LockDemo("obj2");

        // 2 thread dùng 2 instance khác nhau → instance lock KHÔNG block nhau
        long start = System.currentTimeMillis();
        Thread t1 = new Thread(() -> obj1.instanceLockMethod(), "T1");
        Thread t2 = new Thread(() -> obj2.instanceLockMethod(), "T2");
        t1.start(); t2.start();
        t1.join(); t2.join();
        System.out.println("Instance lock (2 objects riêng): " + (System.currentTimeMillis() - start) + "ms (≈100ms nếu chạy song song)");

        // 2 thread dùng 2 instance khác nhau → class lock BLOCK nhau
        start = System.currentTimeMillis();
        Thread t3 = new Thread(() -> obj1.classLockMethod(), "T3");
        Thread t4 = new Thread(() -> obj2.classLockMethod(), "T4");
        t3.start(); t4.start();
        t3.join(); t4.join();
        System.out.println("Class lock (2 objects riêng):   " + (System.currentTimeMillis() - start) + "ms (≈200ms vì bị block tuần tự)\n");
    }

    static class LockDemo {
        String name;
        LockDemo(String name) { this.name = name; }

        // Lock trên 'this' — mỗi instance có lock riêng
        synchronized void instanceLockMethod() {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        // Lock trên LockDemo.class — 1 lock dùng chung cho toàn bộ class
        void classLockMethod() {
            synchronized (LockDemo.class) {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }
    }

    // ================================================================
    // DEMO 3: Tính Reentrant của synchronized
    // ================================================================

    /**
     * REENTRANT LOCK: Một thread đang giữ lock CÓ THỂ gọi thêm synchronized method
     * của cùng object mà KHÔNG bị block chính nó.
     *
     * JVM theo dõi: (thread_id, count) cho mỗi monitor.
     *   - Thread acquire lock → count++
     *   - Thread release lock → count--
     *   - Khi count = 0, lock được trả về
     *
     * Nếu synchronized KHÔNG reentrant → gọi super() từ synchronized method sẽ DEADLOCK ngay!
     */
    static void demo3_ReentrantNature() throws Exception {
        System.out.println("--- DEMO 3: Reentrant Lock ---");

        ReentrantExample example = new ReentrantExample();

        Thread t = new Thread(() -> example.outerMethod());
        t.start();
        t.join();

        System.out.println("Reentrant thành công — cùng thread có thể gọi lại synchronized method\n");
    }

    static class ReentrantExample {
        synchronized void outerMethod() {
            System.out.println("  outerMethod() — thread đang giữ lock: " + Thread.currentThread().getName());
            innerMethod(); // Gọi vào synchronized method khác trên CÙNG object — OK!
        }

        synchronized void innerMethod() {
            // Thread này đang gọi lại lock mà nó đang giữ → reentrant: count tăng lên 2
            System.out.println("  innerMethod() — reentrant thành công, không bị block!");
        }
    }

    // ================================================================
    // DEMO 4: DEADLOCK — Nguyên nhân và Cách Phòng Tránh
    // ================================================================

    /**
     * DEADLOCK xảy ra khi:
     *   Thread A giữ Lock-1, chờ Lock-2
     *   Thread B giữ Lock-2, chờ Lock-1
     *   → Cả 2 chờ nhau mãi mãi → system hang
     *
     * ĐIỀU KIỆN CỦA DEADLOCK (Coffman conditions):
     *   1. Mutual Exclusion  — resource chỉ dùng được bởi 1 thread
     *   2. Hold and Wait     — thread giữ lock trong khi chờ lock khác
     *   3. No Preemption     — lock không thể bị cướp
     *   4. Circular Wait     — A→B→A (vòng tròn)
     *
     * CÁCH PHÒNG TRÁNH:
     *   - Lock Ordering: LUÔN acquire lock theo thứ tự nhất định (phá Circular Wait)
     *   - tryLock với timeout (dùng ReentrantLock — sẽ học bài 2.3)
     *   - Minimize lock scope: chỉ lock khi thực sự cần
     */
    static void demo4_DeadlockAndFix() throws Exception {
        System.out.println("--- DEMO 4: Deadlock Detection & Fix ---");

        Object lockA = new Object();
        Object lockB = new Object();

        // DEADLOCK SCENARIO (commented out để không hang chương trình)
        // Thread t1 = new Thread(() -> {
        //     synchronized (lockA) {           // T1 lấy A
        //         sleep(50);
        //         synchronized (lockB) { ... } // T1 chờ B — nhưng T2 đang giữ B!
        //     }
        // });
        // Thread t2 = new Thread(() -> {
        //     synchronized (lockB) {           // T2 lấy B
        //         sleep(50);
        //         synchronized (lockA) { ... } // T2 chờ A — nhưng T1 đang giữ A!
        //     }
        // });
        // → T1 và T2 chờ nhau mãi mãi = DEADLOCK

        System.out.println("  [Deadlock scenario] Skipped để tránh hang (xem comment trong code)");

        // FIX: Lock Ordering — cả 2 thread đều lấy lockA trước, rồi mới lấy lockB
        Thread fixedT1 = new Thread(() -> {
            synchronized (lockA) {             // T1 lấy A
                safeSleep(50);
                synchronized (lockB) {         // T1 lấy B (A đã được lock, không ai khác có thể lấy A)
                    System.out.println("  Fixed T1: giữ cả A và B thành công");
                }
            }
        }, "Fixed-T1");

        Thread fixedT2 = new Thread(() -> {
            synchronized (lockA) {             // T2 CŨNG lấy A trước (lock ordering)
                safeSleep(50);
                synchronized (lockB) {
                    System.out.println("  Fixed T2: giữ cả A và B thành công");
                }
            }
        }, "Fixed-T2");

        fixedT1.start(); fixedT2.start();
        fixedT1.join(2000); fixedT2.join(2000);

        if (fixedT1.isAlive() || fixedT2.isAlive()) {
            System.out.println("  DEADLOCK vẫn xảy ra! (không nên thấy dòng này)");
        } else {
            System.out.println("  Lock ordering fix hoạt động — không có deadlock!\n");
        }
    }

    // ================================================================
    // DEMO 5: synchronized block vs synchronized method
    // ================================================================

    /**
     * synchronized METHOD: lock toàn bộ method → scope lock rộng
     * synchronized BLOCK:  chỉ lock phần critical section → scope lock hẹp hơn
     *
     * NGUYÊN TẮC: Lock càng ít thời gian càng tốt!
     * - Code ngoài critical section (I/O, computation) không nên nằm trong lock
     * - Lock rộng → contention cao → throughput thấp
     *
     * SA INSIGHT: Đây là nguyên lý "minimize lock scope" — quan trọng khi design
     * high-throughput services. Dùng synchronized block thay vì method khi cần tối ưu.
     */
    static void demo5_SynchronizedBlockVsMethod() throws Exception {
        System.out.println("--- DEMO 5: synchronized Block vs Method ---");

        BankAccount account = new BankAccount(1000);

        // Nhiều thread thực hiện transfer đồng thời
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);

        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                account.deposit(10);   // +10
                latch.countDown();
            });
            pool.submit(() -> {
                account.withdraw(10);  // -10
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        // 50 deposit(+10) và 50 withdraw(-10) → số dư phải không đổi = 1000
        System.out.println("  Số dư ban đầu: 1000");
        System.out.println("  Sau 50 deposit(+10) & 50 withdraw(-10): " + account.getBalance());
        System.out.println("  Kết quả đúng: " + (account.getBalance() == 1000 ? "✓" : "✗ BUG!"));

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 2.2 ===");
        System.out.println("  ✓ synchronized dùng INTRINSIC LOCK (monitor) — mỗi object có 1 lock");
        System.out.println("  ✓ Instance lock (this) vs Class lock (MyClass.class)");
        System.out.println("  ✓ Reentrant: cùng thread có thể re-acquire lock mà không bị block");
        System.out.println("  ✓ Deadlock: dùng LOCK ORDERING để phá Circular Wait");
        System.out.println("  ✓ Minimize lock scope: prefer synchronized block over method");
        System.out.println("  → Bài tiếp: 2.3 LockDemo — ReentrantLock, ReadWriteLock, tryLock");
    }

    static class BankAccount {
        private int balance;
        private final Object lock = new Object(); // private lock — tốt hơn lock trên `this`

        BankAccount(int initial) { this.balance = initial; }

        void deposit(int amount) {
            // Giả lập I/O hoặc validation TRƯỚC khi lock (không cần lock ở đây)
            if (amount <= 0) throw new IllegalArgumentException();

            // Chỉ lock phần thực sự cần thiết — critical section
            synchronized (lock) {
                balance += amount;
            }
        }

        void withdraw(int amount) {
            if (amount <= 0) throw new IllegalArgumentException();

            synchronized (lock) {
                if (balance < amount) throw new IllegalStateException("Insufficient funds");
                balance -= amount;
            }
        }

        int getBalance() {
            synchronized (lock) { return balance; }
        }
    }

    // ================================================================
    // HELPERS
    // ================================================================

    static void runConcurrently(int nThreads, Runnable task) throws Exception {
        CountDownLatch ready = new CountDownLatch(nThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(nThreads);

        for (int i = 0; i < nThreads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await(); // Chờ tất cả thread sẵn sàng, rồi bắt đầu cùng lúc
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await();  // Chờ tất cả thread vào vị trí
        start.countDown(); // Bắn súng xuất phát
        done.await();   // Chờ tất cả xong
    }

    static void safeSleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
