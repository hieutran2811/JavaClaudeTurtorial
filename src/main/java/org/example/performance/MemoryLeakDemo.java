package org.example.performance;

import java.lang.ref.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * =============================================================================
 * BÀI 5.3 — Memory Leak: Detect, Fix & Reference Types
 * =============================================================================
 *
 * Memory leak trong Java KHÔNG phải "quên free()" như C/C++.
 * Định nghĩa: Object không còn được dùng nhưng vẫn còn REACHABLE → GC không collect.
 *
 * TOP 5 NGUYÊN NHÂN MEMORY LEAK THỰC TẾ:
 *   1. Static collections không bị clear (cache không giới hạn)
 *   2. Event listener / callback không được deregister
 *   3. Inner class giữ reference đến outer class (anonymous Runnable, Lambda)
 *   4. ThreadLocal không được remove() → thread pool leak
 *   5. Unclosed resource: Connection, InputStream trong heap (không GC được)
 *
 * REFERENCE TYPES (Java bộ công cụ chống leak):
 *   Strong  → Object NEVER collected khi còn reference (default, new Foo())
 *   Soft    → Collected khi JVM sắp OOM (dùng cho memory-sensitive cache)
 *   Weak    → Collected ở GC tiếp theo khi không còn strong ref (WeakHashMap)
 *   Phantom → Object đã finalized, dùng để cleanup trước khi GC reclaim memory
 *
 * DETECTION TOOLS:
 *   jmap -heap <pid>          — Heap summary
 *   jmap -histo <pid>         — Object histogram (top classes by instance count)
 *   jmap -dump:format=b,file=heap.hprof <pid>  — Heap dump
 *   jcmd <pid> GC.heap_info  — Java 9+
 *   Eclipse MAT / VisualVM    — Analyze heap dump offline
 *   async-profiler --alloc    — Allocation profiling
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.performance.MemoryLeakDemo"
 */
