# Java Advanced Learning Roadmap
> Lộ trình học Java chuyên sâu dành cho Solution Architect
> Bắt đầu: 2026-03-22 | Cập nhật lần cuối: 2026-03-29 (bài 4.3)

---

## Tiến độ tổng quan

| Module | Chủ đề | Bài | Trạng thái |
|--------|--------|-----|-----------|
| 1 | JVM Internals | 4/4 | ✅ **Hoàn thành** |
| 2 | Concurrency & Threading | 6/6 | ✅ **Hoàn thành** |
| 3 | Collections & Generics nâng cao | 4/4 | ✅ **Hoàn thành** |
| 4 | Design Patterns thực chiến | 5/5 | ✅ **Hoàn thành** |
| 5 | Performance & Profiling | 0/4 | ⬜ Chưa bắt đầu |
| 6 | I/O & NIO / Reactive | 0/4 | ⬜ Chưa bắt đầu |
| 7 | Reflection, Annotation, Bytecode | 0/3 | ⬜ Chưa bắt đầu |
| 8 | Testing chuyên sâu | 0/3 | ⬜ Chưa bắt đầu |
| 9 | Architecture Patterns (SA level) | 0/5 | ⬜ Chưa bắt đầu |
| 10 | Java Ecosystem & Tooling | 0/3 | ⬜ Chưa bắt đầu |

**Tổng tiến độ: 19 / 41 bài** `[█████████░] 46%` — Module 1 ✅ | Module 2 ✅ | Module 3 ✅ | Module 4 ✅ | Module 5 🔵

---

## Module 1 — JVM Internals

> **Mục tiêu:** Hiểu cách JVM hoạt động bên trong để debug OOM, tối ưu performance, tránh bug khởi động
> **Package:** `org.example.jvm`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 1.1 | `ClassLoadingDemo.java` | ClassLoader Hierarchy, Static Init Order, Lazy Loading | 2026-03-22 | ✅ |
| 1.2 | `MemoryDemo.java` | Heap vs Stack vs Metaspace, GC trigger, StackOverflow | 2026-03-22 | ✅ |
| 1.3 | `JITDemo.java` | JIT warm-up, Escape Analysis, Inlining, Loop Unrolling | 2026-03-22 | ✅ |
| 1.4 | `GCDemo.java` | GC Algorithms (G1, ZGC, Shenandoah), GC log đọc hiểu | 2026-03-29 | ✅ |

**Key takeaways:**
- ClassLoader: Bootstrap → Platform → App (delegation model, parent-first)
- Initialization order: Parent static → Child static → Parent constructor → Child constructor
- Memory: Stack (thread-local, fast) vs Heap (shared, GC managed) vs Metaspace (class metadata)
- JIT: interpreted → C1 compile (1,500 calls) → C2 full optimize (10,000 calls) = warm-up period

---

## Module 2 — Concurrency & Multithreading

> **Mục tiêu:** Viết code concurrent an toàn, hiểu Java Memory Model, tránh race condition & deadlock
> **Package:** `org.example.concurrency`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 2.1 | `JMMDemo.java` | Java Memory Model, happens-before, volatile | 2026-03-29 | ✅ |
| 2.2 | `SynchronizedDemo.java` | synchronized, intrinsic lock, monitor, reentrant | 2026-03-29 | ✅ |
| 2.3 | `LockDemo.java` | ReentrantLock, ReadWriteLock, StampedLock | 2026-03-29 | ✅ |
| 2.4 | `ExecutorDemo.java` | ThreadPool, ExecutorService, ForkJoinPool | 2026-03-29 | ✅ |
| 2.5 | `CompletableFutureDemo.java` | Async programming, thenCompose, exceptionally | 2026-03-29 | ✅ |
| 2.6 | `VirtualThreadDemo.java` | Project Loom, Virtual Threads (Java 21), structured concurrency | 2026-03-29 | ✅ |

---

