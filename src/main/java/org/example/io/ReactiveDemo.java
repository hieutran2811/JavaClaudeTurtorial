package org.example.io;

import org.reactivestreams.Subscription;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;
import reactor.util.retry.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * =============================================================================
 * BÀI 6.4 — Reactive Programming: Project Reactor (Mono / Flux)
 * =============================================================================
 *
 * REACTIVE STREAMS là giao thức bất đồng bộ 4 interface:
 *   Publisher<T>    — phát ra 0..N phần tử
 *   Subscriber<T>   — nhận phần tử
 *   Subscription    — link giữa Publisher & Subscriber (dùng để request & cancel)
 *   Processor<T,R>  — vừa là Publisher vừa là Subscriber
 *
 * PROJECT REACTOR implementation:
 *   Mono<T>   — 0 hoặc 1 phần tử  (Future/Optional tương đương)
 *   Flux<T>   — 0..N phần tử       (Stream/Iterator tương đương nhưng async)
 *
 * REACTIVE vs IMPERATIVE:
 *   Imperative:  result = callA(); result2 = callB(result); return result2;
 *   Reactive:    Mono.fromCallable(this::callA).flatMap(this::callB)
 *
 * TẠI SAO REACTIVE?
 *   • Non-blocking: thread không idle chờ I/O → ít thread, nhiều throughput
 *   • Backpressure: subscriber kiểm soát tốc độ nhận → không OOM
 *   • Composable: chain operators thay vì callback hell
 *   • Spring WebFlux, R2DBC, Kafka Reactor đều dùng Reactor
 *
 * KHI NÀO KHÔNG DÙNG REACTIVE?
 *   • CRUD đơn giản → Spring MVC + Virtual Threads là đủ
 *   • Team chưa quen → learning curve cao, debug khó
 *   • CPU-bound work → reactive không có lợi ở đây
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.io.ReactiveDemo"
 */
