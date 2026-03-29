package org.example.collections;

import java.util.*;
import java.util.stream.IntStream;

/**
 * ============================================================
 * BÀI 3.1 — Collection Internals
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. HashMap: hash function, bucket, collision, treeify, rehashing
 *   2. ArrayList: capacity growth, amortized O(1), Arrays.copyOf cost
 *   3. LinkedList: node overhead, cache miss — tại sao thường thua ArrayList
 *   4. TreeMap vs LinkedHashMap — khi nào dùng cái nào
 *   5. Chọn đúng collection cho từng bài toán
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.collections.CollectionInternalsDemo"
 * ============================================================
 */
public class CollectionInternalsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 3.1: Collection Internals ===\n");

        demo1_HashMapInternals();
        demo2_HashMapPerformancePitfalls();
        demo3_ArrayListInternals();
        demo4_ArrayListVsLinkedList();
        demo5_TreeMapVsLinkedHashMap();
        demo6_CollectionChooser();

        System.out.println("\n=== KẾT THÚC BÀI 3.1 ===");
    }

    // ================================================================
    // DEMO 1: HashMap Internals — Bên trong HashMap hoạt động thế nào
    // ================================================================

    /**
     * CẤU TRÚC HashMap (Java 8+):
     *   - Một mảng Node[] gọi là "table" (bucket array)
     *   - Mỗi phần tử mảng là một "bucket" (có thể null, 1 node, hoặc chuỗi linked list/tree)
     *   - Default initial capacity: 16 buckets
     *   - Default load factor: 0.75 (rehash khi size > capacity × 0.75)
     *
     * KHI PUT(key, value):
     *   1. Tính hash: hash = key.hashCode() ^ (h >>> 16)  (spread high bits)
     *   2. Tìm bucket: index = hash & (capacity - 1)       (bitwise AND — nhanh hơn modulo)
     *   3. Nếu bucket trống → đặt vào
     *   4. Nếu bucket có sẵn → so sánh key bằng equals():
     *      - Trùng key → overwrite value
     *      - Khác key → COLLISION → thêm vào linked list (chaining)
     *   5. Nếu linked list dài ≥ 8 → chuyển thành Red-Black Tree (TREEIFY_THRESHOLD)
     *   6. Nếu tree ngắn lại ≤ 6 → chuyển về linked list (UNTREEIFY_THRESHOLD)
     *
     * REHASHING:
     *   Khi size > capacity × loadFactor → tạo bảng mới gấp đôi, di chuyển tất cả entry
     *   Chi phí: O(n), xảy ra ít lần → amortized O(1) per put
     *
     * SA INSIGHT: Nếu biết trước số phần tử → khởi tạo capacity đủ lớn để tránh rehash.
     *   new HashMap<>(expectedSize / 0.75 + 1)  hoặc  Maps.newHashMapWithExpectedSize(n) (Guava)
     */
    static void demo1_HashMapInternals() {
        System.out.println("--- DEMO 1: HashMap Internals ---");

        // Minh hoạ hash distribution
        System.out.println("  Hash distribution của các key:");
        String[] keys = {"Alice", "Bob", "Charlie", "Dave", "Eve", "Frank", "Grace", "Hank"};
        int capacity = 16;
        for (String key : keys) {
            int h = key.hashCode();
            int spread = h ^ (h >>> 16);       // Java HashMap spread function
            int bucket = spread & (capacity - 1); // bucket index
            System.out.printf("    %-8s hashCode=%11d  bucket=%2d%n", key, h, bucket);
        }

        // Minh hoạ collision khi hashCode() tệ
        System.out.println("\n  Bad hashCode → tất cả vào cùng bucket (O(n) lookup!):");
        Map<BadKey, String> badMap = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            badMap.put(new BadKey(i), "value-" + i);
        }
        System.out.println("  BadKey map size: " + badMap.size()
                + " (nhưng lookup O(n) thay vì O(1))");

        // TREEIFY: khi bucket có ≥ 8 collision → chuyển sang Red-Black Tree
        System.out.println("\n  Treeify threshold = 8 collision trong 1 bucket:");
        System.out.println("  → Lookup từ O(n) xuống O(log n) khi nhiều collision");
        System.out.println("  → Yêu cầu key implement Comparable để tree sort được");

        // Rehashing: đo số lần resize
        System.out.println("\n  Rehashing khi size > capacity × 0.75:");
        System.out.println("  capacity=16 → rehash ở size 13");
        System.out.println("  capacity=32 → rehash ở size 25");
        System.out.println("  capacity=64 → rehash ở size 49 ...");

        // Pre-size HashMap tránh rehash
        int expected = 1000;
        int goodInitCap = (int)(expected / 0.75) + 1; // = 1334 → capacity 2048 (next power of 2)
        Map<String, String> presized = new HashMap<>(goodInitCap);
        System.out.println("\n  Pre-sized HashMap cho " + expected + " entries:");
        System.out.println("  new HashMap<>(" + goodInitCap + ") → không rehash khi put " + expected + " entries\n");
    }

    /** Key có hashCode() luôn trả về 0 — mọi entry vào cùng bucket */
    static class BadKey {
        int id;
        BadKey(int id) { this.id = id; }

        @Override public int hashCode() { return 0; }  // TỆ — tất cả vào bucket 0
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BadKey)) return false;
            return id == ((BadKey) o).id;
        }
    }

    // ================================================================
    // DEMO 2: HashMap Performance Pitfalls thực tế
    // ================================================================

    /**
     * PITFALL 1 — Mutable key: Key bị thay đổi sau khi put → hashCode thay đổi
     *   → HashMap không tìm được entry → data "biến mất"
     *   Rule: Key phải IMMUTABLE (String, Integer, record — tất cả immutable)
     *
     * PITFALL 2 — equals/hashCode contract vi phạm:
     *   a.equals(b) == true  →  a.hashCode() == b.hashCode()  (BẮT BUỘC)
     *   Ngược lại: HashMap tìm sai bucket → không tìm được entry
     *
     * PITFALL 3 — Không override equals/hashCode:
     *   Mặc định dùng Object.equals (reference equality) và Object.hashCode (address)
     *   → 2 object "bằng nhau" về data nhưng là 2 key khác nhau trong map
     *
     * PITFALL 4 — putIfAbsent vs computeIfAbsent:
     *   putIfAbsent(k, new ArrayList<>()) — LUÔN tạo ArrayList, dù key đã có → lãng phí
     *   computeIfAbsent(k, k -> new ArrayList<>()) — chỉ tạo khi key chưa có → đúng
     */
    static void demo2_HashMapPerformancePitfalls() {
        System.out.println("--- DEMO 2: HashMap Pitfalls ---");

        // PITFALL 1: Mutable key
        MutableKey key = new MutableKey("alice");
        Map<MutableKey, String> map = new HashMap<>();
        map.put(key, "user-data");
        System.out.println("  Trước khi thay đổi key: " + map.get(key));

        key.name = "bob"; // Thay đổi key sau khi put!
        System.out.println("  Sau khi thay đổi key:   " + map.get(key)
                + " ← null! Data bị mất vì hashCode thay đổi");
        System.out.println("  map.size() = " + map.size() + " nhưng không get được\n");

        // PITFALL 3: Không override equals/hashCode
        Map<Point, String> pointMap = new HashMap<>();
        pointMap.put(new Point(1, 2), "origin-shift");
        System.out.println("  Không override equals/hashCode:");
        System.out.println("  get(new Point(1,2)) = " + pointMap.get(new Point(1, 2))
                + " ← null! 2 object khác nhau dù giá trị giống nhau");

        Map<GoodPoint, String> goodMap = new HashMap<>();
        goodMap.put(new GoodPoint(1, 2), "found!");
        System.out.println("  Override equals/hashCode:");
        System.out.println("  get(new GoodPoint(1,2)) = " + goodMap.get(new GoodPoint(1, 2)) + "\n");

        // PITFALL 4: putIfAbsent vs computeIfAbsent
        Map<String, List<String>> grouping = new HashMap<>();
        List<String> names = List.of("Alice", "Bob", "Anna", "Brian", "Charlie");

        // SAI: putIfAbsent tạo ArrayList mới mỗi lần dù không dùng
        for (String name : names) {
            grouping.putIfAbsent(name.substring(0, 1), new ArrayList<>()); // Tạo ArrayList dù key có thể đã tồn tại
            grouping.get(name.substring(0, 1)).add(name);
        }
        System.out.println("  putIfAbsent grouping: " + grouping);

        // ĐÚNG: computeIfAbsent chỉ tạo khi cần
        Map<String, List<String>> betterGrouping = new HashMap<>();
        for (String name : names) {
            betterGrouping.computeIfAbsent(name.substring(0, 1), k -> new ArrayList<>()).add(name);
        }
        System.out.println("  computeIfAbsent grouping: " + betterGrouping + "\n");
    }

    static class MutableKey {
        String name;
        MutableKey(String name) { this.name = name; }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) {
            return o instanceof MutableKey m && name.equals(m.name);
        }
    }

    // Point KHÔNG override equals/hashCode → dùng reference equality
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }

    // GoodPoint override đúng
    static class GoodPoint {
        int x, y;
        GoodPoint(int x, int y) { this.x = x; this.y = y; }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public boolean equals(Object o) {
            return o instanceof GoodPoint p && x == p.x && y == p.y;
        }
    }

    // ================================================================
    // DEMO 3: ArrayList Internals — Amortized O(1) và capacity growth
    // ================================================================

    /**
     * CẤU TRÚC ArrayList:
     *   - Bên trong là Object[] elementData
     *   - Default capacity: 10 (khi add phần tử đầu tiên)
     *   - Capacity growth: newCapacity = oldCapacity + (oldCapacity >> 1) = × 1.5
     *     Ví dụ: 10 → 15 → 22 → 33 → 49 → 73 → ...
     *
     * AMORTIZED O(1) cho add():
     *   - Phần lớn lần add: O(1) — chỉ gán giá trị vào mảng
     *   - Thỉnh thoảng: O(n) — phải Arrays.copyOf sang mảng mới
     *   - Trung bình (amortized): O(1) vì resize xảy ra rất ít
     *
     * KHI NÀO CẦN ensureCapacity / pre-size:
     *   - Biết trước số phần tử → new ArrayList<>(n) tránh nhiều lần resize
     *   - Sau nhiều remove() → trimToSize() giải phóng memory dư
     *
     * SA INSIGHT: ArrayList.subList() trả về VIEW, không phải copy.
     *   Modify subList → modify original. Serialize subList → Exception.
     *   Muốn copy: new ArrayList<>(list.subList(from, to))
     */
    static void demo3_ArrayListInternals() {
        System.out.println("--- DEMO 3: ArrayList Internals ---");

        // Đếm số lần resize
        ResizeTrackingList<Integer> list = new ResizeTrackingList<>();
        for (int i = 0; i < 200; i++) list.add(i);
        System.out.println("  Thêm 200 phần tử vào ArrayList:");
        System.out.println("  Số lần resize (copyOf): " + list.resizeCount
                + " lần (10→15→22→33→49→73→109→163→244 — mỗi lần ×1.5)");

        // Pre-sized: không resize
        List<Integer> presized = new ArrayList<>(200);
        System.out.println("  Pre-sized ArrayList(200): 0 lần resize → hiệu quả hơn");

        // Benchmark: default vs presized
        int N = 1_000_000;
        long start = System.nanoTime();
        List<Integer> defaultList = new ArrayList<>();
        for (int i = 0; i < N; i++) defaultList.add(i);
        long defaultTime = System.nanoTime() - start;

        start = System.nanoTime();
        List<Integer> sizedList = new ArrayList<>(N);
        for (int i = 0; i < N; i++) sizedList.add(i);
        long sizedTime = System.nanoTime() - start;

        System.out.printf("%n  Thêm %,d phần tử:%n", N);
        System.out.printf("  ArrayList() default:  %,dns%n", defaultTime);
        System.out.printf("  ArrayList(%,d):  %,dns%n", N, sizedTime);
        System.out.printf("  Pre-sized nhanh hơn: %.1fx%n", (double) defaultTime / sizedTime);

        // subList là VIEW — cảnh báo quan trọng
        List<Integer> original = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        List<Integer> sub = original.subList(1, 4);  // [2, 3, 4] — VIEW
        sub.set(0, 99);                               // Thay đổi sub → thay đổi original!
        System.out.println("\n  subList là VIEW (không phải copy):");
        System.out.println("  sub.set(0, 99) → original = " + original + " ← original bị thay đổi!");
        System.out.println("  Fix: new ArrayList<>(list.subList(from, to)) để có bản copy\n");
    }

    /** ArrayList tùy chỉnh để đếm số lần resize — chỉ cho mục đích demo */
    static class ResizeTrackingList<E> extends ArrayList<E> {
        int resizeCount = 0;
        int currentCapacity = 10;

        @Override public boolean add(E e) {
            if (size() >= currentCapacity) {
                resizeCount++;
                currentCapacity = currentCapacity + (currentCapacity >> 1); // × 1.5
            }
            return super.add(e);
        }
    }

    // ================================================================
    // DEMO 4: ArrayList vs LinkedList — Tại sao LinkedList thường thua
    // ================================================================

    /**
     * MYTH: "LinkedList nhanh hơn ArrayList khi insert ở giữa"
     * REALITY: LinkedList gần như luôn thua ArrayList trong thực tế
     *
     * LÝ DO:
     *   1. CPU CACHE: ArrayList = contiguous memory → cache-friendly (prefetch tốt)
     *      LinkedList = pointer chasing → mỗi node ở địa chỉ random → cache miss liên tục
     *
     *   2. MEMORY OVERHEAD: Mỗi LinkedList node cần 2 pointer (prev, next) + object header
     *      = ~40 bytes/node. ArrayList: 4-8 bytes/reference.
     *
     *   3. INDEX ACCESS: ArrayList O(1) | LinkedList O(n) phải traverse từ đầu
     *
     *   4. INSERT Ở GIỮA thực tế:
     *      - LinkedList: O(n) để tìm vị trí + O(1) để insert = O(n) tổng
     *      - ArrayList: O(n) để shift nhưng shift dùng System.arraycopy (native, cực nhanh)
     *      → ArrayList thường nhanh hơn dù Big-O giống nhau (constant factor quan trọng!)
     *
     * DÙNG LinkedList KHI NÀO:
     *   - Deque operations: addFirst/addLast/pollFirst/pollLast → O(1)
     *   - Iterator-based insert/remove nhiều lần liên tiếp (dùng ListIterator)
     *   - Khi chắc chắn không cần random access
     *
     * SA INSIGHT: Trong thực tế, dùng ArrayDeque thay LinkedList cho Deque operations.
     *   ArrayDeque nhanh hơn LinkedList ở hầu hết cases và ít memory hơn.
     */
    static void demo4_ArrayListVsLinkedList() {
        System.out.println("--- DEMO 4: ArrayList vs LinkedList ---");

        int N = 100_000;

        // Sequential iteration — ArrayList wins nhờ cache locality
        List<Integer> arrayList = new ArrayList<>(IntStream.range(0, N).boxed().toList());
        List<Integer> linkedList = new LinkedList<>(arrayList);

        long start = System.nanoTime();
        long sum = 0;
        for (int x : arrayList) sum += x;
        long alIterTime = System.nanoTime() - start;

        start = System.nanoTime();
        sum = 0;
        for (int x : linkedList) sum += x;
        long llIterTime = System.nanoTime() - start;

        System.out.println("  Sequential iteration (" + N + " elements):");
        System.out.printf("  ArrayList:   %,6dns%n", alIterTime);
        System.out.printf("  LinkedList:  %,6dns  (%.1fx chậm hơn — cache miss!)%n%n",
                llIterTime, (double) llIterTime / alIterTime);

        // Random access — ArrayList O(1), LinkedList O(n)
        Random rnd = new Random(42);
        int LOOKUPS = 1000;

        start = System.nanoTime();
        for (int i = 0; i < LOOKUPS; i++) arrayList.get(rnd.nextInt(N));
        long alGetTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < LOOKUPS; i++) linkedList.get(rnd.nextInt(N));
        long llGetTime = System.nanoTime() - start;

        System.out.println("  Random access " + LOOKUPS + " times:");
        System.out.printf("  ArrayList:  %,6dns  O(1)%n", alGetTime);
        System.out.printf("  LinkedList: %,6dns  O(n) traverse  (%.0fx chậm hơn!)%n%n",
                llGetTime, (double) llGetTime / alGetTime);

        // Deque operations — LinkedList vs ArrayDeque
        int OPS = 50_000;
        Deque<Integer> ldq = new LinkedList<>();
        Deque<Integer> adq = new ArrayDeque<>();

        start = System.nanoTime();
        for (int i = 0; i < OPS; i++) { ldq.addFirst(i); ldq.addLast(i); }
        for (int i = 0; i < OPS; i++) { ldq.pollFirst(); ldq.pollLast(); }
        long llDequeTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < OPS; i++) { adq.addFirst(i); adq.addLast(i); }
        for (int i = 0; i < OPS; i++) { adq.pollFirst(); adq.pollLast(); }
        long adqDequeTime = System.nanoTime() - start;

        System.out.println("  Deque operations " + OPS + " push+poll:");
        System.out.printf("  LinkedList: %,dns%n", llDequeTime);
        System.out.printf("  ArrayDeque: %,dns  ← dùng cái này thay LinkedList!%n%n", adqDequeTime);
    }

    // ================================================================
    // DEMO 5: TreeMap vs LinkedHashMap vs HashMap
    // ================================================================

    /**
     * 3 Map variants — chọn theo nhu cầu thứ tự:
     *
     *   HashMap:
     *     - Không có thứ tự (random iteration order)
     *     - O(1) get/put/remove (amortized)
     *     - Tốt nhất khi: chỉ cần lookup, không quan tâm thứ tự
     *
     *   LinkedHashMap:
     *     - Duy trì INSERTION ORDER (hoặc ACCESS ORDER nếu config)
     *     - O(1) get/put — overhead nhỏ hơn TreeMap
     *     - Tốt nhất khi: cần thứ tự chèn (config, JSON object order)
     *     - ACCESS ORDER mode → tự nhiên là LRU Cache!
     *
     *   TreeMap:
     *     - Duy trì SORTED ORDER theo key (Red-Black Tree)
     *     - O(log n) get/put/remove
     *     - Bonus: firstKey(), lastKey(), subMap(), headMap(), tailMap()
     *     - Tốt nhất khi: cần range query, sorted output, nearest-key lookup
     *
     * SA INSIGHT: LinkedHashMap(16, 0.75, true) với removeEldestEntry() override
     *   = LRU Cache đơn giản, không cần thư viện ngoài. Xem demo bên dưới.
     */
    static void demo5_TreeMapVsLinkedHashMap() {
        System.out.println("--- DEMO 5: TreeMap vs LinkedHashMap ---");

        // HashMap — random order
        Map<String, Integer> hashMap = new HashMap<>();
        List.of("Banana", "Apple", "Cherry", "Date", "Elderberry").forEach(s -> hashMap.put(s, s.length()));
        System.out.println("  HashMap (random order):     " + hashMap.keySet());

        // LinkedHashMap — insertion order
        Map<String, Integer> linkedMap = new LinkedHashMap<>();
        List.of("Banana", "Apple", "Cherry", "Date", "Elderberry").forEach(s -> linkedMap.put(s, s.length()));
        System.out.println("  LinkedHashMap (insert order): " + linkedMap.keySet());

        // TreeMap — sorted order
        Map<String, Integer> treeMap = new TreeMap<>();
        List.of("Banana", "Apple", "Cherry", "Date", "Elderberry").forEach(s -> treeMap.put(s, s.length()));
        System.out.println("  TreeMap (sorted order):     " + treeMap.keySet());

        // TreeMap range queries — cực hữu ích
        TreeMap<Integer, String> scores = new TreeMap<>();
        scores.put(95, "Alice"); scores.put(82, "Bob"); scores.put(78, "Charlie");
        scores.put(91, "Dave");  scores.put(65, "Eve"); scores.put(88, "Frank");

        System.out.println("\n  TreeMap range queries trên scores:");
        System.out.println("  Điểm từ 80-90: " + scores.subMap(80, true, 90, true));
        System.out.println("  Điểm cao nhất: " + scores.lastEntry());
        System.out.println("  Điểm thấp nhất: " + scores.firstEntry());
        System.out.println("  Điểm ≥ 85: " + scores.tailMap(85));
        System.out.println("  Gần 87 nhất (floor): " + scores.floorEntry(87));  // ≤ 87
        System.out.println("  Gần 87 nhất (ceil):  " + scores.ceilingEntry(87)); // ≥ 87

        // LinkedHashMap LRU Cache
        System.out.println("\n  LinkedHashMap LRU Cache (capacity=3):");
        LRUCache<String, String> lru = new LRUCache<>(3);
        lru.put("k1", "v1"); System.out.println("  put k1 → " + lru.keySet());
        lru.put("k2", "v2"); System.out.println("  put k2 → " + lru.keySet());
        lru.put("k3", "v3"); System.out.println("  put k3 → " + lru.keySet());
        lru.get("k1");        System.out.println("  get k1 → " + lru.keySet() + " (k1 moved to end)");
        lru.put("k4", "v4"); System.out.println("  put k4 → " + lru.keySet() + " (k2 evicted — LRU)");
        System.out.println();
    }

    /** LRU Cache đơn giản dùng LinkedHashMap với access-order mode */
    static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;

        LRUCache(int maxSize) {
            super(maxSize, 0.75f, true); // true = ACCESS ORDER (không phải insertion order)
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize; // Xóa entry ít được dùng nhất khi quá size
        }
    }

    // ================================================================
    // DEMO 6: Collection Chooser — Bảng quyết định
    // ================================================================

    /**
     * Hướng dẫn chọn Collection đúng cho từng bài toán.
     * Lỗi phổ biến nhất: dùng List khi cần Set, dùng HashMap khi cần TreeMap.
     */
    static void demo6_CollectionChooser() {
        System.out.println("--- DEMO 6: Collection Decision Guide ---");

        // Set — deduplicate, O(1) contains
        List<String> withDups = List.of("apple", "banana", "apple", "cherry", "banana");
        Set<String> unique = new LinkedHashSet<>(withDups); // LinkedHashSet giữ insertion order
        System.out.println("  Deduplicate (LinkedHashSet): " + unique);

        // TreeSet — sorted unique elements
        Set<Integer> sorted = new TreeSet<>(List.of(5, 2, 8, 1, 9, 3));
        System.out.println("  TreeSet (sorted):  " + sorted);
        System.out.println("  Subset 3-7:        " + ((TreeSet<Integer>)sorted).subSet(3, true, 7, true));

        // ArrayDeque — Stack hoặc Queue (nhanh hơn Stack/LinkedList)
        Deque<String> stack = new ArrayDeque<>();
        stack.push("first"); stack.push("second"); stack.push("third");
        System.out.println("  ArrayDeque as Stack (LIFO): " + stack.pop() + " → " + stack.pop());

        Deque<String> queue = new ArrayDeque<>();
        queue.offer("first"); queue.offer("second"); queue.offer("third");
        System.out.println("  ArrayDeque as Queue (FIFO): " + queue.poll() + " → " + queue.poll());

        // PriorityQueue — luôn poll phần tử nhỏ nhất/lớn nhất
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();  // min-heap (mặc định)
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder()); // max-heap
        List.of(5, 2, 8, 1, 9, 3).forEach(minHeap::offer);
        List.of(5, 2, 8, 1, 9, 3).forEach(maxHeap::offer);
        System.out.println("  PriorityQueue min-heap poll: " + minHeap.poll() + ", " + minHeap.poll());
        System.out.println("  PriorityQueue max-heap poll: " + maxHeap.poll() + ", " + maxHeap.poll());

        System.out.println();
        System.out.println("=== BẢNG CHỌN COLLECTION ===");
        System.out.println("  BÀI TOÁN                          → COLLECTION TỐT NHẤT");
        System.out.println("  ─────────────────────────────────────────────────────────");
        System.out.println("  Key-value lookup                  → HashMap");
        System.out.println("  Key-value + insert order          → LinkedHashMap");
        System.out.println("  Key-value + sorted / range query  → TreeMap");
        System.out.println("  LRU Cache                         → LinkedHashMap(accessOrder=true)");
        System.out.println("  Unique elements, fast contains    → HashSet");
        System.out.println("  Unique + insert order             → LinkedHashSet");
        System.out.println("  Unique + sorted                   → TreeSet");
        System.out.println("  List, random access               → ArrayList");
        System.out.println("  Stack / Queue / Deque             → ArrayDeque");
        System.out.println("  Top-K, min/max poll               → PriorityQueue");
        System.out.println("  Thread-safe Map                   → ConcurrentHashMap (bài 3.2)");
        System.out.println("  Thread-safe Queue                 → LinkedBlockingQueue (bài 3.2)");

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 3.1 ===");
        System.out.println("  ✓ HashMap: hash → bucket → chain/tree. Pre-size để tránh rehash.");
        System.out.println("  ✓ Key phải immutable + equals/hashCode đúng contract.");
        System.out.println("  ✓ ArrayList amortized O(1), cache-friendly, thường nhanh hơn LinkedList.");
        System.out.println("  ✓ TreeMap = sorted map + range queries. LinkedHashMap = LRU Cache.");
        System.out.println("  ✓ ArrayDeque thay LinkedList cho Deque. PriorityQueue cho top-K.");
        System.out.println("  → Bài tiếp: 3.2 ConcurrentCollectionsDemo — ConcurrentHashMap, BlockingQueue");
    }
}