## Module 3 — Collections & Generics nâng cao

> **Mục tiêu:** Chọn đúng collection cho từng bài toán, hiểu internal structure, tránh lỗi hiệu năng
> **Package:** `org.example.collections`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 3.1 | `CollectionInternalsDemo.java` | HashMap rehashing, ArrayList amortized cost, TreeMap | 2026-03-29 | ✅ |
| 3.2 | `ConcurrentCollectionsDemo.java` | ConcurrentHashMap, CopyOnWriteArrayList, BlockingQueue | 2026-03-29 | ✅ |
| 3.3 | `GenericsAdvancedDemo.java` | Wildcards (? extends / ? super), type erasure, bounds | 2026-03-29 | ✅ |
| 3.4 | `StreamsAdvancedDemo.java` | Spliterator, parallel streams, collector internals | 2026-03-29 | ✅ |

---

## Module 4 — Design Patterns thực chiến

> **Mục tiêu:** Áp dụng patterns đúng ngữ cảnh, nhận biết patterns trong framework (Spring, Jakarta EE)
> **Package:** `org.example.patterns`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 4.1 | `CreationalPatternsDemo.java` | Singleton (thread-safe), Builder, Factory, Prototype | 2026-03-29 | ✅ |
| 4.2 | `StructuralPatternsDemo.java` | Decorator, Proxy (dynamic), Adapter, Facade | 2026-03-29 | ✅ |
| 4.3 | `BehavioralPatternsDemo.java` | Observer, Strategy, Command, Chain of Responsibility | 2026-03-29 | ✅ |
| 4.4 | `ConcurrentPatternsDemo.java` | Producer-Consumer, Thread-per-request vs Reactor | 2026-03-29 | ✅ |
| 4.5 | `AntiPatternsDemo.java` | Lemon patterns, God Object, Service Locator anti-pattern | 2026-03-29 | ✅ |

---

## Module 5 — Performance & Profiling

> **Mục tiêu:** Đo, phân tích và tối ưu performance đúng cách (benchmark, không đoán mò)
> **Package:** `org.example.performance`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 5.1 | `JMHBenchmarkDemo.java` | JMH micro-benchmark đúng cách, JIT bias | — | ⬜ |
| 5.2 | `GCTuningDemo.java` | GC flags (-Xmx, -XX:+UseG1GC), GC log analysis | — | ⬜ |
| 5.3 | `MemoryLeakDemo.java` | Detect & fix memory leak, heap dump analysis | — | ⬜ |
| 5.4 | `ProfilingDemo.java` | async-profiler, flame graph, CPU vs allocation profiling | — | ⬜ |

---

## Module 6 — I/O & NIO / Reactive

> **Mục tiêu:** Hiểu blocking vs non-blocking I/O, chọn đúng model cho throughput cao
> **Package:** `org.example.io`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 6.1 | `BlockingIODemo.java` | InputStream/OutputStream, BufferedIO, RandomAccessFile | — | ⬜ |
| 6.2 | `NIODemo.java` | ByteBuffer, Channel, Selector (non-blocking) | — | ⬜ |
| 6.3 | `NIO2Demo.java` | Path, Files, WatchService, AsynchronousFileChannel | — | ⬜ |
| 6.4 | `ReactiveDemo.java` | Project Reactor, Mono/Flux, backpressure | — | ⬜ |

---

## Module 7 — Reflection, Annotation & Bytecode

> **Mục tiêu:** Hiểu cách frameworks hoạt động bên trong (Spring DI, Hibernate, JSON serialization)
> **Package:** `org.example.meta`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 7.1 | `ReflectionDemo.java` | Class introspection, dynamic proxy (JDK & CGLIB) | — | ⬜ |
| 7.2 | `AnnotationProcessorDemo.java` | Custom annotation + runtime processing | — | ⬜ |
| 7.3 | `BytecodeDemo.java` | Đọc bytecode với javap, ASM basics, instrumentation | — | ⬜ |

