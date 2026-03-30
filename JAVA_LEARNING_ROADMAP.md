# Java Advanced Learning Roadmap
> Lộ trình học Java chuyên sâu dành cho Solution Architect
> Bắt đầu: 2026-03-22 | Cập nhật lần cuối: 2026-03-30 (bài 8.1)

---

## Tiến độ tổng quan

| Module | Chủ đề | Bài | Trạng thái |
|--------|--------|-----|-----------|
| 1 | JVM Internals | 4/4 | ✅ **Hoàn thành** |
| 2 | Concurrency & Threading | 6/6 | ✅ **Hoàn thành** |
| 3 | Collections & Generics nâng cao | 4/4 | ✅ **Hoàn thành** |
| 4 | Design Patterns thực chiến | 5/5 | ✅ **Hoàn thành** |
| 5 | Performance & Profiling | 4/4 | ✅ **Hoàn thành** |
| 6 | I/O & NIO / Reactive | 4/4 | ✅ **Hoàn thành** |
| 7 | Reflection, Annotation, Bytecode | 3/3 | ✅ **Hoàn thành** |
| 8 | Testing chuyên sâu | 1/3 | 🔵 Đang học |
| 9 | Architecture Patterns (SA level) | 0/5 | ⬜ Chưa bắt đầu |
| 10 | Java Ecosystem & Tooling | 0/3 | ⬜ Chưa bắt đầu |

**Tổng tiến độ: 31 / 41 bài** `[███████████████░] 76%` — Module 1 ✅ | Module 2 ✅ | Module 3 ✅ | Module 4 ✅ | Module 5 ✅ | Module 6 ✅ | Module 7 ✅ | Module 8 🔵

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
| 5.1 | `JMHBenchmarkDemo.java` | JMH micro-benchmark đúng cách, JIT bias | 2026-03-29 | ✅ |
| 5.2 | `GCTuningDemo.java` | GC flags (-Xmx, -XX:+UseG1GC), GC log analysis | 2026-03-30 | ✅ |
| 5.3 | `MemoryLeakDemo.java` | Detect & fix memory leak, heap dump analysis | 2026-03-30 | ✅ |
| 5.4 | `ProfilingDemo.java` | async-profiler, flame graph, CPU vs allocation profiling | 2026-03-30 | ✅ |

---

## Module 6 — I/O & NIO / Reactive

> **Mục tiêu:** Hiểu blocking vs non-blocking I/O, chọn đúng model cho throughput cao
> **Package:** `org.example.io`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 6.1 | `BlockingIODemo.java` | InputStream/OutputStream, BufferedIO, RandomAccessFile | 2026-03-30 | ✅ |
| 6.2 | `NIODemo.java` | ByteBuffer, Channel, Selector (non-blocking) | 2026-03-30 | ✅ |
| 6.3 | `NIO2Demo.java` | Path, Files, WatchService, AsynchronousFileChannel | 2026-03-30 | ✅ |
| 6.4 | `ReactiveDemo.java` | Project Reactor, Mono/Flux, backpressure | 2026-03-30 | ✅ |

---

## Module 7 — Reflection, Annotation & Bytecode

> **Mục tiêu:** Hiểu cách frameworks hoạt động bên trong (Spring DI, Hibernate, JSON serialization)
> **Package:** `org.example.meta`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 7.1 | `ReflectionDemo.java` | Class introspection, dynamic proxy (JDK & CGLIB) | 2026-03-30 | ✅ |
| 7.2 | `AnnotationProcessorDemo.java` | Custom annotation + runtime processing | 2026-03-30 | ✅ |
| 7.3 | `BytecodeDemo.java` | Đọc bytecode với javap, ASM basics, instrumentation | 2026-03-30 | ✅ |

---

## Module 8 — Testing chuyên sâu

> **Mục tiêu:** Test strategy cho enterprise: unit, integration, contract, chaos testing
> **Package:** `org.example.testing`

