package org.example.collections;

import java.util.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 3.3 — Generics nâng cao
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Tại sao Generics tồn tại — type safety tại compile time
 *   2. Type Erasure — List<String> và List<Integer> giống nhau ở runtime
 *   3. Wildcards: ? extends T (covariance) và ? super T (contravariance)
 *   4. PECS rule — Producer Extends, Consumer Super
 *   5. Bounded type parameters, generic methods, recursive bounds
 *   6. Heap pollution, reifiable types, @SafeVarargs
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.collections.GenericsAdvancedDemo"
 * ============================================================
 */
public class GenericsAdvancedDemo {

    public static void main(String[] args) {
        System.out.println("=== BÀI 3.3: Generics nâng cao ===\n");

        demo1_TypeErasure();
        demo2_WildcardsCovariance();
        demo3_PECSRule();
        demo4_BoundedTypeParams();
        demo5_GenericMethods();
        demo6_HeapPollution();

        System.out.println("\n=== KẾT THÚC BÀI 3.3 ===");
    }

    // ================================================================
    // DEMO 1: Type Erasure — Bí ẩn lớn nhất của Java Generics
    // ================================================================

    /**
     * Generics trong Java là COMPILE-TIME feature.
     * Sau khi compile, type parameter bị XÓA hoàn toàn (type erasure).
     *
     * List<String> và List<Integer> đều trở thành List (raw type) ở bytecode.
     * Compiler chèn cast tự động tại nơi get() được gọi.
     *
     * VÍ DỤ BYTECODE:
     *   Bạn viết:  String s = list.get(0);
     *   Compiler:  String s = (String) list.get(0);  ← cast tự động
     *
     * HỆ QUẢ của type erasure:
     *   1. KHÔNG THỂ: new T()         — không biết T là gì ở runtime
     *   2. KHÔNG THỂ: new T[]         — không tạo được generic array
     *   3. KHÔNG THỂ: instanceof List<String>  — chỉ kiểm tra được instanceof List
     *   4. KHÔNG THỂ: overload bằng List<String> vs List<Integer>  — cùng erasure!
     *   5. CÓ THỂ:   Class<T> classToken      — workaround dùng Class object
     *
     * TẠI SAO Java chọn erasure? Backward compatibility với Java 1.4 code.
     * C# dùng reification — List<string> và List<int> là 2 class KHÁC nhau ở runtime.
     *
     * SA INSIGHT: Hiểu type erasure giải thích tại sao Spring/Jackson dùng
     *   TypeReference, ParameterizedType — họ cần workaround erasure để
     *   biết generic type ở runtime (ví dụ deserialize List<User>).
     */
    static void demo1_TypeErasure() {
        System.out.println("--- DEMO 1: Type Erasure ---");

        List<String> strings  = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();

        // Ở runtime, cả 2 đều là java.util.ArrayList
        System.out.println("  List<String>.getClass():  " + strings.getClass());
        System.out.println("  List<Integer>.getClass(): " + integers.getClass());
        System.out.println("  Cùng class? " + (strings.getClass() == integers.getClass())); // true!

        // instanceof chỉ check raw type
        System.out.println("  strings instanceof List:          " + (strings instanceof List));
        // Compile error: strings instanceof List<String>  ← không được!
        System.out.println("  (Không check được: instanceof List<String>  — compile error)");

        // Không thể overload bằng generic type
        System.out.println("\n  Overload bằng generic type — KHÔNG thể:");
        System.out.println("  void process(List<String> s) và void process(List<Integer> i)");
        System.out.println("  → Compile error: both have same erasure List");

        // Workaround: Class token (super type token)
        System.out.println("\n  Workaround với Class token:");
        String val = createInstance(String.class, "hello");
        System.out.println("  createInstance(String.class, \"hello\"): " + val);

        // Super type token — cách Jackson/Gson đọc generic type
        // Trick: subclass ẩn danh giữ lại generic type trong getGenericSuperclass()
        TypeToken<List<String>> token = new TypeToken<List<String>>() {};
        System.out.println("  TypeToken<List<String>> type: " + token.getType());
        System.out.println("  → Đây là cách Jackson ObjectMapper biết type khi deserialize List<User>\n");
    }