---

## Module 8 — Testing chuyên sâu

> **Mục tiêu:** Test strategy cho enterprise: unit, integration, contract, chaos testing
> **Package:** `org.example.testing`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 8.1 | `MockingDeepDiveDemo.java` | Mockito internals, Spy vs Mock, verification | — | ⬜ |
| 8.2 | `TestContainersDemo.java` | TestContainers: DB, Kafka, Redis trong integration test | — | ⬜ |
| 8.3 | `MutationTestingDemo.java` | PIT mutation testing, test quality metrics | — | ⬜ |

---

## Module 9 — Architecture Patterns (SA Level)

> **Mục tiêu:** Áp dụng các architectural patterns trong hệ thống thực tế
> **Package:** `org.example.architecture`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 9.1 | `DomainDrivenDemo.java` | DDD: Entity, Value Object, Aggregate, Repository | — | ⬜ |
| 9.2 | `EventSourcingDemo.java` | Event Sourcing + CQRS pattern | — | ⬜ |
| 9.3 | `SagaPatternDemo.java` | Saga (choreography vs orchestration) distributed tx | — | ⬜ |
| 9.4 | `ResilienceDemo.java` | Circuit Breaker, Retry, Bulkhead (Resilience4j) | — | ⬜ |
| 9.5 | `ObservabilityDemo.java` | Structured logging, distributed tracing, metrics | — | ⬜ |

---

## Module 10 — Java Ecosystem & Tooling

> **Mục tiêu:** Nắm vững build tools, dependency management, và modern Java features
> **Package:** `org.example.tooling`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 10.1 | `ModernJavaFeaturesDemo.java` | Records, Sealed classes, Pattern Matching, Text Blocks | — | ⬜ |
| 10.2 | `MavenAdvancedDemo.java` | Maven lifecycle, plugin config, multi-module projects | — | ⬜ |
| 10.3 | `GraalVMDemo.java` | Native Image, AOT compilation, performance tradeoffs | — | ⬜ |

---

## Nhật ký học tập

