package org.example.performance;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * BÀI 5.1 — JMH Micro-Benchmark đúng cách (Java Microbenchmark Harness)
 * =============================================================================
 *
 * JMH là framework benchmark CHÍNH THỨC của OpenJDK. Tại sao không dùng
 * System.currentTimeMillis()?
 *   • JIT warm-up: lần đầu chạy code bao giờ cũng chậm hơn (interpreted mode)
 *   • Dead code elimination: JIT bỏ code nếu kết quả không được dùng
 *   • Constant folding: JIT tính sẵn kết quả compile-time → 0ms mọi lần
 *   • Loop unrolling: JIT unroll loop → thay đổi branch prediction profile
 *   • GC interference: GC pause trong giữa đo → số liệu lệch
 *
 * JMH giải quyết tất cả những vấn đề trên thông qua:
 *   1. @Warmup — chạy N iteration trước để JIT đạt steady state (C2 compile)
 *   2. @Measurement — chỉ đo sau warm-up
 *   3. Blackhole.consume() — ngăn dead code elimination
 *   4. @State — quản lý object lifecycle, tránh constant folding
 *   5. Fork — chạy trong JVM process riêng biệt để tránh JIT pollution
 *
 * BenchmarkMode:
 *   • Throughput (ops/s)   — bao nhiêu lần/giây → high-frequency operations
 *   • AverageTime (ms/op)  — thời gian trung bình mỗi call → latency-sensitive
 *   • SampleTime           — phân phối latency (percentile p50/p95/p99)
 *   • SingleShotTime       — cold start, không warm-up → startup performance
 *
 * SA Insight:
 *   • "Đừng bao giờ optimize trước khi benchmark" — Donald Knuth
 *   • Benchmark phải reflect production workload: same data size, same contention
 *   • JMH benchmark chỉ valid cho micro-operations — system benchmark cần k6/Gatling
 *   • Regression benchmark trong CI: nếu p99 tăng >10% → PR blocked
 *   • Flame graph + JMH = tìm được HOT PATH chính xác để optimize
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.performance.JMHBenchmarkDemo"
 *
 * Note: First run sẽ download JMH dependencies, sau đó benchmark chạy thật.
 * Mỗi benchmark group chạy ~10-15 giây. Tổng demo ≈ 2-3 phút.
 */
public class JMHBenchmarkDemo {

    public static void main(String[] args) throws RunnerException {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 5.1 — JMH Micro-Benchmark đúng cách");
        System.out.println("=".repeat(70));
        System.out.println();

        // ── Giải thích trước khi chạy ──
        printBenchmarkConcepts();

        // ── Chạy các benchmark group ──
        System.out.println("\n" + "─".repeat(70));
        System.out.println("BENCHMARK 1 — String Concatenation: +  vs  StringBuilder  vs  StringJoiner");
        System.out.println("─".repeat(70));
        runBenchmarks(StringConcatBenchmark.class, 2, 3);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("BENCHMARK 2 — Dead Code Elimination: với và không có Blackhole");
        System.out.println("─".repeat(70));
        runBenchmarks(DeadCodeBenchmark.class, 2, 3);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("BENCHMARK 3 — Collection: ArrayList vs LinkedList vs ArrayDeque");
        System.out.println("─".repeat(70));
        runBenchmarks(CollectionBenchmark.class, 2, 3);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("BENCHMARK 4 — Stream vs For-loop vs parallelStream");
        System.out.println("─".repeat(70));
        runBenchmarks(StreamBenchmark.class, 2, 3);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("BENCHMARK 5 — HashMap vs TreeMap vs LinkedHashMap lookup");
        System.out.println("─".repeat(70));
        runBenchmarks(MapLookupBenchmark.class, 2, 3);

        printSAInsights();
    }

    // =========================================================================
    // Utility: run a benchmark class with given warmup/measurement iterations
    // =========================================================================
    private static void runBenchmarks(Class<?> benchmarkClass, int warmup, int measurement)
            throws RunnerException {
        Options opts = new OptionsBuilder()
            .include(benchmarkClass.getSimpleName())
            .warmupIterations(warmup)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(measurement)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)                             // 1 JVM fork để demo nhanh; production dùng 3+
            .mode(Mode.AverageTime)
            .timeUnit(TimeUnit.MICROSECONDS)
            .shouldFailOnError(true)
            .build();

