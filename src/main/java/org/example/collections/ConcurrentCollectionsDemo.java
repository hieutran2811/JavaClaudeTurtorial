package org.example.collections;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ============================================================
 * BÀI 3.2 — Concurrent Collections
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Tại sao Collections.synchronizedXxx() không đủ tốt
 *   2. ConcurrentHashMap — segment striping, compute atomic, LongAdder
 *   3. CopyOnWriteArrayList — snapshot iterator, read-heavy use case
 *   4. BlockingQueue — producer-consumer pattern, back-pressure
 *   5. ConcurrentSkipListMap — sorted concurrent map
 *   6. Chọn đúng concurrent collection
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.collections.ConcurrentCollectionsDemo"
 * ============================================================
 */
public class ConcurrentCollectionsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 3.2: Concurrent Collections ===\n");

        demo1_WhySynchronizedIsNotEnough();
        demo2_ConcurrentHashMap();
        demo3_ConcurrentHashMapAtomicOps();
        demo4_CopyOnWriteArrayList();
        demo5_BlockingQueue();
        demo6_ChooserAndSummary();

        System.out.println("\n=== KẾT THÚC BÀI 3.2 ===");
    }

    // ================================================================
    // DEMO 1: Tại sao Collections.synchronizedXxx() không đủ
    // ================================================================

    /**
     * Collections.synchronizedMap(map) bọc mọi method bằng synchronized(mutex).
     * Tưởng là thread-safe — nhưng có 2 vấn đề lớn:
     *
     * VẤN ĐỀ 1 — Compound operations KHÔNG atomic:
     *   synchronized map vẫn có race condition với compound ops:
     *     if (!map.containsKey(k)) map.put(k, v);  ← 2 operation riêng lẻ, không atomic!
     *   Thread A check → Thread B check → cả 2 cùng put → lost update
     *
     * VẤN ĐỀ 2 — Iterator cần lock thủ công:
     *   for (String key : syncMap.keySet()) { ... }
     *   Nếu thread khác modify map trong lúc iterate → ConcurrentModificationException
     *   Phải synchronized(syncMap) { for (...) } — lock toàn bộ map trong suốt vòng lặp
     *   → Throughput rất thấp (global lock)
     *
     * VẤN ĐỀ 3 — Performance: 1 lock cho toàn bộ map → high contention
     *
     * SA INSIGHT: Collections.synchronized* chỉ phù hợp khi:
     *   - Single-threaded code nhưng cần pass vào API yêu cầu synchronized
     *   - Legacy code migration. Không dùng cho production concurrent access.
     */
    static void demo1_WhySynchronizedIsNotEnough() throws Exception {
        System.out.println("--- DEMO 1: Tại sao synchronizedMap không đủ ---");

        Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>());
        int THREADS = 50;
        CountDownLatch latch = new CountDownLatch(THREADS);

        // Race condition với check-then-act compound operation
        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                // BUG: containsKey + put không atomic dù map là synchronized!
                if (!syncMap.containsKey("counter")) {  // Thread A: false
                    syncMap.put("counter", 1);           // Thread B cũng vào đây!
                } else {
                    syncMap.put("counter", syncMap.get("counter") + 1);
                }
                latch.countDown();
            }).start();
        }
        latch.await(5, TimeUnit.SECONDS);

        System.out.println("  " + THREADS + " threads increment 'counter':");
        System.out.println("  synchronizedMap kết quả: " + syncMap.get("counter")
                + " (kỳ vọng: " + THREADS + " — thường sai vì race condition!)");

        // Fix: ConcurrentHashMap.merge() — atomic compound op
        ConcurrentHashMap<String, Integer> chm = new ConcurrentHashMap<>();
        CountDownLatch latch2 = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                chm.merge("counter", 1, Integer::sum); // Atomic: read + compute + write
                latch2.countDown();
            }).start();
        }
        latch2.await(5, TimeUnit.SECONDS);
        System.out.println("  ConcurrentHashMap.merge() kết quả: " + chm.get("counter")
                + " (luôn đúng!)\n");
    }

    // ================================================================
    // DEMO 2: ConcurrentHashMap — Segment Striping & Throughput
    // ================================================================

    /**
     * ConcurrentHashMap (Java 8+) internals:
     *
     *   JAVA 7: Segment-based — chia map thành 16 segment, mỗi segment có lock riêng
     *   JAVA 8+: Node-level locking — lock chỉ trên từng bucket (head node)
     *     → Concurrency level cao hơn nhiều (lock granularity nhỏ hơn)
     *     → Reads thường lock-free (volatile read)
     *     → Writes lock chỉ 1 bucket (không phải toàn map)
     *
     * KẾT QUẢ: Nhiều thread có thể read/write ĐỒNG THỜI vào các bucket khác nhau
     *
     * SO SÁNH THROUGHPUT:
     *   Hashtable (global lock):         1x
     *   Collections.synchronizedMap:     ~1x (cùng global lock)
     *   ConcurrentHashMap:               ~8-16x (tuỳ số core và contention)
     *
     * SIZE(): CHÚ Ý — ConcurrentHashMap.size() là APPROXIMATE trong concurrent env!
     *   Java 8 dùng CounterCell (như LongAdder) để tránh contention khi count
     *   Dùng mappingCount() cho giá trị long nếu size > Integer.MAX_VALUE
     *
     * NULL: ConcurrentHashMap KHÔNG cho phép null key hoặc null value!
     *   (HashMap cho phép 1 null key và nhiều null value)
     *   Lý do: null value không phân biệt được "key không tồn tại" vs "value là null"
     *   trong concurrent context → ambiguity nguy hiểm
     */
    static void demo2_ConcurrentHashMap() throws Exception {
        System.out.println("--- DEMO 2: ConcurrentHashMap vs Hashtable throughput ---");

        int THREADS = 8, OPS_PER_THREAD = 10_000;

        // Benchmark Hashtable (legacy — global synchronized lock)
        long hashtableTime = benchmarkMap(new Hashtable<>(), THREADS, OPS_PER_THREAD);

        // Benchmark synchronizedMap
        long syncTime = benchmarkMap(Collections.synchronizedMap(new HashMap<>()), THREADS, OPS_PER_THREAD);

        // Benchmark ConcurrentHashMap
        long chmTime = benchmarkMap(new ConcurrentHashMap<>(), THREADS, OPS_PER_THREAD);

        System.out.println("  " + THREADS + " threads × " + OPS_PER_THREAD + " ops (50% read / 50% write):");
        System.out.println("  Hashtable:          " + hashtableTime + "ms");
        System.out.println("  synchronizedMap:    " + syncTime + "ms");
        System.out.println("  ConcurrentHashMap:  " + chmTime + "ms  ← thường nhanh nhất");

        // Null không được phép
        ConcurrentHashMap<String, String> chm = new ConcurrentHashMap<>();
        try {
            chm.put(null, "value");
        } catch (NullPointerException e) {
            System.out.println("\n  CHM null key → NullPointerException (by design)");
        }
        try {
            chm.put("key", null);
        } catch (NullPointerException e) {
            System.out.println("  CHM null value → NullPointerException (by design)\n");
        }
    }

    static long benchmarkMap(Map<Integer, Integer> map, int threads, int opsPerThread) throws Exception {
        // Warm up
        for (int i = 0; i < 100; i++) map.put(i, i);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        Random rnd = new Random(42);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) {}
                for (int i = 0; i < opsPerThread; i++) {
                    int key = rnd.nextInt(1000);
                    if (i % 2 == 0) map.put(key, key);
                    else            map.get(key);
                }
                done.countDown();
            }).start();
        }
        ready.await();
        long t = System.currentTimeMillis();
        start.countDown();
        done.await();
        return System.currentTimeMillis() - t;
    }

    // ================================================================
    // DEMO 3: ConcurrentHashMap — Atomic Operations thực tế
    // ================================================================

    /**
     * Các atomic operations của ConcurrentHashMap — KHÔNG cần lock thủ công:
     *
     *   putIfAbsent(k, v)        — put nếu key chưa tồn tại, atomic
     *   computeIfAbsent(k, fn)   — compute + put nếu key chưa tồn tại, atomic
     *   computeIfPresent(k, fn)  — compute nếu key đã tồn tại, atomic
     *   compute(k, fn)           — always compute, atomic (fn có thể return null để remove)
     *   merge(k, v, fn)          — merge value với existing, atomic
     *   getOrDefault(k, def)     — get với fallback nếu không tìm thấy
     *
     * LongAdder vs AtomicLong cho counter trong ConcurrentHashMap:
     *   AtomicLong: single CAS cell → contention khi nhiều thread cùng update
     *   LongAdder:  nhiều cell, thread update cell riêng → sum khi cần → ít contention
     *   → LongAdder tốt hơn AtomicLong khi nhiều writer, ít reader (counter, metric)
     *
     * USE CASE THỰC TẾ:
     *   - Word count, frequency map
     *   - Request counter per endpoint
     *   - Cache with lazy initialization
     *   - Group-by concurrent processing
     */
    static void demo3_ConcurrentHashMapAtomicOps() throws Exception {
        System.out.println("--- DEMO 3: ConcurrentHashMap Atomic Operations ---");

        // 1. Word frequency count — concurrent, không cần lock
        ConcurrentHashMap<String, LongAdder> wordCount = new ConcurrentHashMap<>();
        String[] words = {"apple", "banana", "apple", "cherry", "banana", "apple",
                          "date", "cherry", "banana", "elderberry"};

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(words.length);

        for (String word : words) {
            pool.submit(() -> {
                // computeIfAbsent + increment — atomic per-key
                wordCount.computeIfAbsent(word, k -> new LongAdder()).increment();
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        System.out.println("  Word count (concurrent): " + wordCount);

        // 2. Cache với lazy initialization — chỉ tính 1 lần dù nhiều thread gọi cùng lúc
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        AtomicInteger computeCount = new AtomicInteger(0);

        CountDownLatch latch2 = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            pool.submit(() -> {
                // computeIfAbsent đảm bảo fn chỉ chạy 1 lần cho mỗi key
                // dù 20 thread cùng gọi
                String result = cache.computeIfAbsent("config", k -> {
                    computeCount.incrementAndGet(); // Đếm số lần thực sự tính toán
                    sleep(10);                      // Giả lập I/O đọc config
                    return "loaded-config-value";
                });
                latch2.countDown();
            });
        }
        latch2.await(5, TimeUnit.SECONDS);
        System.out.println("  Cache lazy-init: 20 threads → fn chỉ chạy "
                + computeCount.get() + " lần (dù 20 thread gọi cùng lúc)");

        // 3. merge — request counter per endpoint
        ConcurrentHashMap<String, Integer> reqCount = new ConcurrentHashMap<>();
        String[] endpoints = {"/api/users", "/api/orders", "/api/users", "/api/products",
                              "/api/users", "/api/orders", "/api/users"};

        for (String ep : endpoints) {
            reqCount.merge(ep, 1, Integer::sum); // atomic: value = existing + 1
        }
        System.out.println("  Request counter per endpoint: " + reqCount);

        // 4. compute để update complex object
        ConcurrentHashMap<String, List<String>> userGroups = new ConcurrentHashMap<>();
        String[][] assignments = {{"admin", "Alice"}, {"admin", "Bob"},
                                   {"user", "Charlie"}, {"admin", "Dave"}, {"user", "Eve"}};
        for (String[] assign : assignments) {
            userGroups.compute(assign[0], (role, users) -> {
                if (users == null) users = new ArrayList<>();
                users.add(assign[1]);
                return users;
            });
        }
        System.out.println("  Group-by role: " + userGroups + "\n");

        pool.shutdown();
    }

    // ================================================================
    // DEMO 4: CopyOnWriteArrayList — Snapshot Iterator
    // ================================================================

    /**
     * CopyOnWriteArrayList (COW):
     *   - Mỗi lần write (add/set/remove) → tạo bản COPY của array → write vào copy → swap
     *   - Read (get, iteration) → dùng array hiện tại, KHÔNG cần lock
     *   - Iterator → giữ snapshot của array tại thời điểm iterator được tạo
     *     → Không bao giờ ConcurrentModificationException khi iterate
     *     → Nhưng iterator không thấy thay đổi xảy ra SAU khi nó được tạo
     *
     * KHI DÙNG CopyOnWriteArrayList:
     *   ✓ Iterate rất nhiều, write rất ít
     *   ✓ Event listener list, plugin list, observer list
     *   ✓ Config/feature flag list được đọc liên tục, update hiếm
     *
     * KHÔNG DÙNG KHI:
     *   ✗ Write nhiều — mỗi write copy toàn bộ array → O(n) memory + time
     *   ✗ Large list — copy 1M element mỗi khi add 1 phần tử → không chấp nhận được
     *
     * SA INSIGHT: Spring ApplicationContext listener list dùng CopyOnWrite.
     *   Kafka consumer rebalance listener, event bus subscriber list — đây là pattern đúng.
     */
    static void demo4_CopyOnWriteArrayList() throws Exception {
        System.out.println("--- DEMO 4: CopyOnWriteArrayList ---");

        CopyOnWriteArrayList<String> listeners = new CopyOnWriteArrayList<>();
        listeners.add("EmailNotifier");
        listeners.add("SlackNotifier");
        listeners.add("SMSNotifier");

        System.out.println("  Listeners ban đầu: " + listeners);

        // Iterate và modify đồng thời — KHÔNG ConcurrentModificationException
        Iterator<String> iter = listeners.iterator(); // Snapshot tại đây

        listeners.add("WebhookNotifier");    // Modify trong khi đang giữ iterator
        listeners.remove("SMSNotifier");     // Modify thêm

        System.out.println("  Listeners sau modify: " + listeners);
        System.out.print("  Iterator snapshot (không thấy thay đổi): ");
        while (iter.hasNext()) System.out.print(iter.next() + " | ");
        System.out.println();

        // So sánh với ArrayList — ConcurrentModificationException
        List<String> regularList = new ArrayList<>(List.of("A", "B", "C"));
        try {
            for (String item : regularList) {
                regularList.add("X"); // ConcurrentModificationException!
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("  ArrayList modify khi iterate → ConcurrentModificationException ✓");
        }

        // Benchmark: COW đọc nhanh, ghi chậm
        int READERS = 10, WRITES = 100;
        CopyOnWriteArrayList<Integer> cowList = new CopyOnWriteArrayList<>();
        List<Integer> syncList = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 1000; i++) { cowList.add(i); syncList.add(i); }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch latch = new CountDownLatch(READERS + 2);

        long start = System.currentTimeMillis();
        // Read-heavy: 10 readers, 2 writers
        for (int i = 0; i < READERS; i++) {
            pool.submit(() -> {
                long sum = 0;
                for (int x : cowList) sum += x; // Snapshot iterator — no lock
                latch.countDown();
            });
        }
        for (int i = 0; i < 2; i++) {
            final int fi = i;
            pool.submit(() -> {
                for (int w = 0; w < WRITES / 2; w++) cowList.add(fi * 1000 + w);
                latch.countDown();
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        long cowTime = System.currentTimeMillis() - start;

        System.out.println("\n  " + READERS + " readers + 2 writers:");
        System.out.println("  CopyOnWriteArrayList: " + cowTime + "ms (readers không bị block)\n");

        pool.shutdown();
    }

    // ================================================================
    // DEMO 5: BlockingQueue — Producer-Consumer Pattern
    // ================================================================

    /**
     * BlockingQueue: Queue với blocking semantics
     *   put(e)    — block nếu queue đầy (wait cho đến khi có chỗ)
     *   take()    — block nếu queue trống (wait cho đến khi có phần tử)
     *   offer(e, timeout) — thử put trong timeout, return false nếu đầy
     *   poll(timeout)     — thử take trong timeout, return null nếu trống
     *
     * BACK-PRESSURE: put() bị block khi queue đầy → Producer bị throttle tự nhiên
     *   → Không cần code phức tạp để giới hạn tốc độ producer
     *   → Queue size = buffer giữa producer speed và consumer speed
     *
     * CÁC LOẠI BlockingQueue:
     *
     *   ArrayBlockingQueue(n):   Bounded, fair/unfair, FIFO, backed by array
     *   LinkedBlockingQueue(n):  Bounded (hoặc unlimited nếu không set), FIFO, 2 lock (head/tail)
     *   PriorityBlockingQueue:   Unbounded, ordered by priority, không block put
     *   SynchronousQueue:        Zero capacity! put() block cho đến khi có take() — handoff
     *   DelayQueue:              Elements only available after delay expires
     *   LinkedTransferQueue:     Combination + transfer() blocks until consumer picks up
     *
     * SA INSIGHT: ThreadPoolExecutor dùng BlockingQueue làm task queue (bài 2.4).
     *   Kafka consumer là BlockingQueue ở quy mô distributed.
     *   Rate limiting, work queue, pipeline — đây là pattern nền tảng.
     */
    static void demo5_BlockingQueue() throws Exception {
        System.out.println("--- DEMO 5: BlockingQueue — Producer-Consumer ---");

        // 1. Basic producer-consumer với ArrayBlockingQueue
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(5); // Buffer tối đa 5 item
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);

        // Producer — tạo task nhanh hơn consumer
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    String item = "task-" + i;
                    queue.put(item); // Block khi queue đầy (back-pressure!)
                    produced.incrementAndGet();
                    System.out.println("  Produced: " + item + " | queue size: " + queue.size());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "Producer");

        // Consumer — xử lý chậm hơn producer
        Thread consumer = new Thread(() -> {
            while (true) {
                try {
                    String item = queue.poll(500, TimeUnit.MILLISECONDS); // Timeout để biết khi nào dừng
                    if (item == null) break; // Producer đã xong và queue trống
                    sleep(80); // Giả lập xử lý chậm
                    consumed.incrementAndGet();
                    System.out.println("  Consumed: " + item);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "Consumer");

        producer.start(); consumer.start();
        producer.join(); consumer.join(5000);
        consumer.interrupt();
        System.out.println("  Produced: " + produced + " | Consumed: " + consumed);

        // 2. SynchronousQueue — handoff trực tiếp, zero buffer
        System.out.println("\n  SynchronousQueue — producer block cho đến khi consumer nhận:");
        SynchronousQueue<String> handoff = new SynchronousQueue<>();

        Thread sender = new Thread(() -> {
            try {
                System.out.println("  Sender: đang chờ receiver...");
                handoff.put("HANDOFF-DATA"); // Block cho đến khi receiver take()
                System.out.println("  Sender: đã bàn giao xong");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        Thread receiver = new Thread(() -> {
            try {
                sleep(200); // Receiver đến muộn
                String data = handoff.take();
                System.out.println("  Receiver: nhận được '" + data + "'");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        sender.start(); receiver.start();
        sender.join(3000); receiver.join(3000);

        // 3. DelayQueue — task chỉ available sau delay (retry with backoff, scheduled cleanup)
        System.out.println("\n  DelayQueue — task available sau delay:");
        DelayQueue<DelayedTask> delayQueue = new DelayQueue<>();
        long now = System.currentTimeMillis();
        delayQueue.put(new DelayedTask("retry-immediately", now));
        delayQueue.put(new DelayedTask("retry-after-200ms", now + 200));
        delayQueue.put(new DelayedTask("retry-after-100ms", now + 100));

        for (int i = 0; i < 3; i++) {
            DelayedTask task = delayQueue.take(); // Block cho đến khi task sẵn sàng
            System.out.println("  Taken: " + task.name + " at +" + (System.currentTimeMillis() - now) + "ms");
        }
        System.out.println();
    }

    static class DelayedTask implements Delayed {
        String name; long readyAt;
        DelayedTask(String name, long readyAt) { this.name = name; this.readyAt = readyAt; }
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(readyAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public int compareTo(Delayed o) {
            return Long.compare(readyAt, ((DelayedTask) o).readyAt);
        }
    }

    // ================================================================
    // DEMO 6: Bảng chọn Concurrent Collection
    // ================================================================

    /**
     * ConcurrentSkipListMap — sorted concurrent map (thay TreeMap trong concurrent context)
     *   - Thread-safe, không cần external lock
     *   - O(log n) get/put/remove
     *   - Hỗ trợ range queries như TreeMap (firstKey, lastKey, subMap, ...)
     *   - Dùng Skip List thay Red-Black Tree → lockless nhiều hơn
     *
     * ConcurrentSkipListSet — sorted concurrent set
     */
    static void demo6_ChooserAndSummary() throws Exception {
        System.out.println("--- DEMO 6: ConcurrentSkipListMap & Decision Guide ---");

        // ConcurrentSkipListMap — sorted + concurrent
        ConcurrentSkipListMap<Integer, String> leaderboard = new ConcurrentSkipListMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Random rnd = new Random(42);
        CountDownLatch latch = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            final int score = rnd.nextInt(100);
            final String player = "Player-" + i;
            pool.submit(() -> {
                leaderboard.put(score, player);
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("  ConcurrentSkipListMap leaderboard (sorted):");
        System.out.println("  Top 3 thấp nhất: " + leaderboard.firstEntry()
                + ", " + leaderboard.higherEntry(leaderboard.firstKey())
                + ", ...");
        System.out.println("  Top scorer: " + leaderboard.lastEntry());
        System.out.println("  Score 50-80: " + leaderboard.subMap(50, 80));

        System.out.println();
        System.out.println("=== BẢNG CHỌN CONCURRENT COLLECTION ===");
        System.out.println();
        System.out.println("  BÀI TOÁN                              → COLLECTION");
        System.out.println("  ──────────────────────────────────────────────────────────────");
        System.out.println("  Thread-safe key-value, high throughput → ConcurrentHashMap");
        System.out.println("  Atomic counter/freq map                → CHM + LongAdder");
        System.out.println("  Lazy init cache                        → CHM.computeIfAbsent");
        System.out.println("  Thread-safe sorted map / range query   → ConcurrentSkipListMap");
        System.out.println("  Thread-safe sorted set                 → ConcurrentSkipListSet");
        System.out.println("  Iterate nhiều, write ít (listener)     → CopyOnWriteArrayList");
        System.out.println("  Producer-consumer, bounded buffer      → ArrayBlockingQueue");
        System.out.println("  Producer-consumer, unbounded           → LinkedBlockingQueue");
        System.out.println("  Direct handoff producer→consumer       → SynchronousQueue");
        System.out.println("  Scheduled / retry-with-backoff         → DelayQueue");
        System.out.println("  Priority task processing               → PriorityBlockingQueue");
        System.out.println();
        System.out.println("  KHÔNG DÙNG:");
        System.out.println("  Hashtable               → thay bằng ConcurrentHashMap");
        System.out.println("  Collections.synchronized* → thay bằng concurrent collections");
        System.out.println("  Vector                  → thay bằng CopyOnWriteArrayList hoặc ArrayList+lock");

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 3.2 ===");
        System.out.println("  ✓ synchronizedMap: compound op KHÔNG atomic → dùng CHM.merge/compute");
        System.out.println("  ✓ ConcurrentHashMap: node-level lock, read lock-free, null bị cấm");
        System.out.println("  ✓ CopyOnWriteArrayList: snapshot iterator, chỉ dùng read-heavy");
        System.out.println("  ✓ BlockingQueue: back-pressure tự nhiên, put() block khi đầy");
        System.out.println("  ✓ ConcurrentSkipListMap: sorted + thread-safe = thay TreeMap");
        System.out.println("  → Bài tiếp: 3.3 GenericsAdvancedDemo — Wildcards, type erasure, bounds");
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