public class ReactiveDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 6.4 — Reactive: Project Reactor (Mono / Flux)");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_monoBasics();
        demo2_fluxBasics();
        demo3_operators();
        demo4_flatMapConcurrency();
        demo5_errorHandling();
        demo6_backpressure();
        demo7_hotVsCold();
        demo8_schedulers();
        demo9_realWorldPipeline();
        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Mono: 0 or 1 element
    // =========================================================================
    static void demo1_monoBasics() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Mono<T>: 0 hoặc 1 phần tử");
        System.out.println("─".repeat(70));

        System.out.println("""

            Mono = async Optional = Future with operators.
            Không subscribe → không chạy (lazy by default = COLD).
            """);

        // Mono creation
        Mono<String> just     = Mono.just("Hello Reactor");
        Mono<String> empty    = Mono.empty();
        Mono<String> error    = Mono.error(new RuntimeException("Oops"));
        Mono<String> deferred = Mono.fromCallable(() -> {
            Thread.sleep(10); // simulated blocking call (use subscribeOn for real I/O)
            return "Computed lazily";
        });

        System.out.println("  [Mono.just — immediate value]");
        just.subscribe(
            val -> System.out.println("    onNext: " + val),
            err -> System.err.println("    onError: " + err),
            ()  -> System.out.println("    onComplete")
        );

        System.out.println("\n  [Mono.empty — no value, just completion]");
        empty.subscribe(
            val -> System.out.println("    onNext: " + val),   // never called
            err -> System.err.println("    onError: " + err),
            ()  -> System.out.println("    onComplete (no value emitted)")
        );

        System.out.println("\n  [Mono.error — terminates with error]");
        error.subscribe(
            val -> System.out.println("    onNext: " + val),    // never called
            err -> System.out.println("    onError: " + err.getMessage()),
            ()  -> System.out.println("    onComplete")          // never called
        );

        System.out.println("\n  [Mono.fromCallable — lazy, executed on subscribe]");
        deferred.subscribe(val -> System.out.println("    Received: " + val));

        // Block: subscribe and wait (only in tests/main, NEVER in reactive chain)
        System.out.println("\n  [block() — convert to synchronous (use only at edges)]");
        String result = Mono.just("Blocking result")
            .map(String::toUpperCase)
            .block(Duration.ofSeconds(2));
        System.out.println("    block() returned: " + result);

        // Mono from CompletableFuture
        CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "from CompletableFuture");
        Mono<String> fromCF = Mono.fromFuture(cf);
        System.out.println("\n  [Mono.fromFuture]");
        fromCF.subscribe(val -> System.out.println("    " + val));

        Thread.sleep(100); // let async complete

        System.out.println("""
            Mono creation methods:
              Mono.just(T)              — constant value
              Mono.empty()              — completes with no value
              Mono.error(Throwable)     — terminates with error
              Mono.fromCallable(Callable) — lazy, blocking-safe with subscribeOn
              Mono.fromFuture(CF)       — bridge from CompletableFuture
              Mono.fromSupplier(Supplier) — lazy supplier
              Mono.defer(Mono supplier) — fresh Mono per subscribe (stateful)
              Mono.delay(Duration)      — emit 0L after delay
            """);
    }

    // =========================================================================
    // DEMO 2 — Flux: 0..N elements
    // =========================================================================
    static void demo2_fluxBasics() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 2 — Flux<T>: 0..N phần tử");
        System.out.println("─".repeat(70));

        System.out.println("""

            Flux = async Stream. Phát ra N phần tử rồi complete (hoặc error).
            """);

        // Flux creation
        System.out.println("  [Flux.just — fixed elements]");
        Flux.just("A", "B", "C", "D")
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {},
                       () -> System.out.println("← complete"));

        System.out.println("\n  [Flux.range — integer sequence]");
        Flux.range(1, 5)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {},
                       () -> System.out.println("← complete"));

        System.out.println("\n  [Flux.fromIterable — from collection]");
        List<String> cities = List.of("Hanoi", "HCM", "Da Nang", "Hue");
        Flux.fromIterable(cities)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {},
                       () -> System.out.println("← complete"));

        System.out.println("\n  [Flux.fromStream — from Java Stream (consumed once!)]");
        Flux.fromStream(IntStream.range(0, 5).mapToObj(i -> "item-" + i))
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {},
                       () -> System.out.println("← complete"));

        // Flux.generate — stateful, synchronous generation
        System.out.println("\n  [Flux.generate — stateful synchronous generator]");
        Flux.<Integer, Integer>generate(
                () -> 0,                                     // initial state
                (state, sink) -> {
                    sink.next(state * state);                // emit value
                    if (state == 4) sink.complete();         // signal end
                    return state + 1;                        // next state
                })
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {},
                       () -> System.out.println("← complete (squares)"));

        // Flux.create — imperative bridge (for event listeners, callbacks)
        System.out.println("\n  [Flux.create — bridge from imperative callbacks]");
        Flux<String> bridged = Flux.create(sink -> {
            // Imagine: eventBus.register(event -> sink.next(event));
            for (String event : List.of("event-1", "event-2", "event-3")) {
                sink.next(event);
            }
            sink.complete();
        });
        bridged.subscribe(v -> System.out.print("    " + v + " "),
                          e -> {},
                          () -> System.out.println("← complete"));

        // Flux.interval — time-based (careful: runs on parallel scheduler)
        System.out.println("\n  [Flux.interval — 50ms between items (take 4)]");
        CountDownLatch intervalLatch = new CountDownLatch(1);
        Flux.interval(Duration.ofMillis(50))
            .take(4)
            .subscribe(
                v  -> System.out.print("    tick-" + v + " "),
                e  -> {},
                () -> { System.out.println("← complete"); intervalLatch.countDown(); }
            );
        intervalLatch.await(2, TimeUnit.SECONDS);

        System.out.println("""
            Flux creation methods:
              Flux.just(T...)           — varargs
              Flux.range(start, count)  — int sequence
              Flux.fromIterable(iter)   — from Collection
              Flux.fromStream(stream)   — Java Stream (consumed once!)
              Flux.fromArray(T[])       — from array
              Flux.generate(init, fn)   — synchronous stateful generation
              Flux.create(consumer)     — async, bridge from imperative API
              Flux.push(consumer)       — single-threaded create variant
              Flux.interval(Duration)   — periodic ticks
              Flux.concat / merge / zip — combining sources
            """);
    }

    // =========================================================================
    // DEMO 3 — Operators: map, filter, flatMap, zip, reduce
    // =========================================================================
    static void demo3_operators() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 3 — Operators: Transform, Filter, Combine, Reduce");
        System.out.println("─".repeat(70));

        System.out.println();

        // map: sync transform 1→1
        System.out.println("  [map — synchronous 1:1 transform]");
        Flux.range(1, 5)
            .map(i -> i * i)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {}, () -> System.out.println("← complete"));

        // filter
        System.out.println("\n  [filter — keep matching elements]");
        Flux.range(1, 10)
            .filter(i -> i % 2 == 0)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {}, () -> System.out.println("← evens"));

        // take / skip / takeLast
        System.out.println("\n  [take, skip, takeLast]");
        Flux<Integer> src = Flux.range(1, 10);
        System.out.print("    take(3):     ");
        src.take(3).subscribe(v -> System.out.print(v + " "),  e -> {}, () -> System.out.println());
        System.out.print("    skip(7):     ");
        src.skip(7).subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());
        System.out.print("    takeLast(3): ");
        src.takeLast(3).subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());

        // distinct, sort, reverse
        System.out.println("  [distinct, sort]");
        Flux.just(3, 1, 4, 1, 5, 9, 2, 6, 5, 3)
            .distinct()
            .sort()
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {}, () -> System.out.println("← distinct+sorted"));

        // reduce: aggregate all → single value (like Stream.reduce)
        System.out.println("\n  [reduce — aggregate to single Mono]");
        Flux.range(1, 10)
            .reduce(0, Integer::sum)
            .subscribe(sum -> System.out.println("    sum(1..10) = " + sum));

        // scan: running accumulation (emit intermediate values)
        System.out.println("\n  [scan — running total (emit each step)]");
        Flux.range(1, 5)
            .scan(0, Integer::sum)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {}, () -> System.out.println("← running sum"));

        // zip: combine two Flux element-by-element
        System.out.println("\n  [zip — combine two Flux pairwise]");
        Flux<String> names  = Flux.just("Alice", "Bob", "Carol");
        Flux<Integer> scores = Flux.just(95, 87, 92);
        Flux.zip(names, scores, (name, score) -> name + "=" + score)
            .subscribe(v -> System.out.print("    " + v + " "),
                       e -> {}, () -> System.out.println("← zipped"));

        // merge: interleave two Flux (order not guaranteed)
        System.out.println("\n  [merge vs concat]");
        Flux<String> f1 = Flux.just("A", "B", "C");
        Flux<String> f2 = Flux.just("1", "2", "3");
        System.out.print("    concat (ordered): ");
        Flux.concat(f1, f2)
            .subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());
        System.out.print("    merge  (unordered): ");
        Flux.merge(f1, f2)
            .subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());

        // collectList, collectMap
        System.out.println("\n  [collectList, groupBy]");
        Flux.range(1, 6)
            .collectList()
            .subscribe(list -> System.out.println("    collectList: " + list));

        Flux.range(1, 6)
            .groupBy(i -> i % 2 == 0 ? "even" : "odd")
            .flatMap(group -> group.collectList()
                .map(list -> group.key() + ": " + list))
            .subscribe(v -> System.out.println("    groupBy: " + v));

        // buffer: batch elements
        System.out.println("\n  [buffer — batch elements]");
        Flux.range(1, 10)
            .buffer(3)   // groups of 3
            .subscribe(batch -> System.out.print("    " + batch + " "),
                       e -> {}, () -> System.out.println("← batched"));

        // window: similar to buffer but emits Flux windows
        System.out.println("\n  [windowUntil — split on condition]");
        Flux.just(1, 2, 3, 0, 4, 5, 0, 6)
            .windowUntil(i -> i == 0, true)  // split at 0 (inclusive)
            .flatMap(w -> w.collectList())
            .subscribe(win -> System.out.print("    " + win + " "),
                       e -> {}, () -> System.out.println("← windows"));
    }

    // =========================================================================
    // DEMO 4 — flatMap vs concatMap vs switchMap
    // =========================================================================
    static void demo4_flatMapConcurrency() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — flatMap vs concatMap vs switchMap");
        System.out.println("─".repeat(70));

        System.out.println("""

            flatMap:   map each element to a Mono/Flux, MERGE results (concurrent!)
            concatMap: map each element to a Mono/Flux, CONCAT results (sequential, ordered)
            switchMap: map each element to Mono/Flux, CANCEL previous on new emission

            Critical difference — flatMap IS CONCURRENT:
              Flux.range(1, 3).flatMap(i → callService(i))
              → calls callService(1), callService(2), callService(3) SIMULTANEOUSLY
              → results arrive in COMPLETION ORDER (not input order)

            concatMap IS SEQUENTIAL:
              → waits for callService(1) to complete before calling callService(2)
              → results in INPUT ORDER
            """);

        // Simulate async service call with variable delay
        java.util.function.Function<Integer, Mono<String>> asyncCall = id -> {
            int delayMs = (4 - id) * 50; // id=1 → 150ms, id=2 → 100ms, id=3 → 50ms
            return Mono.fromCallable(() -> "result-" + id)
                .delayElement(Duration.ofMillis(delayMs));
        };

        // concatMap — sequential, ordered
        System.out.println("  [concatMap — sequential (id=1→150ms, id=2→100ms, id=3→50ms)]");
        CountDownLatch concatLatch = new CountDownLatch(1);
        long concatStart = System.currentTimeMillis();
        Flux.range(1, 3)
            .concatMap(asyncCall)
            .subscribe(
                v  -> System.out.printf("    %dms — %s%n",
                    System.currentTimeMillis() - concatStart, v),
                e  -> {},
                () -> { System.out.printf("    Total: %dms (sequential, ordered)%n",
                    System.currentTimeMillis() - concatStart);
                    concatLatch.countDown(); }
            );
        concatLatch.await(3, TimeUnit.SECONDS);

        // flatMap — concurrent, arrival order
        System.out.println("\n  [flatMap — concurrent (same delays, arrives in completion order)]");
        CountDownLatch flatLatch = new CountDownLatch(1);
        long flatStart = System.currentTimeMillis();
        Flux.range(1, 3)
            .flatMap(asyncCall)
            .subscribe(
                v  -> System.out.printf("    %dms — %s%n",
                    System.currentTimeMillis() - flatStart, v),
                e  -> {},
                () -> { System.out.printf("    Total: %dms (concurrent, arrival order)%n",
                    System.currentTimeMillis() - flatStart);
                    flatLatch.countDown(); }
            );
        flatLatch.await(3, TimeUnit.SECONDS);

        // flatMap with concurrency limit
        System.out.println("\n  [flatMap(fn, concurrency=2) — limit parallel calls]");
        CountDownLatch limitLatch = new CountDownLatch(1);
        long limitStart = System.currentTimeMillis();
        Flux.range(1, 5)
            .flatMap(i -> Mono.fromCallable(() -> "result-" + i)
                .delayElement(Duration.ofMillis(50)), 2) // max 2 concurrent
            .subscribe(
                v  -> System.out.printf("    %dms — %s%n",
                    System.currentTimeMillis() - limitStart, v),
                e  -> {},
                () -> { System.out.printf("    Total: %dms (max 2 concurrent)%n",
                    System.currentTimeMillis() - limitStart);
                    limitLatch.countDown(); }
            );
        limitLatch.await(3, TimeUnit.SECONDS);

        // switchMap — cancel previous on new input (typeahead search pattern)
        System.out.println("\n  [switchMap — cancel previous (live search / debounce pattern)]");
        CountDownLatch switchLatch = new CountDownLatch(1);
        Flux.just("j", "ja", "jav", "java")  // rapid keystrokes
            .delayElements(Duration.ofMillis(30))
            .switchMap(query -> Mono.fromCallable(() -> "search(" + query + ")")
                .delayElement(Duration.ofMillis(80)))  // search takes 80ms
            .subscribe(
                v  -> System.out.println("    Search result: " + v),
                e  -> {},
                () -> { System.out.println("    (only last search completes)");
                    switchLatch.countDown(); }
            );
        switchLatch.await(3, TimeUnit.SECONDS);

        System.out.println("""
            SELECTION GUIDE:
              flatMap:   parallel API calls (e.g. enrich 100 items via REST → fast)
              concatMap: ordered processing (e.g. DB transactions in sequence)
              switchMap: latest-only (e.g. search-as-you-type, live preview)
              flatMapSequential: parallel + preserve ORDER (best of both)
            """);
    }

    // =========================================================================
    // DEMO 5 — Error Handling
    // =========================================================================
    static void demo5_errorHandling() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Error Handling: onError*, retry, timeout");
        System.out.println("─".repeat(70));

        System.out.println("""

            Error in reactive stream → terminates the stream (like uncaught exception).
            Operators to handle gracefully:
              onErrorReturn     — fallback value
              onErrorResume     — fallback Mono/Flux
              onErrorMap        — transform error type
              doOnError         — side effect (log), error still propagates
              retry / retryWhen — retry on error
              timeout           — error if no signal within duration
            """);

        // onErrorReturn — fallback value
        System.out.println("  [onErrorReturn — fallback constant]");
        Mono.error(new RuntimeException("DB connection failed"))
            .onErrorReturn("default-value")
            .subscribe(v -> System.out.println("    Got: " + v));

        // onErrorResume — fallback Mono
        System.out.println("\n  [onErrorResume — fallback Mono/Flux]");
        Mono.<String>error(new RuntimeException("Primary service down"))
            .onErrorResume(e -> {
                System.out.println("    Primary failed: " + e.getMessage() + " → using fallback");
                return Mono.just("fallback-data");
            })
            .subscribe(v -> System.out.println("    Got: " + v));

        // onErrorMap — transform error type (for API boundary)
        System.out.println("\n  [onErrorMap — translate error types]");
        Mono.<String>error(new RuntimeException("Low-level SQL error"))
            .onErrorMap(e -> new IllegalStateException("Service unavailable", e))
            .subscribe(
                v   -> System.out.println("    Got: " + v),
                err -> System.out.println("    Error type: " + err.getClass().getSimpleName()
                    + " → " + err.getMessage())
            );

        // doOnError — side effect (logging), error still propagates
        System.out.println("\n  [doOnError — log then propagate]");
        Mono.<String>error(new RuntimeException("Network timeout"))
            .doOnError(e -> System.out.println("    [LOG] Error intercepted: " + e.getMessage()))
            .onErrorReturn("recovered")
            .subscribe(v -> System.out.println("    Got: " + v));

        // retry — retry N times on any error
        System.out.println("\n  [retry(3) — retry up to 3 times]");
        AtomicInteger attempt = new AtomicInteger(0);
        Mono.fromCallable(() -> {
                int att = attempt.incrementAndGet();
                System.out.println("    Attempt #" + att);
                if (att < 3) throw new RuntimeException("Transient error");
                return "success on attempt " + att;
            })
            .retry(3)
            .subscribe(
                v   -> System.out.println("    Result: " + v),
                err -> System.out.println("    Final error: " + err.getMessage())
            );

        // retryWhen — advanced retry with backoff
        System.out.println("\n  [retryWhen — exponential backoff]");
        AtomicInteger attempt2 = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(1);
        Mono.fromCallable(() -> {
                int att = attempt2.incrementAndGet();
                if (att < 3) throw new RuntimeException("Transient #" + att);
                return "success";
            })
            .retryWhen(Retry.backoff(3, Duration.ofMillis(20))  // exponential: 20ms, 40ms, 80ms
                .maxBackoff(Duration.ofMillis(200))
                .doBeforeRetry(rs -> System.out.printf(
                    "    Retry #%d after failure: %s%n",
                    rs.totalRetries() + 1, rs.failure().getMessage())))
            .subscribe(
                v   -> { System.out.println("    Result: " + v); retryLatch.countDown(); },
                err -> { System.out.println("    Failed: " + err.getMessage()); retryLatch.countDown(); }
            );
        retryLatch.await(2, TimeUnit.SECONDS);

        // timeout
        System.out.println("\n  [timeout — error if no value within duration]");
        CountDownLatch timeoutLatch = new CountDownLatch(1);
        Mono.fromCallable(() -> "slow result")
            .delayElement(Duration.ofMillis(200))   // takes 200ms
            .timeout(Duration.ofMillis(100))         // timeout at 100ms
            .onErrorReturn("timeout-fallback")
            .subscribe(
                v -> { System.out.println("    Got: " + v); timeoutLatch.countDown(); }
            );
        timeoutLatch.await(2, TimeUnit.SECONDS);

        System.out.println("""
            ERROR HANDLING STRATEGY (SA pattern):
              1. doOnError         → structured logging (MDC context)
              2. retryWhen backoff → transient errors (network, DB connection)
              3. onErrorResume     → fallback: cache, default, degraded mode
              4. timeout           → always set on external calls (no infinite wait)
              5. onErrorMap        → translate internal exceptions at API boundary
            """);
    }

    // =========================================================================
    // DEMO 6 — Backpressure
    // =========================================================================
    static void demo6_backpressure() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — Backpressure: Subscriber Controls the Rate");
        System.out.println("─".repeat(70));

        System.out.println("""

            BACKPRESSURE = subscriber nói với publisher: "tôi chỉ nhận được N items"
              → Tránh OOM khi producer nhanh hơn consumer (e.g. Kafka → DB insert)

            Overflow strategies khi subscriber không kịp:
              BUFFER   — buffer all (default, OOM risk nếu unbounded)
              DROP     — discard new items when buffer full
              LATEST   — keep only latest item when buffer full
              ERROR    — throw OverflowException when buffer full
              IGNORE   — no backpressure (consumer must be fast enough)
            """);

        // BaseSubscriber — manual request control
        System.out.println("  [Manual backpressure via BaseSubscriber.request(N)]");
        CountDownLatch bpLatch = new CountDownLatch(1);

        Flux.range(1, 10)
            .log()   // uncomment to see request(N) signals in output
            .subscribe(new BaseSubscriber<>() {
                int received = 0;

                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    System.out.println("    Subscribed — requesting 2 items initially");
                    request(2); // pull first 2 items
                }

                @Override
                protected void hookOnNext(Integer value) {
                    System.out.printf("    Received: %d (total=%d)%n", value, ++received);
                    if (received % 2 == 0 && received < 6) {
                        System.out.println("    → Requesting 2 more");
                        request(2); // pull next 2 items when ready
                    }
                    if (received >= 6) {
                        System.out.println("    → Cancelling subscription (enough data)");
                        cancel();
                        bpLatch.countDown();
                    }
                }

                @Override
                protected void hookOnError(Throwable throwable) {
                    throwable.printStackTrace();
                    bpLatch.countDown();
                }

                @Override
                protected void hookOnComplete() {
                    System.out.println("    Complete");
                    bpLatch.countDown();
                }
            });

        bpLatch.await(3, TimeUnit.SECONDS);

        // Overflow strategies
        System.out.println("\n  [onBackpressureDrop — discard items when slow consumer]");
        CountDownLatch dropLatch = new CountDownLatch(1);
        AtomicInteger dropped = new AtomicInteger(0);
        AtomicInteger received = new AtomicInteger(0);

        Flux.range(1, 100)
            .onBackpressureDrop(item -> dropped.incrementAndGet())
            .publishOn(Schedulers.single(), 4)  // small prefetch buffer = 4
            .doOnNext(i -> {
                received.incrementAndGet();
                // Simulate slow consumer (no actual sleep to keep demo fast)
            })
            .take(10)
            .doOnComplete(() -> {
                System.out.printf("    Received: %d, Dropped: %d (fast producer, slow consumer)%n",
                    received.get(), dropped.get());
                dropLatch.countDown();
            })
            .subscribe();

        dropLatch.await(3, TimeUnit.SECONDS);

        System.out.println("\n  [onBackpressureBuffer with maxSize — bounded buffer]");
        CountDownLatch bufLatch = new CountDownLatch(1);
        AtomicInteger overflow = new AtomicInteger(0);

        Flux.range(1, 50)
            .onBackpressureBuffer(10,                          // max 10 items buffered
                dropped2 -> overflow.incrementAndGet(),        // overflow callback
                BufferOverflowStrategy.DROP_OLDEST)            // drop oldest on overflow
            .publishOn(Schedulers.single(), 2)
            .take(15)
            .doOnComplete(() -> {
                System.out.printf("    Overflowed: %d items dropped (buffer limited to 10)%n",
                    overflow.get());
                bufLatch.countDown();
            })
            .subscribe();

        bufLatch.await(3, TimeUnit.SECONDS);

        System.out.println("""
            BACKPRESSURE REAL WORLD:
              Kafka → DB writer:   onBackpressureBuffer(1000) → write in batches
              HTTP streaming:      BaseSubscriber.request(N) → flow control
              File lines reader:   Flux.fromStream → naturally pulls line-by-line
              WebSocket messages:  onBackpressureDrop → skip old market data ticks
            """);
    }

    // =========================================================================
    // DEMO 7 — Hot vs Cold Publisher
    // =========================================================================
    static void demo7_hotVsCold() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — Hot vs Cold Publisher");
        System.out.println("─".repeat(70));

        System.out.println("""

            COLD Publisher (default):
              • Data sequence starts fresh for EACH subscriber
              • Each subscriber gets ALL items from the beginning
              • Like a video-on-demand: start when you subscribe
              • Example: Flux.range(), Flux.fromIterable(), DB query

            HOT Publisher:
              • Emits regardless of whether anyone is subscribed
              • Late subscribers miss items already emitted
              • Like live TV: join when you join, miss what's past
              • Example: stock price ticker, sensor stream, Kafka topic
            """);

        // COLD demo
        System.out.println("  [COLD — each subscriber gets full sequence]");
        Flux<Integer> cold = Flux.range(1, 5);

        System.out.print("    Subscriber 1: ");
        cold.subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());

        System.out.print("    Subscriber 2: ");
        cold.subscribe(v -> System.out.print(v + " "), e -> {}, () -> System.out.println());

        // HOT via share() — multicast to current subscribers
        System.out.println("\n  [HOT via publish().autoConnect(2) — starts when 2 subs join]");
        CountDownLatch hotLatch = new CountDownLatch(2);

        Flux<Long> hotFlux = Flux.interval(Duration.ofMillis(50))
            .take(6)
            .publish()
            .autoConnect(2); // start publishing when 2 subscribers connected

        // Sub1 joins immediately
        System.out.print("    Sub1 (immediate): ");
        hotFlux.subscribe(
            v -> System.out.print("S1:" + v + " "), e -> {}, () -> { System.out.println(); hotLatch.countDown(); });

        Thread.sleep(80); // sub2 joins late (misses some items)

        // Sub2 joins late
        System.out.print("    Sub2 (80ms late): ");
        hotFlux.subscribe(
            v -> System.out.print("S2:" + v + " "), e -> {}, () -> { System.out.println(); hotLatch.countDown(); });

        hotLatch.await(3, TimeUnit.SECONDS);

        // ConnectableFlux — manual connect control
        System.out.println("\n  [ConnectableFlux — manual connect, replay cache]");
        CountDownLatch replayLatch = new CountDownLatch(2);

        ConnectableFlux<Integer> connectable = Flux.range(1, 5)
            .replay(3); // cache last 3 items for late subscribers

        // Connect starts emission
        connectable.connect();

        Thread.sleep(50); // let some items emit

        // Late subscriber gets cached last 3
        System.out.print("    Late sub (replay 3): ");
        connectable.subscribe(
            v -> System.out.print(v + " "), e -> {}, () -> { System.out.println(); replayLatch.countDown(); });

        System.out.print("    Another late sub:    ");
        connectable.subscribe(
            v -> System.out.print(v + " "), e -> {}, () -> { System.out.println(); replayLatch.countDown(); });

        replayLatch.await(2, TimeUnit.SECONDS);

        System.out.println("""
            HOT PUBLISHER VARIANTS:
              publish().autoConnect(N)  — start after N subscribers, never stops
              publish().refCount(N)     — start after N subs, STOP when 0 remain
              share()                   — shortcut for publish().refCount(1)
              replay(N)                 — cache last N for late subscribers
              replay(Duration)          — cache last Duration worth of items
              cache()                   — replay ALL items (use carefully, memory!)
            """);
    }

    // =========================================================================
    // DEMO 8 — Schedulers: thread pool control
    // =========================================================================
    static void demo8_schedulers() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8 — Schedulers: Controlling Thread Pools");
        System.out.println("─".repeat(70));

        System.out.println("""

            REACTOR SCHEDULERS:
            ┌─────────────────────┬────────────────────────────────────────────┐
            │ Scheduler           │ Use Case                                    │
            ├─────────────────────┼────────────────────────────────────────────┤
            │ Schedulers.immediate()  │ Current thread (default, no switch)   │
            │ Schedulers.single()     │ Single background thread              │
            │ Schedulers.parallel()   │ CPU-bound: nCPU threads               │
            │ Schedulers.boundedElastic() │ I/O-bound: growable thread pool   │
            │ Schedulers.fromExecutor() │ Custom ExecutorService              │
            └─────────────────────┴────────────────────────────────────────────┘

            subscribeOn  — affects WHERE the source emits (upstream thread)
            publishOn    — affects WHERE downstream operators run (switches thread)
            """);

        // subscribeOn: change source thread
        System.out.println("  [subscribeOn — source runs on specified scheduler]");
        CountDownLatch l1 = new CountDownLatch(1);
        Mono.fromCallable(() -> {
                System.out.printf("    Source runs on: %s%n", Thread.currentThread().getName());
                return "result";
            })
            .subscribeOn(Schedulers.boundedElastic())  // source on I/O pool
            .subscribe(v -> {
                System.out.printf("    Subscriber on: %s, value=%s%n",
                    Thread.currentThread().getName(), v);
                l1.countDown();
            });
        l1.await(2, TimeUnit.SECONDS);

        // publishOn: switch thread mid-chain
        System.out.println("\n  [publishOn — downstream runs on different scheduler]");
        CountDownLatch l2 = new CountDownLatch(1);
        Flux.range(1, 3)
            .map(i -> {
                System.out.printf("    map (before publishOn) on: %s%n",
                    Thread.currentThread().getName());
                return i * 2;
            })
            .publishOn(Schedulers.parallel())   // switch to parallel pool here
            .map(i -> {
                System.out.printf("    map (after publishOn)  on: %s%n",
                    Thread.currentThread().getName());
                return i;
            })
            .doOnComplete(l2::countDown)
            .subscribe();
        l2.await(2, TimeUnit.SECONDS);

        // Multiple publishOn hops
        System.out.println("\n  [Multiple publishOn — thread hopping pipeline]");
        CountDownLatch l3 = new CountDownLatch(1);
        Mono.just("input")
            .map(s -> {
                System.out.printf("    Step 1 on: %s%n", Thread.currentThread().getName());
                return s.toUpperCase();
            })
            .publishOn(Schedulers.boundedElastic())   // I/O work (e.g. DB read)
            .flatMap(s -> Mono.fromCallable(() -> {
                System.out.printf("    Step 2 (I/O) on: %s%n", Thread.currentThread().getName());
                return s + "-enriched";
            }))
            .publishOn(Schedulers.parallel())          // CPU work (e.g. JSON serialize)
            .map(s -> {
                System.out.printf("    Step 3 (CPU) on: %s%n", Thread.currentThread().getName());
                return s.toLowerCase();
            })
            .subscribe(v -> {
                System.out.printf("    Result: %s on: %s%n", v, Thread.currentThread().getName());
                l3.countDown();
            });
        l3.await(2, TimeUnit.SECONDS);

        System.out.println("""
            SCHEDULER RULES:
              ✓ subscribeOn: only first one takes effect (closest to source)
              ✓ publishOn: each one switches thread for downstream
              ✓ Blocking code in reactive chain: use subscribeOn(boundedElastic())
              ✓ CPU operators: parallel()
              ✓ I/O / blocking: boundedElastic() (auto-grows under load)
              ✓ NEVER block main thread: Thread.sleep() → replace with delayElement()
              ✓ Spring WebFlux: don't call block() inside reactive chain (deadlock!)
            """);
    }

    // =========================================================================
    // DEMO 9 — Real-World Pipeline: Order Processing
    // =========================================================================
    static void demo9_realWorldPipeline() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 9 — Real-World Pipeline: Reactive Order Processing");
        System.out.println("─".repeat(70));

        System.out.println("""

            Scenario: Process 10 orders concurrently
              1. Validate order (fast, sync)
              2. Check inventory (async, 50ms per call)
              3. Calculate price with discount (async, 30ms per call)
              4. Save to DB (async, 20ms per call)
              5. Collect results with error handling
            """);

        record Order(int id, String item, int qty) {}
        record PricedOrder(int id, String item, int qty, double price) {}

        // Simulate async services
        java.util.function.Function<Order, Mono<Order>> checkInventory = order ->
            Mono.fromCallable(() -> {
                if (order.qty() > 100) throw new IllegalArgumentException("Qty exceeds stock: " + order.id());
                return order;
            }).delayElement(Duration.ofMillis(50)).subscribeOn(Schedulers.boundedElastic());

        java.util.function.Function<Order, Mono<PricedOrder>> calculatePrice = order ->
            Mono.fromCallable(() -> {
                double base = order.qty() * 9.99;
                double discount = order.qty() > 10 ? 0.1 : 0.0;
                return new PricedOrder(order.id(), order.item(), order.qty(), base * (1 - discount));
            }).delayElement(Duration.ofMillis(30)).subscribeOn(Schedulers.boundedElastic());

        java.util.function.Function<PricedOrder, Mono<String>> saveToDb = po ->
            Mono.fromCallable(() ->
                String.format("Order#%d saved: %s×%d @ $%.2f", po.id(), po.item(), po.qty(), po.price())
            ).delayElement(Duration.ofMillis(20)).subscribeOn(Schedulers.boundedElastic());

        // Build orders — order #7 will fail inventory check
        List<Order> orders = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new Order(i, "item-" + i, i == 7 ? 999 : i * 3))
            .collect(Collectors.toList());

        CountDownLatch pipelineLatch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed  = new AtomicInteger(0);
        long pipelineStart = System.currentTimeMillis();

        Flux.fromIterable(orders)
            // Step 1: validate (sync filter)
            .filter(o -> o.qty() > 0)

            // Step 2-4: async pipeline, max 4 concurrent
            .flatMap(order ->
                Mono.just(order)
                    .flatMap(checkInventory)
                    .flatMap(calculatePrice)
                    .flatMap(saveToDb)
                    .doOnNext(r -> success.incrementAndGet())
                    .onErrorResume(e -> {
                        failed.incrementAndGet();
                        return Mono.just(String.format("Order#%d FAILED: %s", order.id(), e.getMessage()));
                    }),
                4)  // max 4 concurrent pipelines

            // Collect all results
            .collectList()
            .subscribe(results -> {
                long totalMs = System.currentTimeMillis() - pipelineStart;
                System.out.println("  Results:");
                results.forEach(r -> System.out.println("    " + r));
                System.out.printf("%n  Total: %d succeeded, %d failed, time=%dms%n",
                    success.get(), failed.get(), totalMs);
                System.out.printf("  Sequential would take: ~%dms (10 × 100ms)%n",
                    10 * 100);
                System.out.printf("  Speedup: ~%.1fx (concurrency=4)%n",
                    (10.0 * 100) / Math.max(totalMs, 1));
                pipelineLatch.countDown();
            });

        pipelineLatch.await(15, TimeUnit.SECONDS);
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 6.4 — Reactive Programming Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            OPERATOR CHEAT SHEET:
            ┌────────────────────┬──────────────────────────────────────────────┐
            │ Operator           │ Effect                                        │
            ├────────────────────┼──────────────────────────────────────────────┤
            │ map                │ sync 1:1 transform                           │
            │ flatMap            │ async 1:N, CONCURRENT, unordered result      │
            │ concatMap          │ async 1:N, SEQUENTIAL, ordered result        │
            │ switchMap          │ async, CANCEL previous on new input          │
            │ flatMapSequential  │ async, CONCURRENT, ordered result (best of both)│
            │ filter             │ keep matching                                 │
            │ take / skip        │ head / tail control                          │
            │ reduce             │ aggregate → Mono                             │
            │ scan               │ running aggregate → Flux                     │
            │ zip                │ combine pairwise                             │
            │ merge / concat     │ combine sources (unordered / ordered)        │
            │ buffer             │ batch N items                                │
            │ groupBy            │ split by key                                 │
            │ publish / share    │ multicast (cold → hot)                       │
            │ replay             │ cache for late subscribers                   │
            └────────────────────┴──────────────────────────────────────────────┘

            BACKPRESSURE STRATEGY:
              Producer fast, consumer slow:
                onBackpressureBuffer(N)  → bounded buffer (safe)
                onBackpressureDrop       → drop new (for real-time data)
                onBackpressureLatest     → keep latest (market ticks)
                BaseSubscriber.request() → manual pull (full control)

            SCHEDULER RULE:
              Blocking I/O call → subscribeOn(Schedulers.boundedElastic())
              CPU computation   → publishOn(Schedulers.parallel())
              Test/main only    → block() / blockFirst() (never in prod chain)

            WHEN TO USE REACTIVE (honest assessment):
              ✅ Streaming APIs (SSE, WebSocket), high-concurrency gateways
              ✅ Async pipeline: Kafka → enrich → DB → notify (chaining)
              ✅ Spring WebFlux + R2DBC (reactive DB) end-to-end
              ❌ Simple CRUD: Spring MVC + Virtual Threads is simpler, equivalent
              ❌ CPU-intensive: reactive adds overhead without benefit
              ❌ Small team unfamiliar with reactive: maintenance nightmare

            KEY INSIGHT:
              "Reactive is about COMPOSITION of async operations with backpressure,
               not just 'making things faster'. The real value is in the operator
               DSL: flatMap, retry, timeout, merge — patterns that would take
               hundreds of lines to implement manually with CompletableFuture."
            """);
    }
}
