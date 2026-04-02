package org.example.tooling;

import java.util.*;
import java.util.stream.*;
import java.time.*;
import java.math.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 10.1 — MODERN JAVA FEATURES (Java 14–21)
 * ============================================================
 *
 * Java đã thay đổi rất nhiều từ Java 8. Mỗi version thêm features
 * giúp code ngắn hơn, an toàn hơn, expressive hơn.
 *
 * TIMELINE:
 *   Java 14  → Records (preview), Switch Expressions (stable)
 *   Java 15  → Text Blocks (stable), Sealed Classes (preview)
 *   Java 16  → Records (stable), Pattern Matching instanceof (stable)
 *   Java 17  → Sealed Classes (stable) [LTS]
 *   Java 21  → Pattern Matching switch (stable), Record Patterns (stable),
 *              Virtual Threads (stable), Sequenced Collections [LTS]
 *
 * ============================================================
 * FEATURE 1: RECORDS (Java 16+)
 * ============================================================
 *
 * Record = immutable data carrier. Tự động generate:
 *   - Constructor (all fields)
 *   - Getters (field name, không phải getX())
 *   - equals() + hashCode() (all fields)
 *   - toString() (all fields)
 *
 * TRƯỚC:
 *   class Point {
 *       private final int x, y;
 *       Point(int x, int y) { this.x = x; this.y = y; }
 *       int x() { return x; }
 *       int y() { return y; }
 *       @Override boolean equals(Object o) { ... } // 10 dòng
 *       @Override int hashCode() { ... }
 *       @Override String toString() { return "Point[x=" + x + ", y=" + y + "]"; }
 *   } // 30+ dòng
 *
 * SAU:
 *   record Point(int x, int y) {}  // 1 dòng!
 *
 * ============================================================
 * FEATURE 2: SEALED CLASSES (Java 17+)
 * ============================================================
 *
 * Sealed class: giới hạn ai có thể extend/implement.
 * Kết hợp với Pattern Matching switch → exhaustive type checking.
 *
 * Khi nào dùng:
 *   - Domain model có tập hữu hạn subtypes (Shape, Result, Event)
 *   - Muốn compiler enforce xử lý tất cả cases (không bỏ sót)
 *   - Thay thế enum khi mỗi variant cần data khác nhau
 *
 * ============================================================
 * FEATURE 3: PATTERN MATCHING instanceof (Java 16+)
 * ============================================================
 *
 * instanceof pattern:  if (obj instanceof String s) { use s directly }
 * Eliminates redundant cast, works with && for guarded conditions.
 *
 * ============================================================
 * FEATURE 4: TEXT BLOCKS (Java 15+)
 * ============================================================
 *
 * Multiline strings với tự động indent stripping.
 * Perfect for SQL, JSON, HTML, regex.
 *
 * ============================================================
 * FEATURE 5: SWITCH EXPRESSIONS (Java 14+)
 * ============================================================
 *
 * Switch là expression (có return value), không chỉ statement.
 * Arrow syntax: no fallthrough, no break needed.
 * yield: return value from block arm.
 *
 * ============================================================
 */
