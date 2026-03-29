package org.example.concurrency;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * DEMO 1: Java Memory Model (JMM) — Nền tảng của mọi thứ Concurrency
 *
 * Bài toán thực tế:
 *  - Thread A ghi flag = true, Thread B đọc mãi thấy false -> bug visibility
 *  - Code chạy đúng trên máy dev (1 core), sai trên server (multi-core) -> JMM violation
 *  - synchronized "nặng", volatile "nhẹ" -> dùng sai chỗ = bug hoặc bottleneck
 *
 * ====================================================
 *  JMM LÀ GÌ?
 * ====================================================
 *
 *  JMM định nghĩa QUY TẮC về cách threads đọc/ghi shared variables.
 *
 *  Vấn đề cốt lõi: mỗi CPU core có L1/L2 Cache riêng.
 *  Thread A (core 0) ghi x=1 vào cache của core 0.
 *  Thread B (core 1) đọc x từ cache của core 1 -> vẫn thấy x=0 !!
 *
 *  CPU Architecture:
 *  ┌──────────┐    ┌──────────┐
 *  │  Core 0  │    │  Core 1  │
 *  │ [Cache]  │    │ [Cache]  │
 *  └────┬─────┘    └────┬─────┘
 *       │               │
 *       └───────┬───────┘
 *           [Main Memory / RAM]
 *
 *  JMM giải quyết bằng khái niệm: HAPPENS-BEFORE
 *
 * ====================================================
 *  HAPPENS-BEFORE RELATIONSHIP
 * ====================================================
 *
 *  "A happens-before B" = mọi ghi của A đều VISIBLE với B
 *
 *  Các quy tắc happens-before:
 *  1. Program order:    dòng trên happens-before dòng dưới (cùng thread)
 *  2. Monitor lock:     unlock(x) happens-before lock(x) tiếp theo
 *  3. Volatile:         ghi volatile happens-before mọi đọc volatile sau đó
 *  4. Thread start:     thread.start() happens-before bất kỳ action nào trong thread
 *  5. Thread join:      mọi action trong thread happens-before thread.join() return
 *  6. Transitivity:     A hb B, B hb C => A hb C
 *
 * ====================================================
 *  3 VẤN ĐỀ CHÍNH TRONG CONCURRENCY
 * ====================================================
 *
 *  1. VISIBILITY:   một thread ghi, thread khác không thấy
 *  2. ATOMICITY:    read-modify-write không phải 1 thao tác đơn (i++ = 3 bước)
 *  3. ORDERING:     CPU/compiler có thể reorder instructions (optimization)
 *
 *  volatile giải quyết: Visibility + Ordering (KHÔNG giải quyết Atomicity)
 *  synchronized giải quyết: cả 3
 *  AtomicXxx giải quyết: Atomicity + Visibility (dùng CAS hardware instruction)
 */
public class JMMDemo {

    public static void main(String[] args) throws InterruptedException {

        // === PHẦN 1: Visibility Problem — bug kinh điển ===
        System.out.println("=== PHẦN 1: Visibility Problem ===");
        demonstrateVisibilityProblem();

        // === PHẦN 2: volatile giải quyết Visibility ===
        System.out.println("\n=== PHẦN 2: volatile — giải quyết Visibility ===");
        demonstrateVolatile();

        // === PHẦN 3: volatile KHÔNG giải quyết Atomicity ===
        System.out.println("\n=== PHẦN 3: volatile KHÔNG đủ cho Atomicity ===");
        demonstrateVolatileNotAtomic();

        // === PHẦN 4: Happens-Before với Thread.start() và join() ===
        System.out.println("\n=== PHẦN 4: Happens-Before với start() và join() ===");
        demonstrateHappensBefore();

        // === PHẦN 5: Instruction Reordering — bẫy nguy hiểm nhất ===
        System.out.println("\n=== PHẦN 5: Reordering & Double-Checked Locking ===");
        demonstrateReordering();

        // === PHẦN 6: Tổng kết — khi nào dùng gì ===
        printDecisionGuide();
    }

    // ------------------------------------------------------------------
    // PHẦN 1: Visibility Problem
    // BUG: Thread reader có thể loop mãi vì không thấy flag = true
    // ------------------------------------------------------------------

    // KHÔNG có volatile -> visibility problem
    static boolean stopFlag = false; // BUG: thread reader có thể không thấy thay đổi