public class MemoryLeakDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 5.3 — Memory Leak: Detect, Fix & Reference Types");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_staticCollectionLeak();
        demo2_listenerLeak();
        demo3_threadLocalLeak();
        demo4_innerClassLeak();
        demo5_referenceTypes();
        demo6_weakHashMapCache();
        demo7_softReferenceCache();
        demo8_phantomReference();
        demo9_leakDetectionTechniques();
        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Static Collection Leak (pattern #1 ở production)
    // =========================================================================

    // LEAK: static Map không giới hạn size — class tồn tại suốt JVM lifetime
    static final Map<String, byte[]> LEAKY_CACHE = new HashMap<>();

    // FIX: bounded cache với eviction
    static final int MAX_CACHE_SIZE = 100;
    static final LinkedHashMap<String, byte[]> BOUNDED_CACHE =
        new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {   // accessOrder=true → LRU
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_CACHE_SIZE;   // evict oldest khi vượt limit
            }
        };

    static void demo1_staticCollectionLeak() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Static Collection Leak & Bounded LRU Cache Fix");
        System.out.println("─".repeat(70));

        System.out.println("""

            LEAK PATTERN:
              static Map<String, Data> cache = new HashMap<>();   // ← LEAK!

              void processRequest(String userId) {
                  cache.put(userId, fetchData(userId));  // grows forever
              }
              // Sau 1 triệu requests → 1 triệu entries trong cache → OOM

            FIX: Bounded cache với LRU eviction (LinkedHashMap trick):
            """);

        // Simulate leaky cache growth
        System.out.println("  [LEAKY CACHE — adding 500 entries, never evicts]");
        for (int i = 0; i < 500; i++) {
            LEAKY_CACHE.put("user-" + i, new byte[1024]); // 1KB per entry
        }
        System.out.printf("  Leaky cache size: %,d entries (%.1f KB, grows forever)%n",
            LEAKY_CACHE.size(), LEAKY_CACHE.size() * 1024.0 / 1024);

        // Simulate bounded LRU cache
        System.out.println("\n  [BOUNDED LRU CACHE — capped at 100 entries]");
        for (int i = 0; i < 500; i++) {
            BOUNDED_CACHE.put("user-" + i, new byte[1024]);
        }
        System.out.printf("  Bounded cache size: %d entries (never exceeds %d)%n",
            BOUNDED_CACHE.size(), MAX_CACHE_SIZE);

        // Verify LRU eviction
        System.out.println("  Accessing user-450 to make it 'recently used'...");
        BOUNDED_CACHE.get("user-450");   // promote to recently used

        System.out.println("""

            SA Insight:
              • Không dùng unbounded static Map làm cache — dùng Caffeine/Guava Cache
              • Caffeine: maximumSize, expireAfterWrite, expireAfterAccess, softValues
              • Example: Cache<K,V> cache = Caffeine.newBuilder()
                              .maximumSize(10_000)
                              .expireAfterWrite(10, MINUTES)
                              .recordStats()
                              .build();
            """);
    }

    // =========================================================================
    // DEMO 2 — Listener / Callback Leak
    // =========================================================================

    interface EventListener {
        void onEvent(String event);
    }

    static class EventBus {
        private final List<EventListener> listeners = new ArrayList<>();

        void register(EventListener listener) {
            listeners.add(listener);
        }

        // LEAK FIX: provide deregister method
        void deregister(EventListener listener) {
            listeners.remove(listener);
        }

        void publish(String event) {
            listeners.forEach(l -> l.onEvent(event));
        }

        int listenerCount() { return listeners.size(); }
    }

    static class Service {
        private final String name;
        private final byte[] data = new byte[10_240]; // 10KB payload — held by listener closure

        Service(String name) { this.name = name; }

        EventListener createListener() {
            // Lambda captures 'this' → Service instance stays alive as long as listener exists
            return event -> System.out.printf("      [%s] received: %s%n", name, event);
        }
    }

    static void demo2_listenerLeak() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 2 — Event Listener Leak & Deregister Fix");
        System.out.println("─".repeat(70));

        System.out.println("""

            LEAK PATTERN:
              class UserSession {
                  byte[] sessionData = new byte[100_000]; // 100KB

                  void init(EventBus bus) {
                      bus.register(event -> handleEvent(event)); // captures 'this'
                      // Session "ends" but EventBus still holds ref → 100KB leak per session
                  }
                  // Sau 10,000 sessions → 1GB leak!
              }
            """);

        EventBus bus = new EventBus();

        // Simulate services registering listeners (without deregister)
        System.out.println("  [LEAKY: registering 5 listeners without deregistering]");
        for (int i = 0; i < 5; i++) {
            Service svc = new Service("Service-" + i);
            bus.register(svc.createListener()); // svc captured by lambda → never GC'd
        }
        System.out.printf("  Listeners registered: %d (services kept alive by bus)%n", bus.listenerCount());
        bus.publish("test-event");

        // Fix: proper registration with deregister
        System.out.println("\n  [FIX: register and deregister lifecycle]");
        EventBus cleanBus = new EventBus();
        Service managed = new Service("ManagedService");
        EventListener listener = managed.createListener();

        cleanBus.register(listener);
        System.out.printf("  After register: %d listener(s)%n", cleanBus.listenerCount());
        cleanBus.publish("hello");

        cleanBus.deregister(listener);  // cleanup khi service shutdown
        System.out.printf("  After deregister: %d listener(s) — service can be GC'd%n", cleanBus.listenerCount());

        System.out.println("""

            PATTERNS TRÁNH LISTENER LEAK:
              1. Dùng WeakReference<EventListener> trong bus → listener tự GC khi không ai giữ
              2. AutoCloseable registration: try (var reg = bus.register(l)) { ... }
              3. Annotation-based: @Subscribe + lifecycle management (Guava EventBus)
              4. Reactive: Disposable sub = observable.subscribe(...); sub.dispose();
            """);
    }

    // =========================================================================
    // DEMO 3 — ThreadLocal Leak trong Thread Pool
    // =========================================================================

    static final ThreadLocal<byte[]> REQUEST_CONTEXT = new ThreadLocal<>();

    static void demo3_threadLocalLeak() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 3 — ThreadLocal Leak trong Thread Pool");
        System.out.println("─".repeat(70));

        System.out.println("""

            LEAK PATTERN (phổ biến ở web framework + thread pool):
              ThreadLocal<RequestContext> ctx = new ThreadLocal<>();

              // Trong request handler:
              ctx.set(new RequestContext(user, session, ...)); // 50KB per request
              processRequest();
              // FORGOT ctx.remove() ← LEAK!

              // Thread pool reuses thread → ThreadLocal vẫn còn data của request cũ
              // → 50KB × thread pool size = N MB stuck forever
              // → Còn gây bug: request B đọc được data của request A!
            """);

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // Simulate leak: set ThreadLocal, không remove
        System.out.println("  [LEAK: set ThreadLocal without remove]");
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.submit(() -> {
                REQUEST_CONTEXT.set(new byte[1024]); // set but never remove
                System.out.printf("    Thread %s: set 1KB context for request-%d%n",
                    Thread.currentThread().getName(), idx);
                latch.countDown();
                // Thread returns to pool → ThreadLocal data persists in thread!
            });
        }
        latch.await();

        System.out.println("\n  [FIX: always remove in try-finally]");
        CountDownLatch latch2 = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    REQUEST_CONTEXT.set(new byte[1024]);
                    System.out.printf("    Thread %s: processing request-%d%n",
                        Thread.currentThread().getName(), idx);
                    // ... do work ...
                } finally {
                    REQUEST_CONTEXT.remove();   // ALWAYS remove in finally
                    System.out.printf("    Thread %s: cleaned up request-%d context%n",
                        Thread.currentThread().getName(), idx);
                }
                latch2.countDown();
            });
        }
        latch2.await();
        pool.shutdown();

        System.out.println("""

            SA RULE: ThreadLocal Pattern (3 bước bắt buộc):
              try {
                  threadLocal.set(value);      // 1. Set
                  doWork();                    // 2. Use
              } finally {
                  threadLocal.remove();        // 3. ALWAYS remove in finally
              }

            PHÁT HIỆN ThreadLocal leak:
              • heap dump: tìm ThreadLocalMap$Entry[] với unexpected values
              • jstack: threads giữ ThreadLocal sau khi request xong
              • Micrometer: monitor thread count + heap usage per thread
            """);
    }

    // =========================================================================
    // DEMO 4 — Inner Class / Anonymous Class Leak
    // =========================================================================

    static void demo4_innerClassLeak() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 4 — Inner Class & Lambda Capture Leak");
        System.out.println("─".repeat(70));

        System.out.println("""

            LEAK PATTERN — Non-static inner class:
              class Activity {
                  byte[] bitmap = new byte[5_000_000]; // 5MB

                  class MyTask implements Runnable {   // non-static → holds Activity ref!
                      public void run() { /* long running task */ }
                  }

                  void start() {
                      new Thread(new MyTask()).start();
                      // Task chạy lâu → Activity (5MB) không được GC
                  }
              }

            FIX OPTIONS:
              1. Static nested class (không có implicit outer ref):
                   static class MyTask implements Runnable { ... }

              2. WeakReference trong inner class:
                   WeakReference<Activity> actRef = new WeakReference<>(activity);

              3. Lambda capture chỉ những gì cần (không capture 'this' ẩn):
                   // LEAK:   executor.submit(() -> this.process(data));
                   // FIX:    String localData = this.data;
                   //         executor.submit(() -> process(localData));

            LAMBDA CAPTURE RULES:
              • Lambda capturing 'this' → holds strong ref to enclosing object
              • Lambda capturing local variable → variable must be effectively final
              • Lambda in static context → no implicit 'this' → safer
            """);

        // Demo: static vs non-static task
        class HeavyObject {
            byte[] data = new byte[100_000]; // 100KB

            // LEAK: non-static inner class captures HeavyObject via implicit 'this'
            Runnable createLeakyTask() {
                return () -> {
                    // This lambda captures HeavyObject.this (100KB)
                    int len = data.length; // access via implicit capture
                    System.out.printf("    Leaky task: data.length = %,d bytes%n", len);
                };
            }

            // FIX: capture only what's needed
            Runnable createSafeTask() {
                int len = data.length;   // capture primitive (no object ref)
                return () -> System.out.printf("    Safe task: data.length = %,d bytes%n", len);
                // HeavyObject can be GC'd even while Runnable is alive
            }
        }

        HeavyObject obj = new HeavyObject();
        Runnable leaky = obj.createLeakyTask();
        Runnable safe  = obj.createSafeTask();

        // obj không thể GC'd vì leaky lambda giữ reference
        // safe lambda chỉ giữ int primitive → obj có thể GC'd

        leaky.run();
        safe.run();

        System.out.println("    → Leaky task: HeavyObject còn bị giữ dù không cần nữa");
        System.out.println("    → Safe task:  HeavyObject có thể được GC sau khi createSafeTask() return");
    }

    // =========================================================================
    // DEMO 5 — Reference Types: Strong, Soft, Weak, Phantom
    // =========================================================================

    static void demo5_referenceTypes() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Reference Types: Strong / Soft / Weak / Phantom");
        System.out.println("─".repeat(70));

        System.out.println("""

            JAVA REFERENCE HIERARCHY:
            ┌─────────────┬──────────────────────────┬────────────────────────────┐
            │ Type        │ GC Behavior               │ Use Case                   │
            ├─────────────┼──────────────────────────┼────────────────────────────┤
            │ Strong Ref  │ NEVER collected (default) │ Normal object reference     │
            │ Soft Ref    │ Collected if OOM imminent │ Memory-sensitive cache      │
            │ Weak Ref    │ Collected at next GC      │ Canonical mapping, metadata │
            │ Phantom Ref │ After finalization        │ Pre-mortem cleanup actions  │
            └─────────────┴──────────────────────────┴────────────────────────────┘

            ReferenceQueue: JVM enqueues Reference khi object đã được GC
              → Dùng để nhận notification "object X đã bị GC"
            """);

        // Strong Reference
        System.out.println("  [Strong Reference — never GC'd while referenced]");
        byte[] strongRef = new byte[1024];
        System.out.printf("    Strong ref to 1KB array: %s%n", strongRef != null ? "alive" : "null");

        // Weak Reference
        System.out.println("\n  [Weak Reference — collected at next GC]");
        ReferenceQueue<byte[]> weakQueue = new ReferenceQueue<>();
        WeakReference<byte[]> weakRef = new WeakReference<>(new byte[1024], weakQueue);

        System.out.printf("    Before GC: weakRef.get() = %s%n",
            weakRef.get() != null ? "byte[1024] alive" : "null");

        System.gc();
        Thread.sleep(100);

        System.out.printf("    After GC:  weakRef.get() = %s%n",
            weakRef.get() != null ? "still alive" : "null (GC'd!)");

        Reference<?> enqueuedRef = weakQueue.poll();
        System.out.printf("    Reference in queue: %s%n",
            enqueuedRef != null ? "YES — object was GC'd" : "not yet enqueued");

        // Soft Reference
        System.out.println("\n  [Soft Reference — survives normal GC, collected only on OOM]");
        SoftReference<byte[]> softRef = new SoftReference<>(new byte[512]);

        System.gc();
        Thread.sleep(100);

        System.out.printf("    After GC: softRef.get() = %s%n",
            softRef.get() != null ? "byte[512] ALIVE (soft refs survive normal GC)" : "null");
        System.out.println("    → Soft refs only cleared when heap is critically low (OOM imminent)");
        System.out.println("    → Ideal for image/result cache that should yield under memory pressure");

        // Phantom Reference
        System.out.println("\n  [Phantom Reference — post-finalization cleanup]");
        ReferenceQueue<byte[]> phantomQueue = new ReferenceQueue<>();

        // Phantom ref: ALWAYS returns null from get() — read object only via subclass
        PhantomReference<byte[]> phantomRef = new PhantomReference<>(new byte[256], phantomQueue);

        System.out.printf("    phantomRef.get() = %s (ALWAYS null — by design)%n", phantomRef.get());
        System.out.println("    → Purpose: detect when object is about to be reclaimed");
        System.out.println("    → Use: off-heap cleanup (DirectByteBuffer, native resources)");

        System.gc();
        Thread.sleep(100);
        Reference<?> phantom = phantomQueue.poll();
        System.out.printf("    After GC: phantom in queue = %s%n",
            phantom != null ? "YES — object finalized, safe to cleanup" : "not yet");
    }

    // =========================================================================
    // DEMO 6 — WeakHashMap: Auto-expiring cache
    // =========================================================================

    static void demo6_weakHashMapCache() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — WeakHashMap: Automatic Cache Expiration");
        System.out.println("─".repeat(70));

        System.out.println("""

            WeakHashMap: Map với WEAK KEYS
              • Khi key không còn strong reference nào → entry tự động bị xóa
              • Dùng cho: canonical mapping, metadata, supplementary data
              • KHÔNG dùng với String literals (String pool = always strong ref!)

            Use case: Object augmentation
              class Connection { ... }  // external object we don't control

              WeakHashMap<Connection, Metadata> metadata = new WeakHashMap<>();
              metadata.put(conn, new Metadata(conn));
              // Khi conn GC'd → entry tự động removed → no leak!
            """);

        WeakHashMap<Object, String> weakMap = new WeakHashMap<>();

        // Key mạnh → entry tồn tại
        Object key1 = new Object();
        Object key2 = new Object();
        weakMap.put(key1, "value-1");
        weakMap.put(key2, "value-2");

        System.out.printf("  Before GC: map size = %d%n", weakMap.size());

        // Xóa strong reference → key trở thành weakly reachable
        key2 = null;  // no more strong ref to key2
        System.gc();
        Thread.sleep(100);

        System.out.printf("  After GC (key2=null): map size = %d%n", weakMap.size());
        System.out.printf("  value-1 still present: %b%n", weakMap.containsValue("value-1"));
        System.out.printf("  value-2 still present: %b (GC'd with its key)%n", weakMap.containsValue("value-2"));

        // Demo với String literal — KHÔNG hoạt động như expected
        System.out.println("\n  [WARNING: WeakHashMap + String literals does NOT work as cache]");
        WeakHashMap<String, String> stringMap = new WeakHashMap<>();
        String literal = "hello";        // interned in String pool = always strong ref!
        stringMap.put(literal, "world");
        literal = null;                  // reference removed, but "hello" still in pool!
        System.gc();
        Thread.sleep(100);
        System.out.printf("  String literal entry still alive: %b (String pool keeps it!)%n",
            stringMap.containsValue("world"));
        System.out.println("  → Dùng 'new String(\"hello\")' để tạo non-interned key nếu cần WeakHashMap");
    }

    // =========================================================================
    // DEMO 7 — SoftReference Cache: Memory-sensitive image/result cache
    // =========================================================================

    static void demo7_softReferenceCache() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — SoftReference Cache: Memory-Sensitive Caching");
        System.out.println("─".repeat(70));

        System.out.println("""

            SoftReference Cache Pattern:
              • Cache lớn → dùng SoftReference<V> làm value
              • JVM tự động clear khi heap low → không bao giờ OOM vì cache
              • Cache hit: softRef.get() != null → reuse
              • Cache miss: softRef.get() == null → reload từ nguồn
            """);

        // Simple SoftReference cache implementation
        Map<String, SoftReference<byte[]>> softCache = new HashMap<>();

        // Populate cache
        System.out.println("  [Populating SoftReference cache with 10 entries (10KB each)]");
        for (int i = 0; i < 10; i++) {
            softCache.put("image-" + i, new SoftReference<>(new byte[10_240]));
        }
        System.out.printf("  Cache size: %d entries%n", softCache.size());

        // Access pattern
        System.out.println("\n  [Accessing cache entries]");
        for (int i = 0; i < 5; i++) {
            String key = "image-" + i;
            SoftReference<byte[]> ref = softCache.get(key);
            byte[] data = (ref != null) ? ref.get() : null;

            if (data != null) {
                System.out.printf("    Cache HIT:  %s (%.1f KB)%n", key, data.length / 1024.0);
            } else {
                // Reload từ source
                byte[] reloaded = new byte[10_240]; // simulate reload
                softCache.put(key, new SoftReference<>(reloaded));
                System.out.printf("    Cache MISS: %s — reloaded from source%n", key);
            }
        }

        // Cleanup expired entries
        System.out.println("\n  [Cleaning up expired SoftReference entries]");
        System.gc();
        int before = softCache.size();
        softCache.entrySet().removeIf(e -> e.getValue().get() == null);
        int after = softCache.size();
        System.out.printf("  Before cleanup: %d, After cleanup: %d (removed %d stale entries)%n",
            before, after, before - after);

        System.out.println("""

            SA PRODUCTION RECOMMENDATION:
              Dùng Caffeine cache thay SoftReference manual:
                Cache<String, Image> cache = Caffeine.newBuilder()
                    .softValues()           // automatic soft reference for values
                    .maximumSize(10_000)    // hard cap
                    .expireAfterAccess(30, MINUTES)
                    .recordStats()          // hit rate metrics
                    .build();
            """);
    }

    // =========================================================================
    // DEMO 8 — PhantomReference: Pre-mortem cleanup (Java NIO pattern)
    // =========================================================================

    static void demo8_phantomReference() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8 — PhantomReference: Resource Cleanup Notification");
        System.out.println("─".repeat(70));

        System.out.println("""

            PhantomReference Use Case:
              • Java NIO DirectByteBuffer: off-heap memory cleanup
              • Cleaner API (Java 9+): replacement for finalize()
              • DB connection pool: detect leaked connections

            Java 9+ Cleaner API (preferred over PhantomReference directly):
            """);

        // Java 9+ Cleaner (preferred PhantomReference alternative)
        java.lang.ref.Cleaner cleaner = java.lang.ref.Cleaner.create();

        class NativeResource implements AutoCloseable {
            private final String resourceId;
            private final java.lang.ref.Cleaner.Cleanable cleanable;

            // Cleaner action: static to avoid capturing NativeResource reference
            static class CleanAction implements Runnable {
                private final String resourceId;
                CleanAction(String id) { this.resourceId = id; }

                @Override
                public void run() {
                    // Called when NativeResource is GC'd (if not explicitly closed)
                    System.out.printf("    [Cleaner] Auto-cleaning resource: %s%n", resourceId);
                }
            }

            NativeResource(String id) {
                this.resourceId = id;
                // Register cleanup action — action is static, no reference to NativeResource
                this.cleanable = cleaner.register(this, new CleanAction(id));
                System.out.printf("    NativeResource '%s' created%n", id);
            }

            @Override
            public void close() {
                cleanable.clean();  // explicit cleanup
                System.out.printf("    NativeResource '%s' explicitly closed%n", resourceId);
            }
        }

        // Case 1: Explicit close (best practice)
        System.out.println("  Case 1: Explicit close via try-with-resources (best)");
        try (NativeResource r = new NativeResource("resource-A")) {
            System.out.println("    Using resource-A...");
        } // close() called here

        // Case 2: Forgot to close → Cleaner handles it
        System.out.println("\n  Case 2: Forgot to close → Cleaner acts as safety net");
        NativeResource leaked = new NativeResource("resource-B");
        System.out.println("    'Forgetting' to close resource-B...");
        leaked = null; // lose reference → will be GC'd → Cleaner fires

        System.gc();
        Thread.sleep(200); // give Cleaner thread time to run

        System.out.println("""

            FINALIZE() vs CLEANER:
              finalize()  — deprecated (Java 9+), unpredictable timing, performance risk
              Cleaner     — Java 9+, dedicated thread, predictable, NO strong ref issue
              AutoCloseable — ALWAYS preferred: deterministic, composable with try-with-resources
            """);
    }

    // =========================================================================
    // DEMO 9 — Leak Detection Techniques
    // =========================================================================

    static void demo9_leakDetectionTechniques() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 9 — Memory Leak Detection Techniques");
        System.out.println("─".repeat(70));

        System.out.println("""

            TECHNIQUE 1: Heap Usage Trend Monitoring
              Bình thường: heap sau GC ổn định hoặc giảm dần
              Memory leak: heap floor tăng dần sau mỗi GC cycle
            """);

        // Trend monitoring simulation
        Runtime rt = Runtime.getRuntime();
        List<Long> postGCUsage = new ArrayList<>();
        List<Object> leakAccumulator = new ArrayList<>(); // simulates growing retention

        System.out.println("  [Heap after GC (simulating growing retention)]");
        System.out.println("  Round | Heap After GC | Delta   | Diagnosis");
        System.out.println("  " + "─".repeat(50));

        for (int round = 0; round < 6; round++) {
            // Each round: allocate + "leak" some objects
            leakAccumulator.add(new byte[50 * 1024]); // 50KB "leaked" per round

            System.gc();
            Thread.sleep(50);

            long used = rt.totalMemory() - rt.freeMemory();
            postGCUsage.add(used);

            String delta = round == 0 ? "   —   " :
                String.format("%+.1f KB", (used - postGCUsage.get(round - 1)) / 1024.0);
            String diagnosis = round == 0 ? "baseline" :
                (used > postGCUsage.get(round - 1)) ? "⚠ growing" : "OK";

            System.out.printf("  %5d | %13.1f MB | %7s | %s%n",
                round + 1, used / 1e6, delta, diagnosis);
        }

        boolean isLeaking = postGCUsage.get(postGCUsage.size() - 1) > postGCUsage.get(0);
        System.out.printf("%n  Verdict: %s%n",
            isLeaking ? "⚠ LEAK SUSPECTED — heap floor increasing after GC" : "OK — stable");

        System.out.println("""

            TECHNIQUE 2: Object Histogram (jmap -histo)
              jmap -histo:live <pid> | head -30

              Output sample:
               num     #instances         #bytes  class name
               ---     ----------         ------  ----------
                 1:         52,432      4,194,976  [B (byte arrays)
                 2:         31,000      2,480,000  java.util.HashMap$Node
                 3:         12,500        600,000  java.lang.String
                 4:          8,000        384,000  com.example.UserSession  ← suspicious!

              → UserSession count이 계속 증가 = session leak!
              → Snapshot 5분 간격으로 비교: 증가하는 class = leak candidate

            TECHNIQUE 3: Heap Dump + MAT Analysis
              # Capture heap dump (live objects only)
              jmap -dump:live,format=b,file=/tmp/heap.hprof <pid>

              # Or enable automatic dump on OOM
              java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof

              MAT (Eclipse Memory Analyzer):
                • Dominator Tree: xem object nào giữ nhiều memory nhất
                • Leak Suspects Report: MAT tự phát hiện potential leak
                • OQL Query: SELECT * FROM java.util.HashMap WHERE size > 10000

            TECHNIQUE 4: GC Notification Monitoring (xem Demo 3 bài 5.2)
              Micrometer metric: jvm_memory_used_bytes{area="heap"}
              Alert rule:       heap_after_gc / heap_max > 0.8 → fire PagerDuty

            TECHNIQUE 5: Allocation Profiling với async-profiler
              ./profiler.sh -e alloc -d 30 -f alloc.html <pid>
              → Flame graph hiện TOP allocators → tìm ai đang create quá nhiều object
            """);
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 5.3 — Memory Leak Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            TOP 5 MEMORY LEAK PATTERNS & FIX:
            ┌────────────────────────────────────┬──────────────────────────────────┐
            │ Pattern (Cause)                    │ Fix                              │
            ├────────────────────────────────────┼──────────────────────────────────┤
            │ Static collection grows forever    │ Bounded cache (Caffeine/LRU Map) │
            │ Listener not deregistered          │ WeakReference listener / dispose │
            │ ThreadLocal not removed            │ try { set } finally { remove() } │
            │ Non-static inner class / lambda    │ Static class / capture primitives│
            │ Unclosed resource in pool          │ try-with-resources / Cleaner     │
            └────────────────────────────────────┴──────────────────────────────────┘

            REFERENCE TYPE SELECTION:
            ┌────────────────┬──────────────────────────────────────────────────┐
            │ Use Case       │ Reference Type                                    │
            ├────────────────┼──────────────────────────────────────────────────┤
            │ Normal objects │ Strong (default)                                  │
            │ Large cache    │ SoftReference (yield under memory pressure)       │
            │ Supplementary  │ WeakReference / WeakHashMap (auto-expire with key)│
            │ Cleanup hook   │ Cleaner API (Java 9+) — safer than PhantomRef     │
            └────────────────┴──────────────────────────────────────────────────┘

            PRODUCTION MEMORY LEAK INVESTIGATION WORKFLOW:
              1. Alert: heap_after_gc / heap_max > 80% (Prometheus alert)
              2. Snapshot histogram:  jmap -histo:live <pid> > snap1.txt
              3. Wait 10 minutes, snapshot again: jmap -histo:live <pid> > snap2.txt
              4. diff snap1.txt snap2.txt → tìm class count tăng nhanh
              5. Heap dump: jmap -dump:live,format=b,file=heap.hprof <pid>
              6. Analyze với MAT: Dominator Tree + Leak Suspects Report
              7. Fix code → deploy → verify heap floor ổn định

            SA RULES:
              ✓ Heap dump ở production: chỉ làm khi OOM hoặc leak confirmed (STW)
              ✓ -XX:+HeapDumpOnOutOfMemoryError = must-have cho EVERY production JVM
              ✓ Micrometer/Prometheus: monitor jvm_memory_used_bytes trend liên tục
              ✓ Code review checklist: static Map? listener deregister? ThreadLocal.remove()?
              ✓ Load test + heap monitoring trước khi release = leak detection in CI

            KEY INSIGHT:
              "Memory leak trong Java không phải lỗi kỹ thuật mà là lỗi design:
               object được giữ sống lâu hơn lifecycle của nó.
               Fix ở design level, không phải JVM tuning level."
            """);
    }
}
