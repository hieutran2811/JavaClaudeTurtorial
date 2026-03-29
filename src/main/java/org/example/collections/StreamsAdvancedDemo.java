package org.example.collections;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.function.*;
import java.util.stream.*;

/**
 * ============================================================
 * BÀI 3.4 — Streams nâng cao
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Stream pipeline internals — lazy evaluation, short-circuit
 *   2. Spliterator — cách Stream chia data cho parallel processing
 *   3. Parallel stream — khi nào nhanh hơn, khi nào nguy hiểm
 *   4. Collector internals — tự viết custom Collector
 *   5. Collector nâng cao: teeing, flatMapping, groupingBy phức tạp
 *   6. Stream pitfalls thực tế
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.collections.StreamsAdvancedDemo"
 * ============================================================
 */
public class StreamsAdvancedDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 3.4: Streams nâng cao ===\n");

        demo1_LazyEvaluationAndShortCircuit();
        demo2_SpliteratorInternals();
        demo3_ParallelStream();
        demo4_CustomCollector();
        demo5_AdvancedCollectors();
        demo6_StreamPitfalls();

        System.out.println("\n=== KẾT THÚC BÀI 3.4 — MODULE 3 HOÀN THÀNH ===");
    }

    // ================================================================
    // DEMO 1: Lazy Evaluation & Short-Circuit
    // ================================================================

    /**
     * Stream pipeline = SOURCE → INTERMEDIATE ops → TERMINAL op
     *
     * LAZY EVALUATION: Intermediate operations (filter, map, ...) KHÔNG chạy
     *   cho đến khi terminal operation (collect, forEach, findFirst, ...) được gọi.
     *   → Chỉ xử lý đúng số phần tử cần thiết, không hơn.
     *
     * SHORT-CIRCUIT: Một số terminal ops dừng sớm khi đã có đủ kết quả:
     *   findFirst(), findAny(), anyMatch(), allMatch(), noneMatch(), limit()
     *   → Không cần xử lý toàn bộ stream
     *
     * VERTICAL vs HORIZONTAL processing:
     *   Collection iteration: xử lý TẤT CẢ phần tử qua filter() rồi mới map()
     *   Stream pipeline:      mỗi phần tử đi QUA TOÀN BỘ pipeline trước khi sang phần tử tiếp
     *   → Stream giảm số lần duyệt dữ liệu (loop fusion)
     *
     * SA INSIGHT: Lazy evaluation cho phép stream trên dữ liệu vô hạn (infinite stream).
     *   Stream.iterate(), Stream.generate() → vô hạn nhưng chỉ xử lý đến khi limit/findFirst.
     */
    static void demo1_LazyEvaluationAndShortCircuit() {
        System.out.println("--- DEMO 1: Lazy Evaluation & Short-Circuit ---");

        // Minh hoạ lazy evaluation — filter/map không chạy cho đến khi terminal
        System.out.println("  Lazy: chỉ xử lý phần tử cần thiết:");
        List<Integer> nums = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        Optional<Integer> first = nums.stream()
            .filter(n -> { System.out.print("  filter(" + n + ") "); return n % 2 == 0; })
            .map(n    -> { System.out.print("→ map(" + n + ") ");    return n * n; })
            .findFirst(); // Short-circuit: dừng ngay khi tìm thấy phần tử đầu tiên
        System.out.println("\n  findFirst() kết quả: " + first.orElse(-1));
        System.out.println("  → Chỉ xử lý đến phần tử 2, không cần xử lý 3..10\n");

        // Infinite stream với lazy + limit
        System.out.println("  Infinite stream — Stream.iterate:");
        List<Integer> fibs = Stream.iterate(new int[]{0, 1}, f -> new int[]{f[1], f[0] + f[1]})
            .limit(10)
            .map(f -> f[0])
            .collect(Collectors.toList());
        System.out.println("  10 số Fibonacci: " + fibs);

        // Stream.generate — random, sensor data, etc.
        List<Double> randoms = Stream.generate(Math::random)
            .limit(5)
            .map(d -> Math.round(d * 100.0) / 100.0)
            .collect(Collectors.toList());
        System.out.println("  5 random numbers: " + randoms);

        // allMatch short-circuit
        System.out.println("\n  allMatch short-circuit:");
        boolean allPositive = Stream.of(1, 2, -3, 4, 5)
            .peek(n -> System.out.print("  check(" + n + ") "))
            .allMatch(n -> n > 0); // Dừng tại -3
        System.out.println("\n  allPositive: " + allPositive + " (dừng sớm tại -3)\n");
    }

    // ================================================================
    // DEMO 2: Spliterator — Cách Stream chia data
    // ================================================================

    /**
     * SPLITERATOR = Splittable Iterator — iterator có thể chia đôi
     *   Là nền tảng để parallel stream chia công việc cho nhiều thread.
     *
     * Spliterator có 2 việc chính:
     *   tryAdvance(Consumer): xử lý 1 phần tử tiếp theo (như iterator.next)
     *   trySplit():           chia đôi → trả về Spliterator cho nửa đầu
     *                        → parallel stream gọi trySplit() nhiều lần để chia cho ForkJoinPool
     *
     * CHARACTERISTICS — cờ báo cho Stream optimizer:
     *   ORDERED   — thứ tự phần tử có ý nghĩa (List → có, HashSet → không)
     *   SIZED     — biết trước số phần tử (List → có, Stream.generate → không)
     *   DISTINCT  — không có phần tử trùng (Set → có)
     *   SORTED    — phần tử đã được sắp xếp (TreeSet → có)
     *   IMMUTABLE — source không thay đổi (List.of → có)
     *   NONNULL   — không có null (IntStream → có)
     *   SUBSIZED  — sau trySplit(), cả 2 nửa đều biết size
     *
     * SA INSIGHT: Characteristics ảnh hưởng đến optimization:
     *   SIZED → Stream biết trước kết quả size → tránh resize trung gian
     *   ORDERED + parallel → phải duy trì thứ tự → giảm parallelism
     *   unordered() hint → cho phép stream bỏ thứ tự → tăng parallelism
     */
    static void demo2_SpliteratorInternals() {
        System.out.println("--- DEMO 2: Spliterator Internals ---");

        List<Integer> list = List.of(1, 2, 3, 4, 5, 6, 7, 8);
        Spliterator<Integer> spliterator = list.spliterator();

        System.out.println("  List spliterator characteristics:");
        System.out.println("  estimateSize: " + spliterator.estimateSize());
        System.out.println("  ORDERED:   " + has(spliterator, Spliterator.ORDERED));
        System.out.println("  SIZED:     " + has(spliterator, Spliterator.SIZED));
        System.out.println("  SUBSIZED:  " + has(spliterator, Spliterator.SUBSIZED));
        System.out.println("  IMMUTABLE: " + has(spliterator, Spliterator.IMMUTABLE));

        // trySplit — chia đôi
        Spliterator<Integer> firstHalf = spliterator.trySplit();
        System.out.println("\n  Sau trySplit():");
        System.out.print("  firstHalf: "); firstHalf.forEachRemaining(n -> System.out.print(n + " "));
        System.out.println("  (size=" + firstHalf.estimateSize() + ")");
        System.out.print("  secondHalf: "); spliterator.forEachRemaining(n -> System.out.print(n + " "));
        System.out.println("  (split đôi cho parallel stream dùng)");

        // Custom Spliterator — stream trên range số [from, to]
        System.out.println("\n  Custom RangeSpliterator [1..16]:");
        long count = StreamSupport.stream(new RangeSpliterator(1, 17), true) // true = parallel
            .filter(n -> n % 2 == 0)
            .count();
        System.out.println("  Số chẵn trong [1,16]: " + count);

        // Characteristics ảnh hưởng optimize
        System.out.println("\n  Characteristics optimization:");
        Set<Integer> set = new HashSet<>(List.of(1, 2, 3, 4, 5));
        Spliterator<Integer> setSplit = set.spliterator();
        System.out.println("  HashSet ORDERED: " + has(setSplit, Spliterator.ORDERED)
                + " → parallel stream trên Set nhanh hơn List (không cần giữ thứ tự)");
        System.out.println("  HashSet DISTINCT: " + has(setSplit, Spliterator.DISTINCT)
                + " → distinct() op là no-op với Set\n");
    }

    static boolean has(Spliterator<?> sp, int characteristic) {
        return sp.hasCharacteristics(characteristic);
    }

    /** Custom Spliterator cho integer range — minh hoạ cách chia đôi */
    static class RangeSpliterator implements Spliterator<Integer> {
        private int current, end;
        RangeSpliterator(int from, int to) { this.current = from; this.end = to; }

        @Override public boolean tryAdvance(Consumer<? super Integer> action) {
            if (current < end) { action.accept(current++); return true; }
            return false;
        }

        @Override public Spliterator<Integer> trySplit() {
            int mid = (current + end) / 2;
            if (mid <= current) return null; // Không chia được nữa
            RangeSpliterator prefix = new RangeSpliterator(current, mid);
            this.current = mid;
            return prefix;
        }

        @Override public long estimateSize()    { return end - current; }
        @Override public int characteristics()  {
            return ORDERED | SIZED | SUBSIZED | IMMUTABLE | NONNULL | DISTINCT;
        }
    }

    // ================================================================
    // DEMO 3: Parallel Stream — Khi nào nhanh, khi nào nguy hiểm
    // ================================================================

    /**
     * Parallel stream dùng ForkJoinPool.commonPool() bên dưới.
     * Mặc định: commonPool có nCPU - 1 thread.
     *
     * KHI NÀO PARALLEL STREAM NHANH HƠN:
     *   ✓ Data lớn (N > 10.000)
     *   ✓ CPU-bound computation (không I/O, không blocking)
     *   ✓ Source dễ chia (ArrayList, array — trySplit hiệu quả)
     *   ✓ Independent operations (stateless, không shared state)
     *   ✓ Nhiều CPU cores (4+ cores thực sự)
     *
     * KHI NÀO PARALLEL STREAM CHẬM HƠN / NGUY HIỂM:
     *   ✗ Data nhỏ — overhead fork/join > benefit
     *   ✗ Stateful operations: sorted(), distinct(), limit() → cần synchronize
     *   ✗ Blocking I/O trong pipeline → block commonPool thread → starve toàn app
     *   ✗ Shared mutable state → race condition (accumulate vào non-thread-safe collection)
     *   ✗ Source khó chia: LinkedList, Stream.iterate → trySplit kém → không song song thực sự
     *
     * ORDERED + parallel: giữ thứ tự kết quả = phải buffer → tốn memory & time
     *   → Dùng unordered() nếu không cần thứ tự → tăng tốc
     *
     * CUSTOM ForkJoinPool: tách parallel stream khỏi commonPool
     *   new ForkJoinPool(n).submit(() -> list.parallelStream()...).get()
     */
    static void demo3_ParallelStream() throws Exception {
        System.out.println("--- DEMO 3: Parallel Stream ---");

        int N = 5_000_000;
        List<Integer> data = new ArrayList<>(N);
        for (int i = 0; i < N; i++) data.add(i);

        // Sequential vs Parallel — CPU-bound (sum)
        long start = System.currentTimeMillis();
        long seqSum = data.stream().mapToLong(Integer::longValue).sum();
        long seqTime = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        long parSum = data.parallelStream().mapToLong(Integer::longValue).sum();
        long parTime = System.currentTimeMillis() - start;

        System.out.println("  Sum " + N + " integers (CPU-bound):");
        System.out.printf("  Sequential:  %dms  (sum=%,d)%n", seqTime, seqSum);
        System.out.printf("  Parallel:    %dms  (%s)%n", parTime,
                parTime < seqTime ? "nhanh hơn ✓" : "không nhanh hơn (overhead > benefit)");

        // Stateful operation — sorted() + parallel phải buffer kết quả
        start = System.currentTimeMillis();
        List<Integer> parSorted = data.parallelStream()
            .filter(n -> n % 3 == 0)
            .sorted()    // Stateful → phải collect rồi mới sort → giảm benefit của parallel
            .limit(10)
            .collect(Collectors.toList());
        System.out.println("  parallel + sorted (stateful): " + (System.currentTimeMillis() - start) + "ms → overhead");

        // Nguy hiểm: shared mutable state trong parallel stream
        System.out.println("\n  Nguy hiểm: shared mutable state:");
        List<Integer> unsafe = new ArrayList<>();
        try {
            IntStream.range(0, 1000).parallel().forEach(unsafe::add); // Race condition!
        } catch (Exception e) {
            System.out.println("  Exception: " + e.getClass().getSimpleName());
        }
        System.out.println("  ArrayList size sau parallel forEach: " + unsafe.size()
                + " (kỳ vọng 1000 — thực tế không đúng vì race condition!)");

        // Fix: dùng collect() thay forEach với mutable container
        List<Integer> safe = IntStream.range(0, 1000).parallel()
            .boxed()
            .collect(Collectors.toList()); // Thread-safe collection
        System.out.println("  collect() fix: size = " + safe.size() + " ✓");

        // Custom ForkJoinPool để tách khỏi commonPool
        System.out.println("\n  Custom ForkJoinPool (4 threads, tách khỏi commonPool):");
        ForkJoinPool customPool = new ForkJoinPool(4);
        long result = customPool.submit(() ->
            data.parallelStream().mapToLong(Integer::longValue).sum()
        ).get();
        customPool.shutdown();
        System.out.printf("  Sum = %,d (dùng pool riêng, không ảnh hưởng commonPool)%n%n", result);
    }

    // ================================================================
    // DEMO 4: Custom Collector
    // ================================================================

    /**
     * Collector<T, A, R> có 5 thành phần:
     *
     *   supplier()     → Supplier<A>          : tạo mutable container trống
     *   accumulator()  → BiConsumer<A, T>     : thêm 1 phần tử vào container
     *   combiner()     → BinaryOperator<A>    : gộp 2 container (dùng trong parallel)
     *   finisher()     → Function<A, R>       : chuyển container thành kết quả cuối
     *   characteristics() → Set<Characteristics>:
     *       IDENTITY_FINISH  — finisher là identity (A == R), bỏ qua bước finisher
     *       CONCURRENT       — accumulator có thể gọi từ nhiều thread cùng lúc
     *       UNORDERED        — thứ tự phần tử không quan trọng
     *
     * Flow:
     *   Sequential:  supplier() → accumulate(e1) → accumulate(e2) → ... → finisher()
     *   Parallel:    supplier()×N → accumulate(partition) → combine×(N-1) → finisher()
     *
     * SA INSIGHT: Custom Collector tái sử dụng được, composable, type-safe.
     *   Các framework build-in collector như Collectors.toList() cũng implement interface này.
     *   Khi cần collect phức tạp nhiều lần → viết Collector thay vì copy-paste logic.
     */
    static void demo4_CustomCollector() {
        System.out.println("--- DEMO 4: Custom Collector ---");

        List<Transaction> transactions = List.of(
            new Transaction("Alice",   "DEBIT",  150.0),
            new Transaction("Bob",     "CREDIT", 200.0),
            new Transaction("Alice",   "CREDIT",  50.0),
            new Transaction("Charlie", "DEBIT",  300.0),
            new Transaction("Bob",     "DEBIT",   75.0),
            new Transaction("Alice",   "DEBIT",  100.0),
            new Transaction("Charlie", "CREDIT", 500.0)
        );

        // Custom Collector 1: Statistics per user
        Map<String, TransactionStats> stats = transactions.stream()
            .collect(new TransactionStatsCollector());

        System.out.println("  Transaction stats per user:");
        stats.forEach((user, s) -> System.out.printf(
            "  %-10s count=%d  debit=%.1f  credit=%.1f  net=%.1f%n",
            user, s.count, s.totalDebit, s.totalCredit, s.net()));

        // Custom Collector 2: Sliding window (batch mỗi N phần tử)
        List<Integer> numbers = IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toList());
        List<List<Integer>> batches = numbers.stream()
            .collect(BatchCollector.ofSize(3));
        System.out.println("\n  Batch collector (size=3): " + batches);

        // Custom Collector 3: Running total (cumulative sum)
        List<Double> amounts = List.of(100.0, 50.0, 200.0, 75.0, 150.0);
        List<Double> runningTotal = amounts.stream()
            .collect(RunningTotalCollector.get());
        System.out.println("  Running total: " + runningTotal + "\n");
    }

    record Transaction(String user, String type, double amount) {}

    static class TransactionStats {
        int count; double totalDebit, totalCredit;
        void add(Transaction t) {
            count++;
            if ("DEBIT".equals(t.type())) totalDebit += t.amount();
            else totalCredit += t.amount();
        }
        TransactionStats merge(TransactionStats other) {
            count += other.count; totalDebit += other.totalDebit; totalCredit += other.totalCredit;
            return this;
        }
        double net() { return totalCredit - totalDebit; }
    }

    /** Custom Collector: group transactions thành stats per user */
    static class TransactionStatsCollector
            implements Collector<Transaction, Map<String, TransactionStats>, Map<String, TransactionStats>> {

        @Override public Supplier<Map<String, TransactionStats>> supplier() {
            return HashMap::new;
        }

        @Override public BiConsumer<Map<String, TransactionStats>, Transaction> accumulator() {
            return (map, t) -> map.computeIfAbsent(t.user(), k -> new TransactionStats()).add(t);
        }

        @Override public BinaryOperator<Map<String, TransactionStats>> combiner() {
            return (map1, map2) -> {
                map2.forEach((k, v) -> map1.merge(k, v, TransactionStats::merge));
                return map1;
            };
        }

        @Override public Function<Map<String, TransactionStats>, Map<String, TransactionStats>> finisher() {
            return Function.identity();
        }

        @Override public Set<Characteristics> characteristics() {
            return Set.of(Characteristics.IDENTITY_FINISH); // finisher là identity → bỏ qua
        }
    }

    /** Collector: chia thành batch size N */
    static class BatchCollector<T> implements Collector<T, List<List<T>>, List<List<T>>> {
        private final int batchSize;
        BatchCollector(int size) { this.batchSize = size; }

        static <T> BatchCollector<T> ofSize(int size) { return new BatchCollector<>(size); }

        @Override public Supplier<List<List<T>>> supplier() {
            return () -> { List<List<T>> r = new ArrayList<>(); r.add(new ArrayList<>()); return r; }; }

        @Override public BiConsumer<List<List<T>>, T> accumulator() {
            return (batches, item) -> {
                if (batches.get(batches.size() - 1).size() >= batchSize)
                    batches.add(new ArrayList<>());
                batches.get(batches.size() - 1).add(item);
            };
        }

        @Override public BinaryOperator<List<List<T>>> combiner() {
            return (a, b) -> { a.addAll(b); return a; };
        }

        @Override public Function<List<List<T>>, List<List<T>>> finisher() { return Function.identity(); }

        @Override public Set<Characteristics> characteristics() { return Set.of(Characteristics.IDENTITY_FINISH); }
    }

    /** Collector: running total (cumulative sum) */
    static class RunningTotalCollector implements Collector<Double, List<Double>, List<Double>> {
        static RunningTotalCollector get() { return new RunningTotalCollector(); }

        @Override public Supplier<List<Double>> supplier() { return ArrayList::new; }

        @Override public BiConsumer<List<Double>, Double> accumulator() {
            return (list, val) -> {
                double prev = list.isEmpty() ? 0 : list.get(list.size() - 1);
                list.add(prev + val);
            };
        }

        @Override public BinaryOperator<List<Double>> combiner() {
            return (a, b) -> { a.addAll(b); return a; }; // sequential only
        }

        @Override public Function<List<Double>, List<Double>> finisher() { return Function.identity(); }

        @Override public Set<Characteristics> characteristics() { return Set.of(Characteristics.IDENTITY_FINISH); }
    }

    // ================================================================
    // DEMO 5: Advanced Collectors — teeing, groupingBy phức tạp
    // ================================================================

    /**
     * Collectors phức tạp thực tế:
     *
     *   groupingBy(classifier, downstream)  — group rồi apply collector khác
     *   partitioningBy(predicate, downstream) — chia 2 nhóm true/false
     *   teeing(c1, c2, merger)              — apply 2 collector song song, gộp kết quả (Java 12)
     *   flatMapping(mapper, downstream)     — flat trước khi collect (Java 9)
     *   filtering(predicate, downstream)    — filter rồi collect (Java 9)
     *   collectingAndThen(downstream, fn)   — apply function sau khi collect
     *   mapping(fn, downstream)             — transform rồi collect (như map+collect)
     */
    static void demo5_AdvancedCollectors() {
        System.out.println("--- DEMO 5: Advanced Collectors ---");

        List<Employee> employees = List.of(
            new Employee("Alice",   "Engineering", 95000, List.of("Java", "Kotlin")),
            new Employee("Bob",     "Engineering", 85000, List.of("Python", "Java")),
            new Employee("Charlie", "Marketing",   70000, List.of("Excel", "PowerBI")),
            new Employee("Dave",    "Engineering", 110000, List.of("Java", "Go", "Rust")),
            new Employee("Eve",     "Marketing",   75000, List.of("Tableau", "SQL")),
            new Employee("Frank",   "HR",          65000, List.of("Excel")),
            new Employee("Grace",   "Engineering", 92000, List.of("Java", "Scala"))
        );

        // groupingBy với downstream counting
        Map<String, Long> countByDept = employees.stream()
            .collect(Collectors.groupingBy(Employee::dept, Collectors.counting()));
        System.out.println("  Count by dept: " + countByDept);

        // groupingBy với downstream averagingInt
        Map<String, Double> avgSalaryByDept = employees.stream()
            .collect(Collectors.groupingBy(Employee::dept,
                Collectors.averagingInt(Employee::salary)));
        avgSalaryByDept.forEach((dept, avg) ->
            System.out.printf("  Avg salary %-12s: $%.0f%n", dept, avg));

        // groupingBy nested — dept → (salary tier → names)
        Map<String, Map<String, List<String>>> nestedGroup = employees.stream()
            .collect(Collectors.groupingBy(Employee::dept,
                Collectors.groupingBy(
                    e -> e.salary() >= 90000 ? "Senior" : "Junior",
                    Collectors.mapping(Employee::name, Collectors.toList())
                )));
        System.out.println("\n  Nested groupBy (dept → tier → names): " + nestedGroup);

        // flatMapping — collect tất cả skills (bỏ duplicate)
        Set<String> allSkills = employees.stream()
            .collect(Collectors.flatMapping(
                e -> e.skills().stream(),
                Collectors.toSet()
            ));
        System.out.println("  All unique skills (flatMapping): " + new TreeSet<>(allSkills));

        // teeing — đồng thời tính min và max salary (Java 12)
        record SalaryRange(int min, int max) {}
        SalaryRange range = employees.stream()
            .collect(Collectors.teeing(
                Collectors.minBy(Comparator.comparingInt(Employee::salary)),
                Collectors.maxBy(Comparator.comparingInt(Employee::salary)),
                (min, max) -> new SalaryRange(
                    min.map(Employee::salary).orElse(0),
                    max.map(Employee::salary).orElse(0))
            ));
        System.out.println("  Salary range (teeing): min=$" + range.min() + " max=$" + range.max());

        // partitioningBy — senior vs junior
        Map<Boolean, List<String>> partition = employees.stream()
            .collect(Collectors.partitioningBy(
                e -> e.salary() >= 90000,
                Collectors.mapping(Employee::name, Collectors.toList())
            ));
        System.out.println("  Senior (salary≥90k): " + partition.get(true));
        System.out.println("  Junior (salary<90k): " + partition.get(false));

        // collectingAndThen — toUnmodifiableList
        List<String> topEarners = employees.stream()
            .filter(e -> e.salary() > 90000)
            .map(Employee::name)
            .collect(Collectors.collectingAndThen(
                Collectors.toList(),
                Collections::unmodifiableList // wrap thành unmodifiable sau khi collect
            ));
        System.out.println("  Top earners (unmodifiable): " + topEarners + "\n");
    }

    record Employee(String name, String dept, int salary, List<String> skills) {}

    // ================================================================
    // DEMO 6: Stream Pitfalls thực tế
    // ================================================================

    /**
     * PITFALL 1 — Stream tái sử dụng:
     *   Stream chỉ dùng được 1 lần. Sau khi terminal op → stream đã closed.
     *   IllegalStateException: "stream has already been operated upon or closed"
     *
     * PITFALL 2 — forEach thay collect (mutable accumulation):
     *   stream.forEach(list::add)  → không thread-safe với parallel stream
     *   → Dùng collect(Collectors.toList()) thay thế
     *
     * PITFALL 3 — Side effects trong intermediate ops (non-interference):
     *   Modify source collection trong khi stream đang chạy → ConcurrentModificationException
     *   hoặc undefined behavior
     *
     * PITFALL 4 — Optional.get() không có isPresent():
     *   stream.filter(...).findFirst().get()  → NoSuchElementException nếu empty
     *   → Dùng orElse(), orElseGet(), orElseThrow(), ifPresent()
     *
     * PITFALL 5 — Boxing overhead trong numerical streams:
     *   stream.mapToInt() thay vì stream.map(Integer::intValue)
     *   IntStream/LongStream/DoubleStream tránh autoboxing → nhanh hơn với large data
     *
     * PITFALL 6 — Parallel stream trên IO — block commonPool
     */
    static void demo6_StreamPitfalls() {
        System.out.println("--- DEMO 6: Stream Pitfalls ---");

        // PITFALL 1: Stream tái sử dụng
        Stream<String> stream = Stream.of("a", "b", "c");
        stream.forEach(s -> {}); // Terminal op — stream đã closed
        try {
            stream.count();       // Dùng lại → Exception
        } catch (IllegalStateException e) {
            System.out.println("  Pitfall 1 — Stream tái sử dụng: " + e.getMessage());
        }

        // PITFALL 4: Optional.get() không an toàn
        List<Integer> empty = List.of();
        try {
            int val = empty.stream().filter(n -> n > 10).findFirst().get(); // Nguy hiểm!
        } catch (NoSuchElementException e) {
            System.out.println("  Pitfall 4 — Optional.get() trên empty: NoSuchElementException");
        }
        // Fix: orElse / orElseGet / orElseThrow
        int safeVal = empty.stream().filter(n -> n > 10)
            .findFirst()
            .orElse(-1);
        System.out.println("  Fix: orElse(-1) = " + safeVal);

        // PITFALL 5: Boxing overhead — IntStream vs Stream<Integer>
        int N = 2_000_000;
        List<Integer> list = new ArrayList<>(N);
        for (int i = 0; i < N; i++) list.add(i);

        long start = System.nanoTime();
        long sum1 = list.stream().mapToLong(Integer::longValue).sum();  // unboxing
        long boxedTime = System.nanoTime() - start;

        start = System.nanoTime();
        long sum2 = IntStream.range(0, N).asLongStream().sum();  // primitive, no boxing
        long primitiveTime = System.nanoTime() - start;

        System.out.printf("%n  Pitfall 5 — Boxing overhead (%,d elements):%n", N);
        System.out.printf("  Stream<Integer>.mapToLong: %,dns%n", boxedTime);
        System.out.printf("  IntStream (no boxing):     %,dns  (%.1fx nhanh hơn)%n",
                primitiveTime, (double) boxedTime / primitiveTime);

        // PITFALL 3: Modify source khi đang stream
        List<String> mutable = new ArrayList<>(List.of("a", "b", "c"));
        try {
            mutable.stream().forEach(s -> {
                if ("b".equals(s)) mutable.add("d"); // Modify source → CME
            });
        } catch (ConcurrentModificationException e) {
            System.out.println("\n  Pitfall 3 — Modify source khi stream: ConcurrentModificationException");
            System.out.println("  Fix: collect kết quả ra list mới, sau đó mới modify source");
        }

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 3.4 ===");
        System.out.println("  ✓ Lazy evaluation: intermediate ops không chạy cho đến terminal");
        System.out.println("  ✓ Spliterator: trySplit() chia data cho parallel — source dễ chia thì lợi");
        System.out.println("  ✓ Parallel stream: CPU-bound + large data + stateless = win");
        System.out.println("  ✓                  stateful ops + I/O + small data = overhead");
        System.out.println("  ✓ Custom Collector: supplier → accumulate → combine → finisher");
        System.out.println("  ✓ teeing/flatMapping/groupingBy nested — powerful composable collectors");
        System.out.println("  ✓ Pitfalls: stream reuse, Optional.get(), boxing overhead, source modify");

        System.out.println();
        System.out.println("=== MODULE 3 HOÀN THÀNH ===");
        System.out.println("  3.1 Collection Internals  → HashMap, ArrayList, TreeMap, LRU");
        System.out.println("  3.2 Concurrent Collections → ConcurrentHashMap, COW, BlockingQueue");
        System.out.println("  3.3 Generics Advanced     → Erasure, PECS, wildcards, bounds");
        System.out.println("  3.4 Streams Advanced      → Lazy, Spliterator, Parallel, Collector");
        System.out.println("  → Bài tiếp: Module 4 — Design Patterns thực chiến");
    }
}
