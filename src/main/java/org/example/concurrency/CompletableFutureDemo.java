package org.example.concurrency;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ============================================================
 * BÀI 2.5 — CompletableFuture & Async Programming
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Vấn đề của Future (bài 2.4) — tại sao cần CompletableFuture
 *   2. Tạo async task: supplyAsync / runAsync
 *   3. Chaining pipeline: thenApply → thenCompose → thenCombine
 *   4. Error handling: exceptionally / handle / whenComplete
 *   5. Fan-out / Fan-in: allOf / anyOf (hedged request pattern)
 *   6. Pitfalls: blocking trong async chain, thread pool chọn sai
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.concurrency.CompletableFutureDemo"
 * ============================================================
 */
public class CompletableFutureDemo {

    // Pool riêng cho IO tasks — KHÔNG dùng ForkJoinPool.commonPool() cho I/O
    static final ExecutorService IO_POOL = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 4, r -> {
            Thread t = new Thread(r, "io-worker-" + System.nanoTime() % 1000);
            t.setDaemon(true);
            return t;
        }
    );

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 2.5: CompletableFuture & Async Programming ===\n");

        demo1_FutureLimitations();
        demo2_SupplyAsyncBasics();
        demo3_ChainingPipeline();
        demo4_ErrorHandling();
        demo5_FanOutFanIn();
        demo6_RealWorldPattern();

        IO_POOL.shutdown();
        System.out.println("\n=== KẾT THÚC BÀI 2.5 ===");
    }

    // ================================================================
    // DEMO 1: Vấn đề của Future — tại sao cần CompletableFuture
    // ================================================================

    /**
     * Future (Java 5) có 3 vấn đề lớn:
     *
     *   1. BLOCKING: future.get() block thread hiện tại → lãng phí
     *      Nếu chuỗi A → B → C, phải block sau mỗi bước
     *
     *   2. KHÔNG CHAIN ĐƯỢC: Không thể nói "khi A xong thì chạy B"
     *      mà không block. Phải viết code synchronous.
     *
     *   3. KHÔNG XỬ LÝ LỖI ĐẸP: Exception bị bọc trong ExecutionException,
     *      không có cơ chế fallback tích hợp.
     *
     * CompletableFuture (Java 8) giải quyết cả 3:
     *   → Non-blocking callback chain (then*)
     *   → Composable (thenCompose)
     *   → Built-in error handling (exceptionally, handle)
     *
     * SA INSIGHT: CompletableFuture là nền tảng của async trong Java.
     *   Spring WebFlux, Reactive Streams đều xây trên ý tưởng này.
     *   Hiểu CF sâu = hiểu được 80% reactive programming.
     */
    static void demo1_FutureLimitations() throws Exception {
        System.out.println("--- DEMO 1: Vấn đề của Future ---");

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // Cách cũ với Future: phải block từng bước
        long start = System.currentTimeMillis();

        Future<String> stepA = pool.submit(() -> { Thread.sleep(100); return "data-from-A"; });
        String resultA = stepA.get();                              // BLOCK 100ms

        Future<String> stepB = pool.submit(() -> { Thread.sleep(100); return resultA + "-processed-by-B"; });
        String resultB = stepB.get();                              // BLOCK thêm 100ms

        Future<String> stepC = pool.submit(() -> { Thread.sleep(100); return resultB + "-saved-by-C"; });
        String resultC = stepC.get();                              // BLOCK thêm 100ms

        System.out.println("  Future (3 bước tuần tự, block từng bước): "
                + (System.currentTimeMillis() - start) + "ms");
        System.out.println("  Kết quả: " + resultC);

        // Với CompletableFuture: chain không block, cùng thời gian chờ nhưng thread không bị giữ
        start = System.currentTimeMillis();
        String cfResult = CompletableFuture
            .supplyAsync(() -> { sleep(100); return "data-from-A"; }, pool)
            .thenApplyAsync(a -> { sleep(100); return a + "-processed-by-B"; }, pool)
            .thenApplyAsync(b -> { sleep(100); return b + "-saved-by-C"; }, pool)
            .get(); // Chỉ block 1 lần ở cuối, các bước trung gian không giữ thread

        System.out.println("  CompletableFuture (chain không block): "
                + (System.currentTimeMillis() - start) + "ms");
        System.out.println("  Kết quả: " + cfResult);

        pool.shutdown();
        System.out.println("  → Thời gian tương đương, nhưng CF không giữ thread chờ từng bước\n");
    }

    // ================================================================
    // DEMO 2: supplyAsync / runAsync — Tạo async task
    // ================================================================

    /**
     * supplyAsync(Supplier, executor) — task có return value
     * runAsync(Runnable, executor)    — task không return (fire-and-forget)
     *
     * QUAN TRỌNG: Luôn truyền executor riêng!
     * Mặc định CompletableFuture dùng ForkJoinPool.commonPool().
     * commonPool được thiết kế cho CPU-bound tasks (thread = nCPU - 1).
     * Dùng nó cho I/O blocking → starve toàn bộ parallel stream của ứng dụng!
     *
     * completedFuture(value)  — tạo CF đã hoàn thành sẵn (hữu ích để test/mock)
     * failedFuture(exception) — tạo CF đã fail sẵn (Java 9+)
     */
    static void demo2_SupplyAsyncBasics() throws Exception {
        System.out.println("--- DEMO 2: supplyAsync / runAsync ---");

        // supplyAsync — có kết quả
        CompletableFuture<String> cf = CompletableFuture.supplyAsync(
            () -> { sleep(100); return "Hello from async"; },
            IO_POOL  // ← LUÔN chỉ định pool!
        );

        System.out.println("  Async task đang chạy, main thread tiếp tục...");
        System.out.println("  Đang làm việc khác...");
        System.out.println("  Kết quả: " + cf.get(2, TimeUnit.SECONDS));

        // runAsync — fire and forget
        CompletableFuture<Void> fireAndForget = CompletableFuture.runAsync(
            () -> { sleep(50); System.out.println("  runAsync: audit log ghi xong"); },
            IO_POOL
        );

        // completedFuture — hữu ích trong test hoặc khi data đã có sẵn
        CompletableFuture<String> cached = CompletableFuture.completedFuture("cached-value");
        System.out.println("  completedFuture (no async): " + cached.get());

        fireAndForget.get();

        // isDone / getNow / join
        CompletableFuture<Integer> cf2 = CompletableFuture.supplyAsync(() -> { sleep(200); return 42; }, IO_POOL);
        System.out.println("  isDone (trước khi xong): " + cf2.isDone());
        System.out.println("  getNow (fallback nếu chưa xong): " + cf2.getNow(-1)); // Trả -1 nếu chưa xong
        System.out.println("  join() (tương tự get() nhưng throws unchecked): " + cf2.join());
        System.out.println();
    }

    // ================================================================
    // DEMO 3: Chaining Pipeline — thenApply / thenCompose / thenCombine
    // ================================================================

    /**
     * 3 operator chính để compose async tasks:
     *
     *   thenApply(fn)      — transform kết quả (như map trong Stream)
     *                        fn: T → R   (synchronous transform)
     *
     *   thenApplyAsync(fn) — transform trên thread pool (không block thread gọi)
     *
     *   thenCompose(fn)    — flat-map: kết nối 2 async tasks phụ thuộc nhau
     *                        fn: T → CompletableFuture<R>
     *                        (nếu dùng thenApply sẽ cho CF<CF<R>> — sai!)
     *
     *   thenCombine(cf, fn) — kết hợp 2 CF độc lập chạy song song
     *                        fn: (T, U) → R
     *
     *   thenAccept(fn)     — consume kết quả, không return (cuối pipeline)
     *   thenRun(fn)        — chạy Runnable sau khi xong, không cần kết quả
     *
     * RULE: thenApply  = transform 1 giá trị (sync)
     *       thenCompose = kết nối 2 async step phụ thuộc
     *       thenCombine = gộp 2 async step độc lập
     */
    static void demo3_ChainingPipeline() throws Exception {
        System.out.println("--- DEMO 3: Chaining Pipeline ---");

        // thenApply — transform tuần tự (mỗi bước đợi bước trước)
        String result = CompletableFuture
            .supplyAsync(() -> "  hello world", IO_POOL)
            .thenApply(String::trim)
            .thenApply(String::toUpperCase)
            .thenApply(s -> s + "!")
            .get();
        System.out.println("  thenApply chain: " + result);

        // thenCompose — 2 async task phụ thuộc (kết quả A là input của B)
        long start = System.currentTimeMillis();
        String user = CompletableFuture
            .supplyAsync(() -> fetchUserId("alice"), IO_POOL)     // Bước 1: lấy userId
            .thenCompose(id -> fetchUserProfile(id))              // Bước 2: dùng id để lấy profile
            .get();
        System.out.println("  thenCompose (A→B phụ thuộc): " + user
                + " (" + (System.currentTimeMillis() - start) + "ms)");

        // thenCombine — 2 async task ĐỘC LẬP chạy song song, gộp kết quả
        start = System.currentTimeMillis();
        String combined = CompletableFuture
            .supplyAsync(() -> fetchPrice("BTC"), IO_POOL)        // Chạy song song
            .thenCombine(
                CompletableFuture.supplyAsync(() -> fetchRate("USD/VND"), IO_POOL), // Chạy song song
                (price, rate) -> "BTC=" + price + " USD → " + (price * rate) + " VND"
            )
            .get();
        System.out.println("  thenCombine (A || B → merge): " + combined
                + " (" + (System.currentTimeMillis() - start) + "ms, chạy song song)");

        // thenAccept — consume kết quả cuối, không return
        CompletableFuture
            .supplyAsync(() -> computeSum(1, 100), IO_POOL)
            .thenApply(sum -> "Tổng 1→100 = " + sum)
            .thenAccept(System.out::println)   // In ra, không return
            .get();

        System.out.println();
    }

    // Giả lập các external calls
    static String fetchUserId(String name) { sleep(80); return "uid-" + name.hashCode(); }
    static CompletableFuture<String> fetchUserProfile(String uid) {
        return CompletableFuture.supplyAsync(() -> { sleep(80); return "Profile[" + uid + "]"; }, IO_POOL);
    }
    static int fetchPrice(String symbol) { sleep(150); return 65000; }
    static double fetchRate(String pair)  { sleep(120); return 25300.0; }
    static int computeSum(int from, int to) {
        return java.util.stream.IntStream.rangeClosed(from, to).sum();
    }

    // ================================================================
    // DEMO 4: Error Handling — exceptionally / handle / whenComplete
    // ================================================================

    /**
     * Async error handling — 3 operator:
     *
     *   exceptionally(fn)    — chỉ chạy khi có exception, như catch block
     *                          fn: Throwable → T (return fallback value)
     *                          Nếu không có exception → bỏ qua fn
     *
     *   handle(fn)           — luôn chạy, dù success hay fail
     *                          fn: (T result, Throwable ex) → R
     *                          (như finally nhưng có thể transform)
     *
     *   whenComplete(fn)     — luôn chạy, như handle nhưng KHÔNG transform
     *                          fn: (T result, Throwable ex) → void
     *                          (dùng cho side effects: logging, metrics)
     *
     * SA INSIGHT: Trong microservices, mọi external call CÓ THỂ fail.
     *   exceptionally = circuit breaker đơn giản: trả về cached/default value
     *   handle = nơi tập trung xử lý lỗi + transform
     *   whenComplete = logging, tracing, metrics — không ảnh hưởng kết quả
     */
    static void demo4_ErrorHandling() throws Exception {
        System.out.println("--- DEMO 4: Error Handling ---");

        // exceptionally — fallback khi fail
        String result1 = CompletableFuture
            .supplyAsync(() -> callExternalService(true), IO_POOL) // Luôn throw
            .exceptionally(ex -> {
                System.out.println("  exceptionally: caught " + ex.getCause().getMessage()
                        + " → returning fallback");
                return "fallback-value";
            })
            .get();
        System.out.println("  exceptionally result: " + result1);

        // handle — xử lý cả 2 case (success + fail)
        String result2 = CompletableFuture
            .supplyAsync(() -> callExternalService(false), IO_POOL) // Không throw
            .handle((value, ex) -> {
                if (ex != null) {
                    System.out.println("  handle: caught " + ex.getMessage());
                    return "handle-fallback";
                }
                return value.toUpperCase(); // Transform kết quả thành công
            })
            .get();
        System.out.println("  handle (success case): " + result2);

        String result3 = CompletableFuture
            .supplyAsync(() -> callExternalService(true), IO_POOL) // Throw
            .handle((value, ex) -> {
                if (ex != null) return "handle-fallback-on-error";
                return value;
            })
            .get();
        System.out.println("  handle (error case):   " + result3);

        // whenComplete — side effect không ảnh hưởng kết quả
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount   = new AtomicInteger(0);

        List<CompletableFuture<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean shouldFail = i % 2 == 0;
            tasks.add(CompletableFuture
                .supplyAsync(() -> callExternalService(shouldFail), IO_POOL)
                .whenComplete((v, ex) -> {   // Logging/metrics — không ảnh hưởng chain
                    if (ex != null) errorCount.incrementAndGet();
                    else            successCount.incrementAndGet();
                })
                .exceptionally(ex -> "fallback")
            );
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
        System.out.println("  whenComplete metrics — success: " + successCount
                + ", errors: " + errorCount + "\n");
    }

    static String callExternalService(boolean fail) {
        sleep(50);
        if (fail) throw new RuntimeException("Service unavailable");
        return "service-response";
    }

    // ================================================================
    // DEMO 5: allOf / anyOf — Fan-out / Fan-in Pattern
    // ================================================================

    /**
     * allOf(cf1, cf2, ...) — chờ TẤT CẢ hoàn thành (fan-out → fan-in)
     *   Dùng khi: cần kết quả của tất cả tasks trước khi tiếp tục
     *   Nếu 1 task fail → allOf fail ngay (fail-fast)
     *
     * anyOf(cf1, cf2, ...) — lấy kết quả của task NÀO VỀ TRƯỚC
     *   Dùng khi: hedged request (gửi đến nhiều replica, lấy cái nhanh nhất)
     *   Khi 1 task xong → anyOf xong ngay, task còn lại vẫn chạy (không cancel)
     *
     * HEDGED REQUEST pattern (Google SRE Book):
     *   Gửi cùng 1 request đến 2-3 backend khác nhau,
     *   lấy response đầu tiên về → giảm tail latency (P99).
     *   Trade-off: tốn thêm tài nguyên backend.
     */
    static void demo5_FanOutFanIn() throws Exception {
        System.out.println("--- DEMO 5: allOf / anyOf — Fan-out Fan-in ---");

        // allOf — gọi 4 service song song, đợi tất cả
        long start = System.currentTimeMillis();
        CompletableFuture<String> userCF    = CompletableFuture.supplyAsync(() -> { sleep(100); return "Alice"; }, IO_POOL);
        CompletableFuture<Integer> orderCF  = CompletableFuture.supplyAsync(() -> { sleep(150); return 42; }, IO_POOL);
        CompletableFuture<Double> balanceCF = CompletableFuture.supplyAsync(() -> { sleep(80);  return 1250.50; }, IO_POOL);
        CompletableFuture<String> prefCF    = CompletableFuture.supplyAsync(() -> { sleep(120); return "VIP"; }, IO_POOL);

        CompletableFuture.allOf(userCF, orderCF, balanceCF, prefCF).get(); // Chờ cả 4

        System.out.println("  allOf (4 service song song, chậm nhất=150ms): "
                + (System.currentTimeMillis() - start) + "ms");
        System.out.printf("  Dashboard: user=%s, orders=%d, balance=%.2f, tier=%s%n",
                userCF.get(), orderCF.get(), balanceCF.get(), prefCF.get());

        // allOf với collect kết quả — pattern thực tế
        List<String> productIds = List.of("prod-1", "prod-2", "prod-3", "prod-4", "prod-5");
        start = System.currentTimeMillis();

        List<CompletableFuture<String>> priceTasks = productIds.stream()
            .map(id -> CompletableFuture.supplyAsync(
                () -> { sleep(100); return id + ":$" + (id.hashCode() % 100 + 100); },
                IO_POOL))
            .collect(Collectors.toList());

        // Gộp tất cả kết quả sau khi xong
        List<String> prices = CompletableFuture
            .allOf(priceTasks.toArray(new CompletableFuture[0]))
            .thenApply(v -> priceTasks.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()))
            .get();

        System.out.println("  allOf collect " + prices.size() + " giá song song: "
                + (System.currentTimeMillis() - start) + "ms (thay vì " + (100 * prices.size()) + "ms tuần tự)");
        System.out.println("  Giá: " + prices);

        // anyOf — Hedged Request: gửi đến 3 replica, lấy cái nhanh nhất
        start = System.currentTimeMillis();
        Object fastest = CompletableFuture.anyOf(
            CompletableFuture.supplyAsync(() -> { sleep(300); return "replica-1 (slow)"; }, IO_POOL),
            CompletableFuture.supplyAsync(() -> { sleep(80);  return "replica-2 (fast)"; }, IO_POOL),
            CompletableFuture.supplyAsync(() -> { sleep(200); return "replica-3 (medium)"; }, IO_POOL)
        ).get();
        System.out.println("  anyOf (hedged request): '" + fastest
                + "' về trước (" + (System.currentTimeMillis() - start) + "ms)\n");
    }

    // ================================================================
    // DEMO 6: Real-world Pattern — Order Processing Pipeline
    // ================================================================

    /**
     * Mô phỏng pipeline xử lý đơn hàng trong e-commerce:
     *
     *   1. Validate order (async)
     *      ├─ 2a. Check inventory (async, song song)
     *      └─ 2b. Check user credit (async, song song)
     *   3. Gộp kết quả → charge payment (async)
     *   4. Notify user + Update inventory (async, song song)
     *   5. Return order confirmation
     *
     * Toàn bộ pipeline non-blocking — thread không bị giữ chờ I/O.
     * Lỗi ở bất kỳ bước nào → propagate xuống, exceptionally xử lý.
     */
    static void demo6_RealWorldPattern() throws Exception {
        System.out.println("--- DEMO 6: Real-world Order Processing Pipeline ---");

        long start = System.currentTimeMillis();

        String confirmation = CompletableFuture
            // Bước 1: Validate order
            .supplyAsync(() -> validateOrder("order-789"), IO_POOL)

            // Bước 2: Parallel check inventory + credit
            .thenCompose(order -> {
                CompletableFuture<Boolean> inventoryCheck =
                    CompletableFuture.supplyAsync(() -> checkInventory(order), IO_POOL);
                CompletableFuture<Boolean> creditCheck =
                    CompletableFuture.supplyAsync(() -> checkCredit(order), IO_POOL);

                return inventoryCheck.thenCombine(creditCheck,
                    (hasStock, hasCredit) -> {
                        if (!hasStock) throw new RuntimeException("Out of stock");
                        if (!hasCredit) throw new RuntimeException("Insufficient credit");
                        return order;
                    });
            })

            // Bước 3: Charge payment
            .thenComposeAsync(order ->
                CompletableFuture.supplyAsync(() -> chargePayment(order), IO_POOL),
                IO_POOL)

            // Bước 4: Parallel notify + update inventory
            .thenCompose(paymentRef -> {
                CompletableFuture<Void> notify =
                    CompletableFuture.runAsync(() -> notifyUser("order-789", paymentRef), IO_POOL);
                CompletableFuture<Void> updateStock =
                    CompletableFuture.runAsync(() -> updateInventory("order-789"), IO_POOL);

                return CompletableFuture.allOf(notify, updateStock)
                    .thenApply(v -> "ORDER-CONFIRMED:" + paymentRef);
            })

            // Error handling — bất kỳ bước nào fail đều được bắt ở đây
            .exceptionally(ex -> "ORDER-FAILED:" + ex.getCause().getMessage())

            // Logging — không ảnh hưởng kết quả
            .whenComplete((result, ex) ->
                System.out.println("  [Audit] Pipeline ended: " + result))

            .get(5, TimeUnit.SECONDS);

        System.out.println("  Kết quả: " + confirmation);
        System.out.println("  Thời gian (pipeline song song): " + (System.currentTimeMillis() - start) + "ms");
        System.out.println("  Ước tính tuần tự: ~" + (60+80+70+90+50+60) + "ms\n");

        System.out.println("=== TỔNG KẾT BÀI 2.5 ===");
        System.out.println("  ✓ supplyAsync/runAsync: luôn truyền executor pool riêng");
        System.out.println("  ✓ thenApply  = transform sync   | thenCompose = flat-map async");
        System.out.println("  ✓ thenCombine= merge 2 CF song song (độc lập)");
        System.out.println("  ✓ exceptionally=fallback | handle=transform | whenComplete=side-effect");
        System.out.println("  ✓ allOf=đợi tất cả | anyOf=lấy cái nhanh nhất (hedged request)");
        System.out.println("  → Bài tiếp: 2.6 VirtualThreadDemo — Project Loom, Java 21");
    }

    // Giả lập các bước của pipeline
    static String validateOrder(String id)       { sleep(60);  return id; }
    static boolean checkInventory(String order)  { sleep(80);  return true; }
    static boolean checkCredit(String order)     { sleep(70);  return true; }
    static String chargePayment(String order)    { sleep(90);  return "PAY-" + order.hashCode(); }
    static void notifyUser(String order, String ref) { sleep(50); }
    static void updateInventory(String order)    { sleep(60); }

    // ================================================================
    // HELPER
    // ================================================================
    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