| Ngày | Bài học | Insight quan trọng |
|------|---------|--------------------|
| 2026-03-22 | 1.1 ClassLoadingDemo | Parent delegation: Bootstrap > Platform > App. Bootstrap = null trong Java |
| 2026-03-22 | 1.2 MemoryDemo | OOM có 2 loại: HeapSpace và MetaspaceOOM — cần đọc error message kỹ trước khi tune |
| 2026-03-22 | 1.3 JITDemo | Load test đầu tiên luôn chậm vì JIT chưa warm up — không nên lấy round 1 làm baseline |
| 2026-03-29 | 1.4 GCDemo | G1=balanced, ZGC=low-latency. LUÔN bật HeapDump + GC log ở production. Tune GC chỉ sau khi fix memory leak |
| 2026-03-29 | 2.1 JMMDemo | volatile fix Visibility KHÔNG fix Atomicity. i++ cần AtomicInteger. Singleton dùng Holder pattern, không cần lock |
| 2026-03-29 | 2.2 SynchronizedDemo | Deadlock = Circular Wait → fix bằng Lock Ordering. Minimize lock scope: synchronized block tốt hơn method. Private lock object tốt hơn lock trên `this` |
| 2026-03-29 | 2.3 LockDemo | tryLock phá "Hold and Wait" (Coffman). ReadWriteLock: read song song, write độc quyền. StampedLock optimistic read = zero lock overhead khi không có write |
| 2026-03-29 | 2.4 ExecutorDemo | IO-bound sizing: nCPU × (1+W/C). KHÔNG dùng unbounded queue ở production. ForkJoin work-stealing: thread idle thì steal task từ thread khác. Exception bị nuốt nếu không gọi future.get() |
| 2026-03-29 | 2.5 CompletableFutureDemo | thenCompose=flat-map async, thenCombine=merge 2 CF độc lập. KHÔNG dùng commonPool cho I/O. anyOf=hedged request giảm tail latency. whenComplete=side effect không ảnh hưởng kết quả |
| 2026-03-29 | 2.6 VirtualThreadDemo | Virtual thread ~200 bytes vs platform thread ~1MB. Pinning = synchronized + blocking → fix bằng ReentrantLock. CPU-bound không được lợi từ virtual thread. Spring Boot 3.2: spring.threads.virtual.enabled=true |
| 2026-03-29 | 3.1 CollectionInternalsDemo | HashMap: mutable key = data mất. Pre-size tránh rehash. ArrayList luôn nhanh hơn LinkedList nhờ cache locality. LinkedHashMap(accessOrder) = LRU Cache. ArrayDeque thay LinkedList. |
| 2026-03-29 | 3.2 ConcurrentCollectionsDemo | synchronizedMap compound op không atomic → dùng CHM.merge/compute. CHM null bị cấm. CopyOnWrite chỉ read-heavy. BlockingQueue put() block tạo back-pressure tự nhiên. |
| 2026-03-29 | 3.3 GenericsAdvancedDemo | Type erasure: List&lt;String&gt;==List&lt;Integer&gt; runtime → TypeToken workaround. PECS: Producer Extends Consumer Super. Recursive bound &lt;T extends Comparable&lt;T&gt;&gt;. @SafeVarargs chỉ dùng khi không ghi vào varargs. |
| 2026-03-29 | 3.4 StreamsAdvancedDemo | Lazy eval: intermediate ops không chạy cho đến terminal. Parallel stream: stateless+large+CPU-bound mới win. Custom Collector: supplier→accumulate→combine→finisher. teeing/flatMapping/groupingBy nested. |
| 2026-03-29 | 4.1 CreationalPatternsDemo | Singleton: Holder=lazy+thread-safe, Enum=chống reflection. Builder: Step Builder ép compile-time required fields. Abstract Factory: swap cả family không sửa client. Prototype Registry: deep copy template. |
| 2026-03-29 | 4.2 StructuralPatternsDemo | Decorator compose tại runtime vs inheritance. JDK Proxy chỉ proxy interface, self-invocation bypass proxy. Adapter=Anti-corruption Layer. Facade=Application Service. Composite=leaf+container đồng nhất. |
| 2026-03-29 | 4.3 BehavioralPatternsDemo | EventBus type-safe pub/sub. Strategy+Map=plugin registry. Command undo/redo+audit. Chain=middleware short-circuit. State Machine thay switch→extensible. Domain Event phải immutable. |
| 2026-03-29 | 4.4 ConcurrentPatternsDemo | Producer-Consumer: Poison Pill shutdown. PriorityBlockingQueue: task priority. DelayQueue: retry scheduling. Thread-per-request vs Reactor vs VirtualThread: VT wins I/O. Active Object: single servant = no contention. Half-Sync/Half-Async: async accept + sync process. ObjectPool: Semaphore+LIFO+validate. |
| 2026-03-29 | 4.5 AntiPatternsDemo | God Object=SRP vi phạm→Bounded Context. Service Locator=hidden dep→Constructor DI. Singleton Abuse=mutable global→ThreadLocal scope. Primitive Obsession→Value Object (record). Anemic Domain=data bag+proc svc→Rich Entity. Magic Numbers→Enum+NamedConstant. |

---

## Bài tiếp theo được đề xuất

**→ Bài 5.1: `JMHBenchmarkDemo.java`**
- JMH micro-benchmark đúng cách (tránh dead-code elimination, JIT bias)
- @Benchmark, @Warmup, @Measurement, BenchmarkMode
- Pitfalls: Black Holes, Constant Folding, Loop Unrolling
- So sánh String concatenation vs StringBuilder vs StringJoiner

---

*File này được cập nhật sau mỗi buổi học. Mỗi bài = 1 demo Java có thể chạy được.*