| # | File | Chủ đề | Ngày hoàn thành | Ghi chú |
|---|------|--------|-----------------|---------|
| 8.1 | `MockingDeepDiveDemo.java` | Mockito internals, Spy vs Mock, verification | 2026-03-30 | ✅ |
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
| 2026-03-29 | 5.1 JMHBenchmarkDemo | Dùng JMH không System.nanoTime. Blackhole.consume() chặn dead-code elimination. @State tránh constant folding. StringBuilder 20-50x nhanh hơn String+. parallelStream chỉ win khi N>10k + CPU-bound. |
| 2026-03-30 | 5.2 GCTuningDemo | G1=default balanced; ZGC=<1ms pause dùng cho latency-sensitive. Set Xms=Xmx tránh resize overhead. LUÔN bật GC log + HeapDump ở production. Tune GC sau khi fix leak, không trước. System.gc() trong code = anti-pattern, dùng -XX:+DisableExplicitGC. |
| 2026-03-30 | 5.3 MemoryLeakDemo | Leak = object reachable nhưng không còn dùng. Top causes: static collection, listener leak, ThreadLocal không remove(), inner class capture, unclosed resource. Strong>Soft>Weak>Phantom. ThreadLocal: luôn remove() trong finally. WeakHashMap tự expire khi key GC'd. Cleaner thay thế finalize(). |
| 2026-03-30 | 5.4 ProfilingDemo | Measure first, optimize second. async-profiler: cpu/alloc/lock/wall mode. Flame graph: frame rộng gần top = hot path. JFR continuous ở production (<1% overhead). Pattern.compile trong hot path = 10-50x slow. LongAdder vs AtomicLong: per-cell vs CAS. Wall-clock > CPU = I/O bound, không phải CPU bound. |
| 2026-03-30 | 6.1 BlockingIODemo | Buffering = optimization #1: unbuffered 1 syscall/byte vs 1 syscall/8KB. FileReader/Writer nguy hiểm (platform charset) → LUÔN dùng InputStreamReader + UTF-8. FileChannel.transferTo = zero-copy (sendfile). MappedByteBuffer cho large file random access. Buffer sweet spot: 8-16KB. try-with-resources bắt buộc. |
| 2026-03-30 | 6.2 NIODemo | ByteBuffer state machine: flip→read, clear→write, compact→preserve unread. Selector = Reactor pattern: 1 thread, N channels. iter.remove() sau mỗi key. OP_WRITE chỉ register khi có data. SelectionKey.attachment() lưu per-connection state. Scatter/gather = vectored I/O, 1 syscall. NIO wins ở high concurrency, VirtualThread cho simplicity. |
| 2026-03-30 | 6.3 NIO2Demo | Path immutable thay File. toRealPath() resolve symlinks (cần file tồn tại). WatchService: key.reset() bắt buộc sau mỗi poll, không recursive tự động. walkFileTree: SKIP_SUBTREE bỏ qua target/. AsyncFileChannel: CompletionHandler chạy trên pool thread, không block trong handler. ZipFileSystem: đọc/ghi ZIP như thư mục thường. |
| 2026-03-30 | 6.4 ReactiveDemo | Mono=0..1, Flux=0..N, lazy(cold). flatMap=concurrent+unordered, concatMap=sequential+ordered, switchMap=cancel-previous. Backpressure: BaseSubscriber.request(N). onBackpressureDrop/Buffer/Latest. Hot via publish().autoConnect(). subscribeOn=source thread, publishOn=downstream thread. retryWhen(Retry.backoff). |
| 2026-03-30 | 7.1 ReflectionDemo | getDeclaredFields/Methods vs getFields/Methods (private vs public+inherited). setAccessible(true) bypass access control. Cache Method object — lookup là expensive. InvocationTargetException wraps actual exception, unwrap bằng getCause(). JDK Proxy cần interface; CGLIB cần class. MethodHandle JIT-inlineable ≈ direct call sau warmup. TypeToken trick capture generic type qua superclass. |
| 2026-03-30 | 7.2 AnnotationProcessorDemo | @Retention(RUNTIME) để đọc annotation lúc chạy. @Target giới hạn context. @Inherited chỉ kế thừa qua class, không qua interface/method. @Repeatable cần container annotation. Meta-annotation: annotate the annotation. Mini validator framework, mini ORM mapper, mini event bus, mini DI container đều dùng reflection + annotation kết hợp. Compile-time: AbstractProcessor + ProcessingEnvironment tạo code tại build time (Lombok/MapStruct pattern). |
| 2026-03-30 | 7.3 BytecodeDemo | JVM = stack machine: local var array + operand stack. invokevirtual(vtable) vs invokeinterface(itable) vs invokestatic vs invokespecial vs invokedynamic. Lambda dùng invokedynamic + LambdaMetafactory (không phải anonymous class). ASM: ClassReader→ClassVisitor→ClassWriter visitor chain. javaagent: premain()+ClassFileTransformer intercepts every class load. CGLIB subclasses target (Spring @Transactional); self-invocation bypasses proxy → LazyInitializationException. final class cannot be CGLIB proxied. |
| 2026-03-30 | 8.1 MockingDeepDiveDemo | Mock=full replacement(default null/0/false/[]), Spy=real object+selective stub. doReturn(x).when(spy).method() tránh real call lúc stub. void method: doThrow/doAnswer/doNothing. ArgumentCaptor capture argument để assert. InOrder verify thứ tự gọi. verifyNoMoreInteractions phát hiện unexpected calls. Fake(InMemoryRepo) tốt hơn mock cho state-based test. Strict stubbing phát hiện unused stubs. |

---

## Bài tiếp theo được đề xuất

**→ Bài 8.2: `TestContainersDemo.java`**
- TestContainers: spin up real Docker containers in tests
- PostgreSQL, Redis, Kafka containers trong integration test
- @Container + @DynamicPropertySource (Spring integration)
- Singleton container pattern (share across test class)
- Network, wait strategies, custom images

---

*File này được cập nhật sau mỗi buổi học. Mỗi bài = 1 demo Java có thể chạy được.*