    // Class token workaround
    static <T> T createInstance(Class<T> clazz, Object... args) {
        // Trong thực tế dùng reflection — đây chỉ minh hoạ concept
        return clazz.cast(args.length > 0 ? args[0] : null);
    }

    /** Super type token — trick để capture generic type ở runtime */
    static abstract class TypeToken<T> {
        java.lang.reflect.Type getType() {
            // getGenericSuperclass() trả về ParameterizedType giữ T
            return ((java.lang.reflect.ParameterizedType)
                    getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }

    // ================================================================
    // DEMO 2: Wildcards — ? extends T và ? super T
    // ================================================================

    /**
     * Vấn đề: Generics KHÔNG covariant theo mặc định.
     *   List<Dog> là subtype của List<Animal>?  KHÔNG! (dù Dog extends Animal)
     *   Lý do: nếu cho phép, có thể add Cat vào List<Dog> → type unsafe!
     *
     *   List<Dog> dogs = ...;
     *   List<Animal> animals = dogs;  // Compile error — đúng!
     *   animals.add(new Cat());       // Nếu được phép → sai kiểu trong dogs!
     *
     * GIẢI PHÁP: Wildcards
     *
     *   ? extends T  (UPPER BOUND / covariance):
     *     List<? extends Animal>: chấp nhận List<Dog>, List<Cat>, List<Animal>
     *     Chỉ được READ (get trả về Animal)
     *     KHÔNG được WRITE (không biết chính xác là List<Dog> hay List<Cat>)
     *
     *   ? super T  (LOWER BOUND / contravariance):
     *     List<? super Dog>: chấp nhận List<Dog>, List<Animal>, List<Object>
     *     Chỉ được WRITE Dog hoặc subtypes
     *     READ trả về Object (mất type info)
     *
     *   ?  (UNBOUNDED):
     *     List<?>: chấp nhận List<anything>
     *     Chỉ được đọc Object, không được write (trừ null)
     *     Dùng khi không cần biết type cụ thể (printAll, size(), ...)
     */
    static void demo2_WildcardsCovariance() {
        System.out.println("--- DEMO 2: Wildcards — ? extends vs ? super ---");

        List<Dog>    dogs    = List.of(new Dog("Rex"),   new Dog("Buddy"));
        List<Cat>    cats    = List.of(new Cat("Whiskers"), new Cat("Mittens"));
        List<Animal> animals = List.of(new Dog("Max"),   new Cat("Luna"));

        // Không covariant — compile error nếu bỏ comment
        // List<Animal> ref = dogs;  // ERROR: List<Dog> is not List<Animal>

        // ? extends Animal — "List của gì đó là Animal" — READ ONLY
        System.out.println("  ? extends Animal (covariance — read only):");
        printAnimals(dogs);    // OK — Dog extends Animal
        printAnimals(cats);    // OK — Cat extends Animal
        printAnimals(animals); // OK — Animal is Animal

        // ? super Dog — "List của gì đó là supertype của Dog" — WRITE Dog
        System.out.println("\n  ? super Dog (contravariance — write Dog):");
        List<Animal> bucket = new ArrayList<>();
        addDogs(bucket);  // OK — Animal is supertype of Dog
        System.out.println("  Sau addDogs vào List<Animal>: " + bucket);

        List<Object> objectBucket = new ArrayList<>();
        addDogs(objectBucket);  // OK — Object is supertype of Dog
        System.out.println("  Sau addDogs vào List<Object>: " + objectBucket);

        // Unbounded ? — không biết type, chỉ dùng Object
        System.out.println("\n  Unbounded ? — dùng khi không quan tâm type:");
        printSize(dogs);
        printSize(cats);
        printSize(List.of(1, "two", 3.0));
    }

    // ? extends Animal → chỉ READ, không WRITE
    static void printAnimals(List<? extends Animal> list) {
        list.forEach(a -> System.out.print("  " + a.speak() + "  "));
        System.out.println();
        // list.add(new Dog("Max"));  // COMPILE ERROR — không biết là List<Dog> hay List<Cat>
    }

    // ? super Dog → WRITE Dog, READ trả về Object
    static void addDogs(List<? super Dog> list) {
        list.add(new Dog("Fido"));
        list.add(new Dog("Spike"));
        // list.add(new Cat("Kitty"));  // COMPILE ERROR — Cat không phải Dog
    }

    static void printSize(List<?> list) {
        System.out.print("  size=" + list.size() + "  ");
        // Object item = list.get(0);  // Chỉ nhận được Object
        // list.add("x");  // COMPILE ERROR — không biết type
    }

    // ================================================================
    // DEMO 3: PECS Rule — Producer Extends, Consumer Super
    // ================================================================

    /**
     * PECS = Producer Extends, Consumer Super  (Joshua Bloch — Effective Java)
     *
     * Câu hỏi: khi nào dùng ? extends, khi nào ? super?
     *
     * PRODUCER (cung cấp data — ta READ từ nó):
     *   → Dùng ? extends T
     *   Ví dụ: copy FROM source → source là producer → List<? extends T> source
     *
     * CONSUMER (tiêu thụ data — ta WRITE vào nó):
     *   → Dùng ? super T
     *   Ví dụ: copy INTO destination → dest là consumer → List<? super T> dest
     *
     * Mnemonic: "Be PECS: Producer Extends, Consumer Super"
     *
     * VÍ DỤ KINH ĐIỂN: Collections.copy(dest, src)
     *   public static <T> void copy(List<? super T> dest, List<? extends T> src)
     *   src là producer (ta đọc từ đây) → ? extends T
     *   dest là consumer (ta ghi vào đây) → ? super T
     *
     * Nếu không dùng PECS: API kém linh hoạt, caller phải cast thủ công.
     */
    static void demo3_PECSRule() {
        System.out.println("--- DEMO 3: PECS Rule ---");

        List<Dog> src  = new ArrayList<>(List.of(new Dog("A"), new Dog("B"), new Dog("C")));
        List<Animal> dest = new ArrayList<>();

        // copyAnimals: src là producer (extends), dest là consumer (super)
        copyAnimals(src, dest);
        System.out.println("  copyAnimals(List<Dog> → List<Animal>): " + dest);

        // Flexible — nhiều type combination đều work
        List<Cat> cats = new ArrayList<>(List.of(new Cat("X"), new Cat("Y")));
        List<Object> objects = new ArrayList<>();
        copyAnimals(cats, objects);
        System.out.println("  copyAnimals(List<Cat> → List<Object>):  " + objects);

        // Stack ví dụ — pushAll và popAll
        GenericStack<Number> stack = new GenericStack<>();
        List<Integer> ints    = List.of(1, 2, 3);
        List<Double>  doubles = List.of(4.4, 5.5);

        stack.pushAll(ints);    // Integer là subtype của Number → ? extends Number
        stack.pushAll(doubles); // Double là subtype của Number → ? extends Number
        System.out.println("  Stack sau pushAll (ints + doubles): " + stack);

        List<Number> output = new ArrayList<>();
        stack.popAll(output);   // output là consumer của Number → ? super Number
        System.out.println("  popAll vào List<Number>: " + output);

        List<Object> objOutput = new ArrayList<>();
        stack.pushAll(ints);
        stack.popAll(objOutput); // Object là supertype của Number → ? super Number
        System.out.println("  popAll vào List<Object>: " + objOutput + "\n");
    }

    // PECS: src = producer (extends), dest = consumer (super)
    static <T> void copyAnimals(List<? extends T> src, List<? super T> dest) {
        for (T item : src) dest.add(item);
    }

    static class GenericStack<E> {
        private final Deque<E> deque = new ArrayDeque<>();

        // Producer Extends: lấy từ iterable (producer)
        public void pushAll(Iterable<? extends E> src) {
            for (E e : src) deque.push(e);
        }

        // Consumer Super: đổ vào collection (consumer)
        public void popAll(Collection<? super E> dest) {
            while (!deque.isEmpty()) dest.add(deque.pop());
        }

        @Override public String toString() { return deque.toString(); }
    }

    // ================================================================
    // DEMO 4: Bounded Type Parameters & Recursive Bounds
    // ================================================================

    /**
     * SINGLE BOUND:    <T extends Comparable<T>>
     *   T phải implement Comparable<T>
     *   Dùng khi: min, max, sort — cần so sánh được
     *
     * MULTIPLE BOUNDS: <T extends Serializable & Comparable<T>>
     *   T phải implement cả 2 interface
     *   Class phải đứng đầu nếu có cả class và interface
     *
     * RECURSIVE BOUND (F-bounded polymorphism): <T extends Comparable<T>>
     *   T so sánh được với chính T (không phải với Animal chung chung)
     *   Ví dụ: Integer implements Comparable<Integer> ✓
     *
     * ENUM PATTERN: <T extends Enum<T>>
     *   Tất cả enum đều extend Enum<T> — pattern rất phổ biến trong Java
     *
     * SA INSIGHT: Builder pattern trong API design thường dùng recursive bound:
     *   abstract class Builder<T extends Builder<T>> { T self() { return (T) this; } }
     *   → Method chaining trả về đúng subtype, không phải Builder base type.
     */
    static void demo4_BoundedTypeParams() {
        System.out.println("--- DEMO 4: Bounded Type Parameters ---");

        // Single bound — T extends Comparable<T>
        System.out.println("  max(3, 7): " + max(3, 7));
        System.out.println("  max(\"apple\", \"zebra\"): " + max("apple", "zebra"));
        System.out.println("  max(3.14, 2.71): " + max(3.14, 2.71));

        // Multiple bounds
        List<Integer> nums = new ArrayList<>(List.of(5, 2, 8, 1, 9, 3));
        sortAndPrint(nums);

        // Recursive bound — Builder pattern
        System.out.println("\n  Recursive bound Builder pattern:");
        HttpRequest request = new HttpRequest.Builder()
            .url("https://api.example.com/users")
            .method("GET")
            .header("Authorization", "Bearer token123")
            .timeout(30)
            .build();
        System.out.println("  " + request);

        // Enum bound
        System.out.println("\n  Enum bound:");
        System.out.println("  Day.MONDAY ordinal: " + getOrdinal(Day.MONDAY));
        System.out.println("  Day.FRIDAY name:    " + Day.FRIDAY.name());
        System.out.println("  All days: " + Arrays.toString(getAllValues(Day.class)));
        System.out.println();
    }

    // T extends Comparable<T> — chỉ nhận type có thể so sánh với chính nó
    static <T extends Comparable<T>> T max(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    // Multiple bounds: T phải là Comparable và có thể log (ví dụ)
    static <T extends Comparable<T>> void sortAndPrint(List<T> list) {
        Collections.sort(list);
        System.out.println("  sortAndPrint: " + list);
    }

    enum Day { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

    static <T extends Enum<T>> int getOrdinal(T enumValue) {
        return enumValue.ordinal();
    }

    static <T extends Enum<T>> T[] getAllValues(Class<T> enumClass) {
        return enumClass.getEnumConstants();
    }

    // Recursive bound Builder — method chaining trả về đúng subtype
    static class HttpRequest {
        final String url, method, auth;
        final int timeout;
        final Map<String, String> headers;

        private HttpRequest(Builder b) {
            this.url = b.url; this.method = b.method;
            this.auth = b.headers.getOrDefault("Authorization", "");
            this.timeout = b.timeout; this.headers = b.headers;
        }

        @Override public String toString() {
            return "HttpRequest{" + method + " " + url + " timeout=" + timeout + "s}";
        }

        // <B extends Builder<B>> — Builder của subtype trả về subtype, không phải Builder base
        static class Builder {
            String url = "", method = "GET";
            int timeout = 10;
            Map<String, String> headers = new LinkedHashMap<>();

            Builder url(String url)           { this.url = url; return this; }
            Builder method(String method)     { this.method = method; return this; }
            Builder timeout(int s)            { this.timeout = s; return this; }
            Builder header(String k, String v){ this.headers.put(k, v); return this; }
            HttpRequest build()               { return new HttpRequest(this); }
        }
    }

    // ================================================================
    // DEMO 5: Generic Methods — Inference, Flexible APIs
    // ================================================================

    /**
     * GENERIC METHOD: <T> trước return type
     *   Compiler tự suy ra T từ argument (type inference)
     *   Không cần chỉ định type khi gọi (thường)
     *
     * LỢI ÍCH so với raw type hoặc Object:
     *   - Type-safe: không cần cast
     *   - Flexible: hoạt động với nhiều type
     *   - Self-documenting: API rõ ràng hơn
     *
     * GENERIC RETURN TYPE: Trả về T thay vì Object — caller không cần cast
     *
     * VARARGS + GENERICS: @SafeVarargs (xem demo 6)
     */
    static void demo5_GenericMethods() {
        System.out.println("--- DEMO 5: Generic Methods ---");

        // swap — generic method, type inferred
        String[] strArr = {"Hello", "World"};
        swap(strArr, 0, 1);
        System.out.println("  swap String[]: " + Arrays.toString(strArr));

        Integer[] intArr = {1, 2, 3, 4, 5};
        swap(intArr, 1, 3);
        System.out.println("  swap Integer[]: " + Arrays.toString(intArr));

        // Optional-like container — generic return
        Box<String> strBox = Box.of("Java Generics");
        Box<Integer> intBox = Box.of(42);
        System.out.println("  Box<String>: " + strBox.map(String::toUpperCase));
        System.out.println("  Box<Integer>: " + intBox.map(n -> n * 2));

        // Pair — 2 type parameters
        Pair<String, Integer> pair = Pair.of("score", 99);
        Pair<Integer, String> swapped = pair.swap(); // swap type params!
        System.out.println("  Pair: " + pair);
        System.out.println("  Swapped: " + swapped);

        // Generic utility methods
        List<Integer> numbers = List.of(3, 1, 4, 1, 5, 9, 2, 6);
        System.out.println("  min of numbers: " + findMin(numbers));
        System.out.println("  max of numbers: " + findMax(numbers));

        List<String> words = List.of("banana", "apple", "cherry");
        System.out.println("  min of words:   " + findMin(words));
        System.out.println("  max of words:   " + findMax(words));

        // zip — kết hợp 2 list thành List<Pair>
        List<String> names  = List.of("Alice", "Bob", "Charlie");
        List<Integer> scores = List.of(95, 82, 91);
        List<Pair<String, Integer>> zipped = zip(names, scores);
        System.out.println("  zip(names, scores): " + zipped + "\n");
    }

    static <T> void swap(T[] arr, int i, int j) {
        T temp = arr[i]; arr[i] = arr[j]; arr[j] = temp;
    }

    static <T extends Comparable<T>> T findMin(List<T> list) {
        return list.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    static <T extends Comparable<T>> T findMax(List<T> list) {
        return list.stream().max(Comparator.naturalOrder()).orElseThrow();
    }

    static <A, B> List<Pair<A, B>> zip(List<A> as, List<B> bs) {
        List<Pair<A, B>> result = new ArrayList<>();
        int size = Math.min(as.size(), bs.size());
        for (int i = 0; i < size; i++) result.add(Pair.of(as.get(i), bs.get(i)));
        return result;
    }

    // Generic Box — giống Optional nhẹ
    static class Box<T> {
        private final T value;
        private Box(T value) { this.value = value; }
        static <T> Box<T> of(T value) { return new Box<>(value); }

        <R> Box<R> map(Function<T, R> fn) { return Box.of(fn.apply(value)); }

        @Override public String toString() { return "Box[" + value + "]"; }
    }

    // Generic Pair — 2 type parameters
    static class Pair<A, B> {
        final A first; final B second;
        private Pair(A a, B b) { this.first = a; this.second = b; }
        static <A, B> Pair<A, B> of(A a, B b) { return new Pair<>(a, b); }

        Pair<B, A> swap() { return Pair.of(second, first); }

        @Override public String toString() { return "(" + first + ", " + second + ")"; }
    }

    // ================================================================
    // DEMO 6: Heap Pollution & @SafeVarargs
    // ================================================================

    /**
     * HEAP POLLUTION: Khi biến kiểu tham số hóa (parameterized type) trỏ
     *   đến object KHÔNG phải kiểu đó — dẫn đến ClassCastException bất ngờ.
     *
     * NGUYÊN NHÂN PHỔ BIẾN:
     *   1. Raw type assignment:
     *      List strings = new ArrayList<String>();
     *      strings.add(42);  // Compiler warning, không error
     *      String s = ((List<String>) strings).get(0);  // ClassCastException!
     *
     *   2. Generic varargs:
     *      void dangerous(List<String>... lists) { ... }
     *      Compiler warning: "Possible heap pollution from parameterized vararg type"
     *      Lý do: varargs tạo array, generic array tạo ra heap pollution
     *
     * @SafeVarargs: Annotation nói với compiler "method này an toàn, không cần warning"
     *   Dùng khi: method chỉ ĐỌC từ varargs, KHÔNG ghi vào
     *   Chỉ dùng được trên: static, final, hoặc private methods (Java 9+: private)
     *
     * REIFIABLE TYPE: Type biết chính xác kiểu ở runtime
     *   int, String, List (raw), String[], List<?>  → reifiable
     *   List<String>, Map<K,V>                      → NOT reifiable (bị erase)
     *   → Generic array (new List<String>[5]) KHÔNG thể tạo vì array cần reifiable element type
     *
     * SA INSIGHT: Khi thấy "unchecked warning" → cần hiểu tại sao, không nên
     *   @SuppressWarnings("unchecked") bừa bãi. Mỗi suppression nên có comment giải thích.
     */
    static void demo6_HeapPollution() {
        System.out.println("--- DEMO 6: Heap Pollution & @SafeVarargs ---");

        // Heap pollution qua raw type
        @SuppressWarnings("unchecked")
        List<String> polluted = (List) new ArrayList<>(List.of(1, 2, 3)); // Raw type cast
        try {
            String s = polluted.get(0); // ClassCastException — Integer không thể cast sang String
            System.out.println("  Không nên thấy dòng này: " + s);
        } catch (ClassCastException e) {
            System.out.println("  Heap pollution → ClassCastException: " + e.getMessage());
            System.out.println("  (Compiler đã warn nhưng bị suppress)");
        }

        // @SafeVarargs — safe vì chỉ đọc, không ghi
        List<String> a = List.of("hello", "world");
        List<String> b = List.of("java", "generics");
        List<List<String>> combined = combine(a, b);
        System.out.println("  @SafeVarargs combine: " + combined);

        // Generic array KHÔNG thể tạo trực tiếp — workaround
        System.out.println("\n  Generic array workarounds:");

        // Cách 1: dùng List thay Array
        List<List<String>> matrix = new ArrayList<>();
        matrix.add(new ArrayList<>(List.of("r1c1", "r1c2")));
        matrix.add(new ArrayList<>(List.of("r2c1", "r2c2")));
        System.out.println("  List<List<T>> thay T[][]: " + matrix);

        // Cách 2: cast với @SuppressWarnings nếu bắt buộc dùng array
        @SuppressWarnings("unchecked")
        List<String>[] arr = new List[3]; // Raw array — compiler warn, nhưng đôi khi cần
        arr[0] = List.of("a"); arr[1] = List.of("b"); arr[2] = List.of("c");
        System.out.println("  new List[3] cast: " + Arrays.toString(arr)
                + "  ← dùng @SuppressWarnings + comment giải thích");

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 3.3 ===");
        System.out.println("  ✓ Type erasure: List<String> == List<Integer> ở runtime → workaround: TypeToken");
        System.out.println("  ✓ ? extends T  = covariance, read-only (Producer)");
        System.out.println("  ✓ ? super T    = contravariance, write (Consumer)");
        System.out.println("  ✓ PECS: Producer Extends, Consumer Super");
        System.out.println("  ✓ <T extends Comparable<T>> = recursive bound, tự so sánh được");
        System.out.println("  ✓ @SafeVarargs: chỉ dùng khi method không ghi vào varargs array");
        System.out.println("  → Bài tiếp: 3.4 StreamsAdvancedDemo — Spliterator, parallel streams, collectors");
    }

    @SafeVarargs // An toàn: chỉ đọc từ varargs, không ghi vào
    static <T> List<List<T>> combine(List<T>... lists) {
        List<List<T>> result = new ArrayList<>();
        for (List<T> list : lists) result.add(list);
        return result;
    }

    // ================================================================
    // DOMAIN CLASSES
    // ================================================================

    static abstract class Animal {
        String name;
        Animal(String name) { this.name = name; }
        abstract String speak();
        @Override public String toString() { return getClass().getSimpleName() + "(" + name + ")"; }
    }

    static class Dog extends Animal {
        Dog(String name) { super(name); }
        @Override public String speak() { return name + ":Woof"; }
    }

    static class Cat extends Animal {
        Cat(String name) { super(name); }
        @Override public String speak() { return name + ":Meow"; }
    }
}