public class ModernJavaFeaturesDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: RECORDS
    // ═══════════════════════════════════════════════════════

    /**
     * Basic record — 1 dòng thay 30+ dòng boilerplate.
     */
    record Point(int x, int y) {
        // Compact constructor: validation, normalization
        Point {
            if (x < 0 || y < 0) throw new IllegalArgumentException(
                "Coordinates must be non-negative: (" + x + "," + y + ")");
        }

        // Custom methods OK — records are not just DTOs
        double distanceTo(Point other) {
            int dx = this.x - other.x;
            int dy = this.y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }

        Point translate(int dx, int dy) {
            return new Point(x + dx, y + dy); // records are immutable → return new instance
        }

        // Static factory
        static Point origin() { return new Point(0, 0); }
    }

    /**
     * Record với generic type.
     */
    record Pair<A, B>(A first, B second) {
        static <A, B> Pair<A, B> of(A a, B b) { return new Pair<>(a, b); }

        Pair<B, A> swapped() { return new Pair<>(second, first); }
    }

    /**
     * Record implementing interface — DDD Value Object pattern.
     */
    interface Monetary {
        BigDecimal amount();
        String currency();
        default String formatted() {
            return amount().toPlainString() + " " + currency();
        }
    }

    record Money(BigDecimal amount, String currency) implements Monetary {
        Money {
            Objects.requireNonNull(amount);
            Objects.requireNonNull(currency);
            if (amount.compareTo(BigDecimal.ZERO) < 0)
                throw new IllegalArgumentException("Amount cannot be negative");
            amount = amount.setScale(2, RoundingMode.HALF_UP);
        }

        static Money of(double amount, String currency) {
            return new Money(BigDecimal.valueOf(amount), currency);
        }

        Money add(Money other) {
            assertSameCurrency(other);
            return new Money(amount.add(other.amount), currency);
        }

        Money multiply(int factor) {
            return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
        }

        private void assertSameCurrency(Money other) {
            if (!currency.equals(other.currency))
                throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    /**
     * Record Patterns (Java 21): destructure record in pattern matching.
     */
    record Range(int min, int max) {
        Range {
            if (min > max) throw new IllegalArgumentException("min > max");
        }
        boolean contains(int value) { return value >= min && value <= max; }
        int size() { return max - min; }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: SEALED CLASSES + PATTERN MATCHING
    // ═══════════════════════════════════════════════════════

    /**
     * Sealed interface: Shape có thể là Circle, Rectangle, hoặc Triangle.
     * Không ai khác có thể implement Shape.
     *
     * permits clause = exhaustive list → compiler kiểm tra tất cả cases.
     */
    sealed interface Shape permits Circle, Rectangle, Triangle {
        double area();
        double perimeter();
        String color();
    }

    record Circle(double radius, String color) implements Shape {
        Circle { if (radius <= 0) throw new IllegalArgumentException("radius must be positive"); }
        @Override public double area()      { return Math.PI * radius * radius; }
        @Override public double perimeter() { return 2 * Math.PI * radius; }
    }

    record Rectangle(double width, double height, String color) implements Shape {
        Rectangle {
            if (width <= 0 || height <= 0)
                throw new IllegalArgumentException("Dimensions must be positive");
        }
        @Override public double area()      { return width * height; }
        @Override public double perimeter() { return 2 * (width + height); }
        boolean isSquare()                  { return width == height; }
    }

    record Triangle(double a, double b, double c, String color) implements Shape {
        Triangle {
            if (a + b <= c || a + c <= b || b + c <= a)
                throw new IllegalArgumentException("Invalid triangle sides");
        }
        @Override public double area() {
            double s = (a + b + c) / 2;
            return Math.sqrt(s * (s-a) * (s-b) * (s-c));
        }
        @Override public double perimeter() { return a + b + c; }
    }

    /**
     * Result type: Success hoặc Failure (không throw exception).
     * Sealed + records = type-safe error handling.
     *
     * Pattern: railway-oriented programming, functional error handling.
     */
    sealed interface Result<T> permits Result.Success, Result.Failure {
        record Success<T>(T value) implements Result<T> {}
        record Failure<T>(String error, Throwable cause) implements Result<T> {
            Failure(String error) { this(error, null); }
        }

        static <T> Result<T> success(T value)            { return new Success<>(value); }
        static <T> Result<T> failure(String error)       { return new Failure<>(error); }
        static <T> Result<T> failure(String e, Throwable t) { return new Failure<>(e, t); }

        static <T> Result<T> of(Supplier<T> action) {
            try { return success(action.get()); }
            catch (Exception e) { return failure(e.getMessage(), e); }
        }

        default boolean isSuccess() { return this instanceof Success; }
        default boolean isFailure() { return this instanceof Failure; }

        @SuppressWarnings("unchecked")
        default T valueOrThrow() {
            if (this instanceof Success<?> s) return (T) s.value();
            Failure<?> f = (Failure<?>) this;
            throw new RuntimeException(f.error(), f.cause());
        }

        @SuppressWarnings("unchecked")
        default <R> Result<R> map(Function<T, R> mapper) {
            if (this instanceof Success<?> s) return Result.of(() -> mapper.apply((T) s.value()));
            Failure<?> f = (Failure<?>) this;
            return Result.failure(f.error(), f.cause());
        }

        @SuppressWarnings("unchecked")
        default T getOrElse(T defaultValue) {
            if (this instanceof Success<?> s) return (T) s.value();
            return defaultValue;
        }
    }

    /**
     * Sealed class with class (not just records) — when subtypes need mutable state.
     */
    sealed interface JsonValue permits
        JsonValue.JsonString, JsonValue.JsonNumber, JsonValue.JsonBool,
        JsonValue.JsonNull, JsonValue.JsonArray, JsonValue.JsonObject {

        record JsonString(String value) implements JsonValue {}
        record JsonNumber(double value) implements JsonValue {}
        record JsonBool(boolean value)  implements JsonValue {}
        record JsonNull()               implements JsonValue {}
        record JsonArray(List<JsonValue> elements) implements JsonValue {}
        record JsonObject(Map<String, JsonValue> fields) implements JsonValue {}
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: PATTERN MATCHING SWITCH
    // ═══════════════════════════════════════════════════════

    /**
     * Pattern Matching switch (Java 21): type-safe dispatch over sealed hierarchy.
     * when clause thay thế && guard condition.
     * Exhaustive: sealed interface → không cần default.
     */
    static String describeShape(Shape shape) {
        return switch (shape) {
            case Circle c when c.radius() > 100 ->
                String.format("HUGE circle (r=%.0f, area=%.0f)", c.radius(), c.area());
            case Circle c ->
                String.format("Circle: r=%.1f area=%.2f", c.radius(), c.area());
            case Rectangle r when r.isSquare() ->
                String.format("Square: side=%.1f area=%.2f", r.width(), r.area());
            case Rectangle r ->
                String.format("Rectangle: %.1f×%.1f area=%.2f", r.width(), r.height(), r.area());
            case Triangle t ->
                String.format("Triangle: sides=%.1f/%.1f/%.1f area=%.2f", t.a(), t.b(), t.c(), t.area());
        };
    }

    /**
     * Pattern Matching switch trên sealed hierarchy (Java 21).
     */
    static String describeJsonValue(JsonValue val) {
        return switch (val) {
            case JsonValue.JsonString s                              -> "string: \"" + s.value() + "\"";
            case JsonValue.JsonNumber n when n.value() == Math.floor(n.value()) -> "integer: " + (long) n.value();
            case JsonValue.JsonNumber n                             -> "number: " + n.value();
            case JsonValue.JsonBool b                               -> "boolean: " + b.value();
            case JsonValue.JsonNull jn                              -> "null";
            case JsonValue.JsonArray a                              -> "array[" + a.elements().size() + "]";
            case JsonValue.JsonObject o                             -> "object{" + o.fields().size() + " fields}";
        };
    }

    /**
     * instanceof Pattern Matching (Java 16).
     * Eliminates redundant cast.
     */
    static double totalArea(List<Object> objects) {
        double total = 0;
        for (Object obj : objects) {
            if (obj instanceof Circle c)            total += c.area();
            else if (obj instanceof Rectangle r)    total += r.area();
            else if (obj instanceof Triangle t)     total += t.area();
            // non-Shape objects silently skipped
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: TEXT BLOCKS
    // ═══════════════════════════════════════════════════════

    /**
     * Text Blocks: multiline strings với tự động indent stripping.
     *
     * Rules:
     *   - Opening """ on same line as assignment (or newline after)
     *   - Closing """ position determines indent stripping
     *   - Trailing whitespace stripped by default
     *   - \  = line continuation (no newline)
     *   - \s = explicit trailing space
     */
    static void demoTextBlocks() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Text Blocks");
        System.out.println("═══════════════════════════════════════════════════");

        // SQL
        String sql = """
                SELECT o.id, o.customer_id, o.total,
                       c.name, c.email
                FROM orders o
                JOIN customers c ON c.id = o.customer_id
                WHERE o.status = 'CONFIRMED'
                  AND o.created_at >= NOW() - INTERVAL '7 days'
                ORDER BY o.total DESC
                LIMIT 100
                """;
        System.out.println("[SQL]\n" + sql);

        // JSON
        String json = """
                {
                    "orderId": "ORD-001",
                    "customer": {
                        "name": "Alice",
                        "email": "alice@example.com"
                    },
                    "items": [
                        {"sku": "P-001", "qty": 2, "price": 150000},
                        {"sku": "P-002", "qty": 1, "price": 500000}
                    ],
                    "total": 800000
                }
                """;
        System.out.println("[JSON]\n" + json);

        // HTML
        String html = """
                <html>
                    <body>
                        <h1>Order Confirmation</h1>
                        <p>Your order has been confirmed.</p>
                    </body>
                </html>
                """;
        System.out.println("[HTML]\n" + html);

        // Formatted text block (with %s substitution)
        String orderId = "ORD-001";
        String customer = "Alice";
        String message = """
                Dear %s,
                Your order %s has been confirmed.
                Thank you for shopping with us!
                """.formatted(customer, orderId);
        System.out.println("[Formatted]\n" + message);

        // Line continuation with \
        String singleLine = """
                This is a very long string that \
                continues on the next line \
                without a newline character.
                """;
        System.out.println("[Line continuation] " + singleLine);

        // String comparison
        String oldStyle = "SELECT *\nFROM orders\nWHERE id = ?";
        String newStyle = """
                SELECT *
                FROM orders
                WHERE id = ?""";
        System.out.println("[Equal to old style] " + oldStyle.equals(newStyle));
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: SWITCH EXPRESSIONS
    // ═══════════════════════════════════════════════════════

    /**
     * Switch Expressions (Java 14+):
     *   - Expression: produces a value
     *   - Arrow syntax: no fallthrough, no break
     *   - yield: return value from block arm
     *   - Exhaustive: compiler checks all cases
     */
    enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }

    static int workHours(Day day) {
        return switch (day) {
            case MON, TUE, WED, THU, FRI -> 8;  // multiple labels → same result
            case SAT -> 4;
            case SUN -> 0;
        };
    }

    static String classifyDay(Day day) {
        return switch (day) {
            case SAT, SUN -> "Weekend";
            case MON, FRI -> "Near-weekend";
            default       -> "Midweek";
        };
    }

    // yield: return from block arm (when you need multiple statements)
    static String describeOrderStatus(String status) {
        return switch (status.toUpperCase()) {
            case "PENDING"   -> "Waiting for payment";
            case "CONFIRMED" -> "Payment received, processing";
            case "SHIPPED"   -> "On the way";
            case "DELIVERED" -> "Arrived";
            case "CANCELLED" -> "Order cancelled";
            default -> {
                // Block arm: multiple statements, yield for return
                String normalized = status.strip().toLowerCase();
                yield "Unknown status: " + normalized;
            }
        };
    }

    static double calculateShapeScore(Shape shape) {
        return switch (shape) {
            case Circle c                        -> c.area() / c.perimeter();
            case Rectangle r when r.isSquare()  -> 1.0;
            case Rectangle r                    -> r.area() / r.perimeter();
            case Triangle t                     -> t.area() / t.perimeter();
        };
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: COMBINING FEATURES — Real-world example
    // ═══════════════════════════════════════════════════════

    /**
     * Combining Records + Sealed + Pattern Matching + Text Blocks + Switch:
     * A mini expression evaluator / rule engine.
     */
    sealed interface Expr permits
        Expr.Num, Expr.Add, Expr.Mul, Expr.Neg, Expr.Var {
        record Num(double value)            implements Expr {}
        record Add(Expr left, Expr right)   implements Expr {}
        record Mul(Expr left, Expr right)   implements Expr {}
        record Neg(Expr operand)            implements Expr {}
        record Var(String name)             implements Expr {}
    }

    static double evaluate(Expr expr, Map<String, Double> vars) {
        return switch (expr) {
            case Expr.Num e -> e.value();
            case Expr.Var e -> vars.getOrDefault(e.name(), 0.0);
            case Expr.Neg e -> -evaluate(e.operand(), vars);
            case Expr.Add e -> evaluate(e.left(), vars) + evaluate(e.right(), vars);
            case Expr.Mul e -> evaluate(e.left(), vars) * evaluate(e.right(), vars);
        };
    }

    static String prettyPrint(Expr expr) {
        return switch (expr) {
            case Expr.Num e -> String.valueOf(e.value());
            case Expr.Var e -> e.name();
            case Expr.Neg e -> "(-" + prettyPrint(e.operand()) + ")";
            case Expr.Add e -> "(" + prettyPrint(e.left()) + " + " + prettyPrint(e.right()) + ")";
            case Expr.Mul e -> "(" + prettyPrint(e.left()) + " * " + prettyPrint(e.right()) + ")";
        };
    }

    /**
     * HTTP request/response pipeline using modern Java features.
     */
    sealed interface HttpRequest permits
        HttpRequest.Get, HttpRequest.Post, HttpRequest.Delete {
        record Get(String path, Map<String, String> headers)    implements HttpRequest {}
        record Post(String path, String body, String contentType) implements HttpRequest {}
        record Delete(String path, String reason)               implements HttpRequest {}
    }

    record HttpResponse(int status, String body) {
        boolean isSuccess() { return status >= 200 && status < 300; }
        static HttpResponse ok(String body)           { return new HttpResponse(200, body); }
        static HttpResponse created(String body)      { return new HttpResponse(201, body); }
        static HttpResponse notFound(String resource) { return new HttpResponse(404, resource + " not found"); }
        static HttpResponse badRequest(String msg)    { return new HttpResponse(400, msg); }
    }

    static HttpResponse handleRequest(HttpRequest req) {
        return switch (req) {
            case HttpRequest.Get r when r.path().startsWith("/orders") ->
                HttpResponse.ok("""
                    {"orders": [{"id": "ORD-001", "status": "CONFIRMED"}]}
                    """.strip());
            case HttpRequest.Get r when r.path().startsWith("/health") ->
                HttpResponse.ok("{\"status\": \"UP\"}");
            case HttpRequest.Get r ->
                HttpResponse.notFound(r.path());
            case HttpRequest.Post r when r.path().equals("/orders") ->
                HttpResponse.created("""
                    {"id": "ORD-NEW", "status": "PENDING"}
                    """.strip());
            case HttpRequest.Post r ->
                HttpResponse.badRequest("Unknown endpoint: " + r.path());
            case HttpRequest.Delete r ->
                new HttpResponse(204, ""); // No Content
        };
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoRecords() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Records");
        System.out.println("═══════════════════════════════════════════════════");

        Point p1 = new Point(3, 4);
        Point p2 = new Point(0, 0);
        Point p3 = p1.translate(1, 1);

        System.out.println("Points:");
        System.out.println("  p1       = " + p1);          // auto toString
        System.out.println("  origin   = " + Point.origin());
        System.out.println("  p1==p1   = " + p1.equals(new Point(3, 4)));  // auto equals
        System.out.println("  distance = " + String.format("%.2f", p1.distanceTo(p2)));
        System.out.println("  translate= " + p3);

        System.out.println("\nRecord validation:");
        try { new Point(-1, 5); }
        catch (IllegalArgumentException e) { System.out.println("  caught: " + e.getMessage()); }

        System.out.println("\nGeneric Pair:");
        Pair<String, Integer> pair = Pair.of("Alice", 42);
        System.out.println("  " + pair);
        System.out.println("  swapped: " + pair.swapped());

        System.out.println("\nMoney (Record + interface):");
        Money price = Money.of(150_000, "VND");
        Money tax   = Money.of(15_000, "VND");
        System.out.println("  price    = " + price.formatted());
        System.out.println("  total    = " + price.add(tax).formatted());
        System.out.println("  x3       = " + price.multiply(3).formatted());

        System.out.println("\nRecord used as Map key (auto hashCode):");
        Map<Point, String> grid = new HashMap<>();
        grid.put(new Point(0, 0), "origin");
        grid.put(new Point(1, 0), "right");
        grid.put(new Point(0, 1), "up");
        System.out.println("  grid.get(Point(0,0)) = " + grid.get(new Point(0, 0)));
        System.out.println("  (new Point == stored key because of auto equals/hashCode)");
    }

    static void demoSealedAndPatternMatching() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Sealed Classes + Pattern Matching");
        System.out.println("═══════════════════════════════════════════════════");

        List<Shape> shapes = List.of(
            new Circle(5, "red"),
            new Circle(150, "blue"),
            new Rectangle(4, 4, "green"),      // square!
            new Rectangle(3, 7, "yellow"),
            new Triangle(3, 4, 5, "purple")
        );

        System.out.println("Shape descriptions:");
        shapes.forEach(s -> System.out.println("  " + describeShape(s)));

        System.out.println("\nTotal area: " + String.format("%.2f", shapes.stream().mapToDouble(Shape::area).sum()));
        System.out.println("Largest: " + shapes.stream()
            .max(Comparator.comparingDouble(Shape::area))
            .map(s -> describeShape(s)).orElse("none"));

        System.out.println("\nJsonValue dispatch (instanceof pattern):");
        List<JsonValue> jsons = List.of(
            new JsonValue.JsonString("hello"),
            new JsonValue.JsonNumber(42.0),
            new JsonValue.JsonNumber(3.14),
            new JsonValue.JsonBool(true),
            new JsonValue.JsonNull(),
            new JsonValue.JsonArray(List.of(new JsonValue.JsonNumber(1), new JsonValue.JsonNumber(2))),
            new JsonValue.JsonObject(Map.of("key", new JsonValue.JsonString("value")))
        );
        jsons.forEach(j -> System.out.println("  " + describeJsonValue(j)));

        System.out.println("\nResult type (sealed error handling):");
        Result<Integer> ok   = Result.of(() -> Integer.parseInt("42"));
        Result<Integer> fail = Result.of(() -> Integer.parseInt("not-a-number"));

        System.out.println("  parse('42')          : " + ok.isSuccess() + " → " + ok.getOrElse(-1));
        System.out.println("  parse('not-a-number'): " + fail.isSuccess() + " → " + fail.getOrElse(-1));

        // Chaining
        Result<String> chained = ok
            .map(n -> n * 2)
            .map(n -> "Result: " + n);
        System.out.println("  chained map          : " + chained.getOrElse("error"));

        // switch pattern matching on Result
        String msg = switch (fail) {
            case Result.Success<?> s -> "Got: " + s.value();
            case Result.Failure<?> f -> "Failed: " + f.error();
        };
        System.out.println("  switch on Result     : " + msg);
    }

    static void demoSwitchExpressions() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Switch Expressions");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("Work hours per day:");
        for (Day d : Day.values()) {
            System.out.printf("  %-3s: %d hours  [%s]%n", d, workHours(d), classifyDay(d));
        }

        System.out.println("\nOrder status descriptions:");
        List.of("PENDING", "CONFIRMED", "shipped", "  cancelled  ", "UNKNOWN")
            .forEach(s -> System.out.println("  '" + s + "' → " + describeOrderStatus(s)));

        System.out.println("\nShape scores (area/perimeter ratio):");
        List<Shape> shapes = List.of(
            new Circle(10, "red"),
            new Rectangle(5, 5, "green"),
            new Rectangle(3, 10, "blue"),
            new Triangle(3, 4, 5, "yellow")
        );
        shapes.forEach(s -> System.out.printf("  %-40s score=%.3f%n",
            describeShape(s), calculateShapeScore(s)));
    }

    static void demoCombined() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Combining Features — Expression Evaluator");
        System.out.println("═══════════════════════════════════════════════════");

        // Build expression: (x + 2) * (y - 1)
        Expr expr = new Expr.Mul(
            new Expr.Add(new Expr.Var("x"), new Expr.Num(2)),
            new Expr.Add(new Expr.Var("y"), new Expr.Neg(new Expr.Num(1)))
        );

        System.out.println("Expression: " + prettyPrint(expr));
        Map<String, Double> vars1 = Map.of("x", 3.0, "y", 5.0);
        Map<String, Double> vars2 = Map.of("x", 0.0, "y", 1.0);
        System.out.println("  x=3, y=5 → " + evaluate(expr, vars1));  // (3+2)*(5-1) = 20
        System.out.println("  x=0, y=1 → " + evaluate(expr, vars2));  // (0+2)*(1-1) = 0

        System.out.println("\nHTTP Request Handler:");
        List<HttpRequest> requests = List.of(
            new HttpRequest.Get("/orders/123", Map.of("Accept", "application/json")),
            new HttpRequest.Get("/health", Map.of()),
            new HttpRequest.Get("/unknown", Map.of()),
            new HttpRequest.Post("/orders", "{\"item\":\"laptop\"}", "application/json"),
            new HttpRequest.Delete("/orders/123", "customer request")
        );

        requests.forEach(req -> {
            HttpResponse resp = handleRequest(req);
            String path = switch (req) {
                case HttpRequest.Get r    -> r.path();
                case HttpRequest.Post r   -> r.path();
                case HttpRequest.Delete r -> r.path();
            };
            System.out.printf("  %-45s → %d %s%n",
                req.getClass().getSimpleName() + "(" + path + ")",
                resp.status(),
                resp.body().length() > 40 ? resp.body().substring(0, 40) + "..." : resp.body()
            );
        });
    }

    static void demoJavaVersionComparison() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Java 8 vs Java 21 — Same Logic");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            CASE: Represent a Shape hierarchy and compute area.

            ─── Java 8 way ────────────────────────────────────
            abstract class Shape {
                abstract double area();
            }
            class Circle extends Shape {
                private final double radius;
                Circle(double r) { this.radius = r; }
                double getRadius() { return radius; }
                @Override double area() { return Math.PI * radius * radius; }
                @Override public boolean equals(Object o) { ... } // 8 lines
                @Override public int hashCode() { ... }
                @Override public String toString() { return "Circle[r=" + radius + "]"; }
            }
            // ... Rectangle, Triangle similar boilerplate

            double computeArea(Shape s) {
                if (s instanceof Circle) {
                    Circle c = (Circle) s;   // redundant cast
                    return c.area();
                } else if (s instanceof Rectangle) {
                    Rectangle r = (Rectangle) s;
                    return r.area();
                } else {
                    throw new IllegalArgumentException("Unknown: " + s);
                    // ↑ compiler doesn't know if all cases covered!
                }
            }

            ─── Java 21 way ───────────────────────────────────
            sealed interface Shape permits Circle, Rectangle, Triangle {}
            record Circle(double radius) implements Shape {
                double area() { return Math.PI * radius * radius; }
            }
            // ... 1-liner records for Rectangle, Triangle

            double computeArea(Shape s) {
                return switch (s) {          // exhaustive — no default needed
                    case Circle c    -> c.area();
                    case Rectangle r -> r.area();
                    case Triangle t  -> t.area();
                };
                // If you add Triangle to sealed hierarchy but forget switch arm
                // → COMPILE ERROR (not runtime!)
            }

            BENEFITS:
              ✅ Records: zero boilerplate (equals/hashCode/toString free)
              ✅ Sealed: compiler enforces exhaustive handling
              ✅ Pattern matching: no cast, no ClassCastException
              ✅ Switch expression: expression not statement, no fallthrough
              ✅ Guarded patterns: when clause for conditional matching
              ✅ Record patterns: destructure nested records inline
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 10.1 — MODERN JAVA FEATURES (Java 14–21)   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoRecords();
        demoSealedAndPatternMatching();
        demoSwitchExpressions();
        demoTextBlocks();
        demoCombined();
        demoJavaVersionComparison();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 10.1                               ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  RECORDS: 1 dòng thay 30+ dòng boilerplate       ║");
        System.out.println("║    Compact constructor = validation/normalization ║");
        System.out.println("║    Immutable → safe as Map key, Value Object     ║");
        System.out.println("║                                                   ║");
        System.out.println("║  SEALED: giới hạn subtypes, compiler exhaustive  ║");
        System.out.println("║    Kết hợp records → algebraic data types        ║");
        System.out.println("║    sealed Result<T> permits Success, Failure     ║");
        System.out.println("║                                                   ║");
        System.out.println("║  PATTERN MATCHING instanceof (Java 16):          ║");
        System.out.println("║    if (obj instanceof Type t) → no cast needed   ║");
        System.out.println("║    if (obj instanceof Type t && guard) → guarded ║");
        System.out.println("║                                                   ║");
        System.out.println("║  TEXT BLOCKS: multiline, indent-stripped,        ║");
        System.out.println("║    .formatted() for substitution                 ║");
        System.out.println("║                                                   ║");
        System.out.println("║  SWITCH EXPRESSION: produces value,              ║");
        System.out.println("║    arrow = no fallthrough, yield from block arm  ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