    static void demonstrateVisibilityProblem() throws InterruptedException {
        System.out.println("Chạy với flag KHÔNG volatile...");
        System.out.println("(Trong thực tế trên server multi-core, reader thread có thể loop mãi)");
        System.out.println("(Demo này dùng timeout để không bị treo)");

        stopFlag = false;
        Thread reader = new Thread(() -> {
            long count = 0;
            while (!stopFlag) {
                count++;
                // JIT có thể optimize thành: if (!stopFlag) { while(true) count++; }
                // vì JIT thấy stopFlag không bao giờ thay đổi trong thread này!
                if (count > 500_000_000L) {
                    System.out.println("  [reader] Timeout! Vẫn thấy stopFlag=false sau 500M iterations");
                    System.out.println("  [reader] -> Đây là Visibility Problem!");
                    return;
                }
            }
            System.out.println("  [reader] Thấy stopFlag=true, thoát vòng lặp");
        });

        reader.start();
        Thread.sleep(10); // Writer "ngủ" một chút trước khi set flag
        stopFlag = true;
        System.out.println("  [writer] Đã set stopFlag=true");
        reader.join(2000); // Chờ tối đa 2 giây

        System.out.println("GIẢI THÍCH:");
        System.out.println("  - JIT cache stopFlag vào register của core đang chạy reader thread");
        System.out.println("  - Writer ghi vào cache của core khác, reader KHÔNG thấy");
        System.out.println("  - Fix: dùng 'volatile boolean stopFlag'");
    }

    // ------------------------------------------------------------------
    // PHẦN 2: volatile giải quyết Visibility
    // ------------------------------------------------------------------

    static volatile boolean stopFlagVolatile = false;

    static void demonstrateVolatile() throws InterruptedException {
        stopFlagVolatile = false;

        Thread reader = new Thread(() -> {
            long count = 0;
            while (!stopFlagVolatile) {
                count++;
            }
            System.out.println("  [reader] Thấy stopFlagVolatile=true sau " + count + " iterations ✓");
        });

        reader.start();
        Thread.sleep(5);
        stopFlagVolatile = true;
        System.out.println("  [writer] Đã set stopFlagVolatile=true");
        reader.join(1000);

        System.out.println();
        System.out.println("volatile đảm bảo:");
        System.out.println("  1. Ghi volatile -> FLUSH cache xuống main memory ngay lập tức");
        System.out.println("  2. Đọc volatile -> INVALIDATE cache, load từ main memory");
        System.out.println("  3. Tạo happens-before: mọi ghi trước volatile-write visible sau volatile-read");
    }

    // ------------------------------------------------------------------
    // PHẦN 3: volatile KHÔNG đủ cho Atomicity
    // i++ thực ra là 3 bước: READ i, ADD 1, WRITE i
    // ------------------------------------------------------------------

    static volatile int volatileCounter = 0;
    static int          normalCounter   = 0;
    static final AtomicInteger atomicCounter = new AtomicInteger(0);