        new Runner(opts).run();
    }

    // =========================================================================
    // BENCHMARK 1 — String Concatenation
    // =========================================================================

    /**
     * Classic benchmark: so sánh 3 cách nối chuỗi trong loop.
     *
     * Expected result:
     *   stringPlus       — SLOW: tạo N String objects, O(N²) chars copied
     *   stringBuilder    — FAST: amortized O(N), single backing char[]
     *   stringJoiner     — FAST: tương đương StringBuilder, cleaner API
     *
     * SA: Java 9+ compiler đã optimize string + trong 1 expression nhưng KHÔNG
     * trong loop. javac -verbose confirms: loop body vẫn tạo new StringBuilder mỗi iter.
     */
    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public static class StringConcatBenchmark {

        @Param({"10", "100", "500"})
        public int iterations;

        @Benchmark
        public String stringPlus() {
            String result = "";
            for (int i = 0; i < iterations; i++) {
                result += "item-" + i + ",";   // BAD: new String object every iteration
            }
            return result;
        }

        @Benchmark
        public String stringBuilder() {
            StringBuilder sb = new StringBuilder(iterations * 10);
            for (int i = 0; i < iterations; i++) {
                sb.append("item-").append(i).append(',');
            }
            return sb.toString();
        }

        @Benchmark
        public String stringJoiner() {
            StringJoiner sj = new StringJoiner(",");
            for (int i = 0; i < iterations; i++) {
                sj.add("item-" + i);
            }
            return sj.toString();
        }

        @Benchmark
        public String streamCollect() {
            // Stream approach — readable but has overhead
            return java.util.stream.IntStream.range(0, iterations)
                .mapToObj(i -> "item-" + i)
                .collect(Collectors.joining(","));
        }
    }

    // =========================================================================
    // BENCHMARK 2 — Dead Code Elimination Demo
    // =========================================================================

    /**
     * Minh họa vấn đề DEAD CODE ELIMINATION — pitfall #1 của benchmark tự viết.
     *
     * Nếu kết quả của computation không được "consume" → JIT biết code là dead
     * → JIT xóa code → benchmark đo 0ns (hoặc rất nhỏ) → kết quả sai hoàn toàn.
     *
     * JMH giải quyết qua Blackhole.consume(result) — trick JIT tin rằng
     * kết quả được sử dụng → không eliminate.
     */
    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public static class DeadCodeBenchmark {

        private double x = Math.PI;

        /**
         * WRONG benchmark — result bị discard → JIT có thể eliminate entire computation.
         * Trên JVM hiện đại với JMH, sẽ được detect, nhưng nếu tự benchmark thì miss.
         */
        @Benchmark
        public void mathSin_discardResult() {
            Math.sin(x);   // result không được return hay consume → dead code candidate
        }

        /**
         * CORRECT — return value forces JVM to compute (JMH captures return value).
         */
        @Benchmark
        public double mathSin_returnResult() {
            return Math.sin(x);    // JMH auto-captures return value
        }

        /**
         * CORRECT — Blackhole.consume() explicit, clearer intent.
         */
        @Benchmark
        public void mathSin_blackhole(Blackhole bh) {
            bh.consume(Math.sin(x));   // Explicit: "I'm using this result"
        }

        /**
         * CONSTANT FOLDING demo: nếu x là constant → JIT pre-computes Math.sin(3.14)
         * tại compile time → mọi call return precomputed value → 0ns.
         * Dùng @State với non-final field để tránh.
         */
        @Benchmark
        public double mathSin_constantFoldingRisk() {
            // WRONG nếu viết: return Math.sin(3.14159); — JIT folds to constant
            return Math.sin(x);   // x từ @State, không thể fold
        }
    }

    // =========================================================================
    // BENCHMARK 3 — Collection Add/Iterate
    // =========================================================================

    /**
     * So sánh hiệu năng thực tế của List implementations.
     *
     * Expected:
     *   addLast:     ArrayList ≈ ArrayDeque >> LinkedList
     *   iterateAll:  ArrayList >> LinkedList (cache locality — L1/L2 cache miss)
     *   addFirst:    ArrayDeque >> ArrayList (O(1) vs O(N) shift) >> LinkedList (alloc)
     *
     * SA: LinkedList thắng chỉ khi: frequent insert vào middle + iterator-based removal.
     * Trong 95% case: ArrayList hoặc ArrayDeque là đúng lựa chọn.
     */
    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public static class CollectionBenchmark {

        @Param({"1000"})
        public int size;

        private List<Integer> arrayList;
        private List<Integer> linkedList;
        private Deque<Integer> arrayDeque;

        @Setup(Level.Invocation)
        public void setup() {
            arrayList  = new ArrayList<>(size);
            linkedList = new LinkedList<>();
            arrayDeque = new ArrayDeque<>(size);
            for (int i = 0; i < size; i++) {
                arrayList.add(i);
                linkedList.add(i);
                arrayDeque.addLast(i);
            }
        }

        @Benchmark
        public void addLast_ArrayList(Blackhole bh) {
            List<Integer> list = new ArrayList<>();
            for (int i = 0; i < size; i++) list.add(i);
            bh.consume(list);
        }

        @Benchmark
        public void addLast_LinkedList(Blackhole bh) {
            List<Integer> list = new LinkedList<>();
            for (int i = 0; i < size; i++) list.add(i);
            bh.consume(list);
        }

        @Benchmark
        public void addLast_ArrayDeque(Blackhole bh) {
            Deque<Integer> deque = new ArrayDeque<>();
            for (int i = 0; i < size; i++) deque.addLast(i);
            bh.consume(deque);
        }

        @Benchmark
        public long iterate_ArrayList(Blackhole bh) {
            long sum = 0;
            for (int v : arrayList) sum += v;
            bh.consume(sum);
            return sum;
        }

        @Benchmark
        public long iterate_LinkedList(Blackhole bh) {
            long sum = 0;
            for (int v : linkedList) sum += v; // cache miss per element!
            bh.consume(sum);
            return sum;
        }
    }

    // =========================================================================
    // BENCHMARK 4 — Stream vs For-loop vs parallelStream
    // =========================================================================

    /**
     * Khi nào parallelStream thực sự nhanh hơn?
     *
     * Expected:
     *   forLoop          — fastest for simple sum (minimal overhead)
     *   sequentialStream — slightly slower (iterator + lambda overhead)
     *   parallelStream   — WINS only at large N with CPU-bound work
     *                      at small N: thread coordination overhead LOSES
     *
     * SA Insight:
     *   • parallelStream threshold: > 10,000 elements với CPU-bound stateless op
     *   • parallelStream dùng ForkJoinPool.commonPool() — shared với toàn app!
     *   • I/O-bound với parallelStream → thread pool starvation, KHÔNG DÙNG
     *   • Stateful parallel ops (sorted, distinct) có synchronization overhead
     */
    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public static class StreamBenchmark {

        @Param({"1000", "100000"})
        public int size;

        private int[] data;

        @Setup(Level.Trial)
        public void setup() {
            data = new int[size];
            Random rng = new Random(42);
            for (int i = 0; i < size; i++) data[i] = rng.nextInt(1000);
        }

        @Benchmark
        public long forLoop(Blackhole bh) {
            long sum = 0;
            for (int v : data) sum += v;
            bh.consume(sum);
            return sum;
        }

        @Benchmark
        public long sequentialStream(Blackhole bh) {
            long sum = Arrays.stream(data).asLongStream().sum();
            bh.consume(sum);
            return sum;
        }

        @Benchmark
        public long parallelStream(Blackhole bh) {
            long sum = Arrays.stream(data).parallel().asLongStream().sum();
            bh.consume(sum);
            return sum;
        }

        // CPU-intensive operation to show parallel stream winning
        @Benchmark
        public double parallelStream_cpuIntensive(Blackhole bh) {
            double result = Arrays.stream(data)
                .parallel()
                .mapToDouble(v -> Math.sqrt(v) * Math.log(v + 1) * Math.sin(v))
                .sum();
            bh.consume(result);
            return result;
        }

        @Benchmark
        public double sequentialStream_cpuIntensive(Blackhole bh) {
            double result = Arrays.stream(data)
                .mapToDouble(v -> Math.sqrt(v) * Math.log(v + 1) * Math.sin(v))
                .sum();
            bh.consume(result);
            return result;
        }
    }

    // =========================================================================
    // BENCHMARK 5 — Map Lookup Performance
    // =========================================================================

    /**
     * Map lookup benchmark — quan trọng cho caching, routing, config lookup.
     *
     * Expected:
     *   HashMap     — O(1) average, fastest overall
     *   TreeMap     — O(log N) always, slower but sorted
     *   LinkedHashMap — O(1) like HashMap + insertion order (small overhead)
     *   EnumMap     — FASTEST khi key là enum (array-backed, no hash collision)
     *
     * SA: Router/dispatcher dùng Map<String, Handler> → HashMap win.
     *     Range query / sorted key → TreeMap.
     *     LRU Cache → LinkedHashMap(accessOrder).
     *     Enum-keyed config → EnumMap (zero boxing, zero hashing).
     */
    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public static class MapLookupBenchmark {

        enum Status { PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED }

        private Map<String, String>  hashMap;
        private Map<String, String>  treeMap;
        private Map<String, String>  linkedHashMap;
        private Map<Status, String>  enumMap;

        private String[]             keys;
        private Status[]             enumKeys;

        @Param({"100", "10000"})
        public int mapSize;

        @Setup(Level.Trial)
        public void setup() {
            hashMap       = new HashMap<>(mapSize);
            treeMap       = new TreeMap<>();
            linkedHashMap = new LinkedHashMap<>(mapSize);
            enumMap       = new EnumMap<>(Status.class);

            for (int i = 0; i < mapSize; i++) {
                String key = "key-" + i;
                hashMap.put(key, "value-" + i);
                treeMap.put(key, "value-" + i);
                linkedHashMap.put(key, "value-" + i);
            }
            for (Status s : Status.values()) enumMap.put(s, s.name().toLowerCase());

            // Random lookup keys (mix of hit and miss)
            Random rng = new Random(42);
            keys = new String[1000];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = "key-" + rng.nextInt(mapSize * 2); // 50% miss rate
            }
            enumKeys = Status.values();
        }

        @Benchmark
        public void lookup_HashMap(Blackhole bh) {
            for (String key : keys) bh.consume(hashMap.get(key));
        }

        @Benchmark
        public void lookup_TreeMap(Blackhole bh) {
            for (String key : keys) bh.consume(treeMap.get(key));
        }

        @Benchmark
        public void lookup_LinkedHashMap(Blackhole bh) {
            for (String key : keys) bh.consume(linkedHashMap.get(key));
        }

        @Benchmark
        public void lookup_EnumMap(Blackhole bh) {
            for (Status s : enumKeys) bh.consume(enumMap.get(s));
        }
    }

    // =========================================================================
    // Explanatory output
    // =========================================================================

    static void printBenchmarkConcepts() {
        System.out.println("""
            ┌─────────────────────────────────────────────────────────────────┐
            │                JMH BENCHMARK — KEY CONCEPTS                    │
            ├─────────────────────────────────────────────────────────────────┤
            │  @Warmup(iterations=5, time=1s)                                │
            │    → JVM chạy 5 giây đầu để JIT đạt C2 compiled steady state  │
            │    → Không đo giai đoạn này                                    │
            │                                                                  │
            │  @Measurement(iterations=5, time=1s)                           │
            │    → Chỉ đo trong 5 giây tiếp theo (sau warm-up)               │
            │                                                                  │
            │  @Fork(3)                                                       │
            │    → Chạy 3 JVM process riêng → loại bỏ JIT profile pollution  │
            │    → Demo này dùng fork=1 để tiết kiệm thời gian               │
            │                                                                  │
            │  @State(Scope.Thread)                                           │
            │    → Mỗi thread có instance riêng → không có contention        │
            │    → Scope.Benchmark = shared, Scope.Group = per group          │
            │                                                                  │
            │  Blackhole.consume(result)                                      │
            │    → Ngăn JIT xóa code vì "kết quả không được dùng"            │
            │                                                                  │
            │  BenchmarkMode.AverageTime (μs/op)                             │
            │    → Thời gian trung bình mỗi lần gọi — tốt cho latency        │
            └─────────────────────────────────────────────────────────────────┘
            """);
    }

    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 5.1 — JMH Benchmark Insights");
        System.out.println("=".repeat(70));
        System.out.println("""
            KẾT QUẢ BENCHMARK ĐIỂN HÌNH (thực tế phụ thuộc hardware):
            ┌──────────────────────────────────────┬──────────────────────┐
            │ String concat (N=100 iterations)     │ Tốc độ tương đối    │
            ├──────────────────────────────────────┼──────────────────────┤
            │ String + (plus operator)             │ 1x (baseline, chậm) │
            │ StringBuilder.append()               │ ~20-50x nhanh hơn   │
            │ StringJoiner / Collectors.joining()  │ ~15-40x nhanh hơn   │
            └──────────────────────────────────────┴──────────────────────┘

            ┌──────────────────────────────────────┬──────────────────────┐
            │ Collection iterate (N=1000)          │ Ghi chú             │
            ├──────────────────────────────────────┼──────────────────────┤
            │ ArrayList                            │ Cache-friendly, O(1) │
            │ LinkedList                           │ Pointer chasing, chậm│
            │ ArrayDeque                           │ Tốt cho queue ops   │
            └──────────────────────────────────────┴──────────────────────┘

            ┌──────────────────────────────────────┬──────────────────────┐
            │ parallelStream win condition         │                      │
            ├──────────────────────────────────────┼──────────────────────┤
            │ N < 10,000 elements                  │ Sequential WINS      │
            │ N > 100,000 + CPU-bound stateless    │ Parallel WINS ~Nx    │
            │ I/O-bound work                       │ NEVER use parallel   │
            └──────────────────────────────────────┴──────────────────────┘

            SA CHECKLIST KHI BENCHMARK:
              ✓ Warm-up đủ: ít nhất 5 iterations × 1 giây
              ✓ Dùng Blackhole.consume() hoặc return result
              ✓ @State field là non-final để tránh constant folding
              ✓ @Fork(3+) cho production benchmark
              ✓ Test với realistic data size — không dùng N=10 rồi extrapolate
              ✓ Benchmark trên hardware giống production (không laptop vs server)
              ✓ Measure p99 latency (SampleTime mode), không chỉ average
              ✓ GC pause ảnh hưởng variance — xem stddev trong kết quả

            RULE:
              "Đừng optimize cho đến khi có benchmark proof.
               Đừng tin benchmark trừ khi JMH framework viết nó."
            """);
    }
}