    static void demonstrateVolatileNotAtomic() throws InterruptedException {
        int NUM_THREADS = 10;
        int INCREMENTS  = 10_000;

        volatileCounter = 0;
        normalCounter   = 0;
        atomicCounter.set(0);

        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        Thread[] threads = new Thread[NUM_THREADS];

        for (int i = 0; i < NUM_THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < INCREMENTS; j++) {
                    volatileCounter++; // KHÔNG atomic: read -> add -> write (3 ops)
                    normalCounter++;   // Cũng không atomic, cộng thêm visibility bug
                    atomicCounter.incrementAndGet(); // Atomic bằng CAS instruction
                }
                latch.countDown();
            });
        }

        for (Thread t : threads) t.start();
        latch.await();

        int expected = NUM_THREADS * INCREMENTS;
        System.out.printf("Expected         : %,d%n", expected);
        System.out.printf("normalCounter    : %,d  (race condition, SAIIIIII)%n", normalCounter);
        System.out.printf("volatileCounter  : %,d  (vẫn sai! volatile không fix atomicity)%n", volatileCounter);
        System.out.printf("atomicCounter    : %,d  (đúng! CAS = Compare-And-Swap)%n", atomicCounter.get());

        System.out.println();
        System.out.println("CAS hoạt động như thế nào:");
        System.out.println("  while (true) {");
        System.out.println("    int old = value;                    // đọc giá trị hiện tại");
        System.out.println("    int new = old + 1;                  // tính giá trị mới");
        System.out.println("    if (compareAndSet(old, new)) break; // chỉ ghi nếu value vẫn == old");
        System.out.println("  }                                     // nếu không -> retry");
        System.out.println("  -> Hardware instruction: LOCK XADD (x86), đảm bảo atomicity");
    }

    // ------------------------------------------------------------------
    // PHẦN 4: Happens-Before với Thread.start() và join()
    // ------------------------------------------------------------------
    static void demonstrateHappensBefore() throws InterruptedException {
        // Ghi dữ liệu TRƯỚC khi start thread
        final int[] sharedData = new int[3];
        sharedData[0] = 100;
        sharedData[1] = 200;
        sharedData[2] = 300;
        // Không cần volatile vì thread.start() tạo happens-before!
        // Mọi ghi trước start() đều visible trong thread mới

        Thread worker = new Thread(() -> {
            // Thread này GUARANTEED thấy sharedData đã init đúng
            // vì thread.start() happens-before mọi action trong thread này
            System.out.println("  [worker] sharedData[0]=" + sharedData[0]
                    + " [1]=" + sharedData[1] + " [2]=" + sharedData[2]);
            sharedData[0] = 999; // Ghi kết quả
        });

        worker.start();
        worker.join(); // join() happens-before dòng tiếp theo
        // Guaranteed thấy sharedData[0]=999 sau join()
        System.out.println("  [main] Sau join: sharedData[0]=" + sharedData[0]);
        System.out.println("  -> Không cần volatile vì start()/join() tạo happens-before boundary");
    }

    // ------------------------------------------------------------------
    // PHẦN 5: Instruction Reordering — Double-Checked Locking Pattern
    // ------------------------------------------------------------------

    // SAI (Java 4 trở về trước — bẫy kinh điển)
    static class SingletonBroken {
        private static SingletonBroken instance;
        private int value;

        private SingletonBroken() { value = 42; }

        public static SingletonBroken getInstance() {
            if (instance == null) {                    // Check 1 (no lock)
                synchronized (SingletonBroken.class) {
                    if (instance == null) {            // Check 2 (with lock)
                        instance = new SingletonBroken();
                        // BUG: 'new' có thể bị reorder thành:
                        // 1. Allocate memory
                        // 2. Gán instance = <reference>   <- thread khác thấy non-null!
                        // 3. Gọi constructor              <- chưa chạy xong!
                        // -> Thread khác nhận instance chưa init xong
                    }
                }
            }
            return instance; // có thể return object chưa init!
        }
    }

    // ĐÚNG — dùng volatile để ngăn reordering
    static class SingletonCorrect {
        private static volatile SingletonCorrect instance; // volatile = memory barrier
        private int value;

        private SingletonCorrect() { value = 42; }

        public static SingletonCorrect getInstance() {
            if (instance == null) {
                synchronized (SingletonCorrect.class) {
                    if (instance == null) {
                        instance = new SingletonCorrect();
                        // volatile write tạo memory barrier:
                        // constructor phải HOÀN TOÀN XONG trước khi gán instance
                    }
                }
            }
            return instance;
        }
    }

    // TỐT NHẤT — Initialization-on-demand holder (không cần volatile, không cần sync)
    static class SingletonBest {
        private int value;
        private SingletonBest() { value = 42; }

        private static class Holder {
            // Class này chỉ load khi getInstance() được gọi lần đầu
            // JVM đảm bảo class loading là thread-safe
            static final SingletonBest INSTANCE = new SingletonBest();
        }

        public static SingletonBest getInstance() {
            return Holder.INSTANCE; // không cần lock!
        }
    }

    static void demonstrateReordering() {
        System.out.println("Double-Checked Locking — 3 cách triển khai:");
        System.out.println();
        System.out.println("  ❌ SingletonBroken    : thiếu volatile -> reordering bug");
        System.out.println("                          Thread có thể nhận instance chưa init xong");
        System.out.println();
        System.out.println("  ✓  SingletonCorrect   : volatile instance -> memory barrier");
        System.out.println("                          Đúng nhưng vẫn có overhead synchronized");
        System.out.println();
        System.out.println("  ✓✓ SingletonBest      : Initialization-on-demand holder");
        System.out.println("                          Lazy, thread-safe, zero overhead!");
        System.out.println("                          Dùng cái này trong thực tế");

        // Demo Holder pattern hoạt động
        SingletonBest s1 = SingletonBest.getInstance();
        SingletonBest s2 = SingletonBest.getInstance();
        System.out.println();
        System.out.println("  s1 == s2: " + (s1 == s2) + " (cùng instance)");
    }

    // ------------------------------------------------------------------
    // PHẦN 6: Decision Guide — khi nào dùng gì
    // ------------------------------------------------------------------
    static void printDecisionGuide() {
        System.out.println("\n=== DECISION GUIDE: volatile vs synchronized vs Atomic ===");
        System.out.println("""
                ┌─────────────────┬──────────┬───────────┬────────────┐
                │                 │ volatile │synchroniz.│  Atomic    │
                ├─────────────────┼──────────┼───────────┼────────────┤
                │ Visibility      │    ✓     │     ✓     │     ✓      │
                │ Atomicity       │    ✗     │     ✓     │     ✓      │
                │ Ordering        │    ✓     │     ✓     │     ✓      │
                │ Mutual exclusion│    ✗     │     ✓     │     ✗      │
                │ Performance     │   HIGH   │    LOW    │    HIGH    │
                └─────────────────┴──────────┴───────────┴────────────┘

                Dùng volatile khi:
                  - Chỉ 1 thread GHI, nhiều thread ĐỌC (flag, state machine)
                  - Double-Checked Locking singleton
                  - Ví dụ: volatile boolean running, volatile int state

                Dùng AtomicXxx khi:
                  - Nhiều thread đọc-và-ghi (counter, accumulator)
                  - Cần lock-free performance cao
                  - Ví dụ: AtomicInteger counter, AtomicReference<State>

                Dùng synchronized khi:
                  - Cần bảo vệ NHIỀU BƯỚC liên tiếp (compound action)
                  - check-then-act: if (map.containsKey(k)) map.get(k)
                  - Cần mutual exclusion thực sự

                KHÔNG dùng:
                  - volatile cho i++ (race condition!)
                  - synchronized cho read-only (over-engineering)
                  - Cả hai khi có thể dùng ConcurrentHashMap / CopyOnWriteArrayList
                """);
    }
}
