package org.example.patterns;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 4.2 — Structural Patterns thực chiến
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Decorator — thêm behaviour mà không sửa class gốc (Open/Closed Principle)
 *   2. Proxy — JDK Dynamic Proxy vs CGLIB, lazy loading, access control, logging
 *   3. Adapter — tích hợp legacy API không thể sửa
 *   4. Facade — đơn giản hoá subsystem phức tạp
 *   5. Composite — xử lý tree structure đồng nhất (cây thư mục, org chart)
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.patterns.StructuralPatternsDemo"
 * ============================================================
 */
public class StructuralPatternsDemo {

    public static void main(String[] args) {
        System.out.println("=== BÀI 4.2: Structural Patterns ===\n");

        demo1_Decorator();
        demo2_DynamicProxy();
        demo3_Adapter();
        demo4_Facade();
        demo5_Composite();

        System.out.println("\n=== KẾT THÚC BÀI 4.2 ===");
    }

    // ================================================================
    // DEMO 1: Decorator — Thêm behaviour không sửa class gốc
    // ================================================================

    /**
     * Decorator bọc object gốc, thêm behaviour trước/sau khi delegate.
     * Khác Inheritance: Decorator compose tại runtime, không phải compile time.
     *
     * NGUYÊN TẮC: Open for extension, Closed for modification (OCP).
     *   Không sửa DataStore → thêm Caching, Logging, Retry bằng cách wrap.
     *
     * THỰC TẾ TRONG JAVA:
     *   BufferedReader(FileReader)        — I/O decorator chain
     *   Collections.unmodifiableList()    — decorator thêm read-only constraint
     *   Spring @Transactional, @Cacheable — AOP = dynamic decorator
     *   HttpClient interceptors           — decorator chain cho HTTP
     *
     * DECORATOR vs INHERITANCE:
     *   Inheritance: kết hợp cứng tại compile time, explosion of subclasses
     *   Decorator:   kết hợp linh hoạt tại runtime, mix-and-match
     *
     * SA INSIGHT: Decorator là nền tảng của AOP (Aspect-Oriented Programming).
     *   Spring AOP tạo proxy (dynamic decorator) quanh bean của bạn.
     *   Hiểu Decorator = hiểu tại sao @Transactional không work khi gọi internal method.
     *   (Internal call bypasses proxy → bypasses decorator → no transaction!)
     */
    static void demo1_Decorator() {
        System.out.println("--- DEMO 1: Decorator ---");

        // Base implementation
        DataStore plain = new FileDataStore();
        System.out.println("  Plain store:");
        plain.save("user:1", "{\"name\":\"Alice\"}");
        System.out.println("  " + plain.load("user:1"));

        // Decorator stack: Retry → Cache → Logging → FileDataStore
        // Thứ tự wrap = thứ tự thực hiện từ ngoài vào trong
        DataStore decorated = new RetryDecorator(
            new CachingDecorator(
                new LoggingDecorator(
                    plain
                )
            ), 3
        );

        System.out.println("\n  Decorated store (Retry → Cache → Logging → File):");
        decorated.save("user:2", "{\"name\":\"Bob\"}");
        System.out.println("  First load (cache miss):  " + decorated.load("user:2"));
        System.out.println("  Second load (cache hit):  " + decorated.load("user:2")); // Cache hit

        // I/O Decorator — giống java.io
        System.out.println("\n  I/O Decorator chain (giống BufferedReader(InputStreamReader(...))):");
        TextProcessor processor = new TrimDecorator(
            new UpperCaseDecorator(
                new PunctuationDecorator(
                    text -> text // base: identity
                )
            )
        );
        String result = processor.process("  hello world  ");
        System.out.println("  process(\"  hello world  \") → \"" + result + "\"");

        // Functional decorator — dùng Function.andThen() / compose()
        System.out.println("\n  Functional Decorator (Function.andThen):");
        Function<String, String> pipeline = Function.<String>identity()
            .andThen(String::trim)
            .andThen(String::toUpperCase)
            .andThen(s -> s + "!");
        System.out.println("  pipeline.apply(\"  hello  \") → \"" + pipeline.apply("  hello  ") + "\"");
        System.out.println();
    }

    interface DataStore {
        void save(String key, String value);
        String load(String key);
    }

    static class FileDataStore implements DataStore {
        private final Map<String, String> storage = new HashMap<>();
        @Override public void save(String k, String v) { storage.put(k, v); }
        @Override public String load(String k) { return storage.getOrDefault(k, null); }
    }

    // Base decorator — chứa reference tới component được bọc
    static abstract class DataStoreDecorator implements DataStore {
        protected final DataStore wrapped;
        DataStoreDecorator(DataStore wrapped) { this.wrapped = wrapped; }
    }

    static class LoggingDecorator extends DataStoreDecorator {
        LoggingDecorator(DataStore w) { super(w); }
        @Override public void save(String k, String v) {
            System.out.println("  [LOG] save key=" + k);
            wrapped.save(k, v);
        }
        @Override public String load(String k) {
            System.out.println("  [LOG] load key=" + k);
            return wrapped.load(k);
        }
    }

    static class CachingDecorator extends DataStoreDecorator {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        CachingDecorator(DataStore w) { super(w); }
        @Override public void save(String k, String v) { cache.put(k, v); wrapped.save(k, v); }
        @Override public String load(String k) {
            return cache.computeIfAbsent(k, key -> {
                System.out.println("  [CACHE] miss → delegate to store");
                return wrapped.load(key);
            });
        }
    }

    static class RetryDecorator extends DataStoreDecorator {
        private final int maxRetries;
        RetryDecorator(DataStore w, int retries) { super(w); this.maxRetries = retries; }
        @Override public void save(String k, String v) {
            for (int i = 1; i <= maxRetries; i++) {
                try { wrapped.save(k, v); return; }
                catch (Exception e) { if (i == maxRetries) throw e; }
            }
        }
        @Override public String load(String k) {
            for (int i = 1; i <= maxRetries; i++) {
                try { return wrapped.load(k); }
                catch (Exception e) { if (i == maxRetries) throw e; }
            }
            return null;
        }
    }

    interface TextProcessor { String process(String text); }
    static class UpperCaseDecorator implements TextProcessor {
        private final TextProcessor w; UpperCaseDecorator(TextProcessor w) { this.w = w; }
        @Override public String process(String t) { return w.process(t).toUpperCase(); }
    }
    static class TrimDecorator implements TextProcessor {
        private final TextProcessor w; TrimDecorator(TextProcessor w) { this.w = w; }
        @Override public String process(String t) { return w.process(t.trim()); }
    }
    static class PunctuationDecorator implements TextProcessor {
        private final TextProcessor w; PunctuationDecorator(TextProcessor w) { this.w = w; }
        @Override public String process(String t) { return w.process(t) + "!"; }
    }

    // ================================================================
    // DEMO 2: Dynamic Proxy — JDK Proxy và ứng dụng thực tế
    // ================================================================

    /**
     * PROXY: Đối tượng đại diện kiểm soát truy cập vào object gốc (subject).
     *
     * 3 LOẠI PROXY:
     *   Virtual Proxy:    Lazy init — chỉ tạo subject khi thực sự cần (expensive resource)
     *   Protection Proxy: Access control — kiểm tra permission trước khi delegate
     *   Remote Proxy:     RPC — gọi method trên object ở máy khác (gRPC stub, EJB)
     *
     * JDK DYNAMIC PROXY (java.lang.reflect.Proxy):
     *   - Chỉ proxy được INTERFACE (không phải class)
     *   - Tạo class mới tại runtime implement interface
     *   - InvocationHandler.invoke() intercept mọi method call
     *   - Spring AOP dùng JDK Proxy cho interface-based bean
     *
     * CGLIB PROXY (Spring dùng khi không có interface):
     *   - Subclass concrete class tại runtime (bytecode generation)
     *   - Không cần interface
     *   - Không proxy được final class/method
     *   - Spring dùng CGLIB khi bean không implement interface
     *
     * SA INSIGHT: @Transactional trong Spring = proxy pattern.
     *   Khi bạn inject UserService, Spring inject proxy của UserService.
     *   Proxy intercept method call → begin TX → call real method → commit/rollback.
     *   "self-invocation" problem: gọi @Transactional từ trong cùng class
     *   → gọi trực tiếp object gốc, KHÔNG qua proxy → KHÔNG có transaction!
     */
    static void demo2_DynamicProxy() {
        System.out.println("--- DEMO 2: Dynamic Proxy ---");

        // 1. Logging Proxy — intercept mọi method, log trước/sau
        UserService realService = new UserServiceImpl();
        UserService loggingProxy = createLoggingProxy(UserService.class, realService);

        System.out.println("  Logging Proxy (mọi method đều được log):");
        loggingProxy.createUser("alice", "alice@example.com");
        loggingProxy.getUserById(42L);
        loggingProxy.deleteUser(42L);

        // 2. Timing Proxy — đo thời gian mỗi method
        System.out.println("\n  Timing Proxy:");
        UserService timingProxy = createTimingProxy(UserService.class, realService);
        timingProxy.createUser("bob", "bob@example.com");
        timingProxy.getUserById(1L);

        // 3. Access Control Proxy
        System.out.println("\n  Access Control Proxy:");
        UserService adminProxy = createAccessProxy(UserService.class, realService, "ADMIN");
        UserService guestProxy = createAccessProxy(UserService.class, realService, "GUEST");

        adminProxy.createUser("charlie", "charlie@example.com"); // OK
        adminProxy.deleteUser(1L);                               // OK
        try {
            guestProxy.deleteUser(1L); // Blocked!
        } catch (SecurityException e) {
            System.out.println("  GUEST bị chặn deleteUser: " + e.getMessage());
        }
        guestProxy.getUserById(1L); // OK — read allowed

        // 4. Virtual Proxy — lazy init expensive resource
        System.out.println("\n  Virtual Proxy (lazy init):");
        HeavyReport report = createLazyProxy(HeavyReport.class, () -> new RealHeavyReport());
        System.out.println("  Proxy tạo xong — report object CHƯA được tạo");
        System.out.println("  Gọi getTitle(): " + report.getTitle()); // Lúc này mới init
        System.out.println("  Gọi getTitle() lần 2: " + report.getTitle()); // Reuse
        System.out.println();
    }

    interface UserService {
        void createUser(String name, String email);
        String getUserById(long id);
        void deleteUser(long id);
    }

    static class UserServiceImpl implements UserService {
        @Override public void createUser(String name, String email) {
            sleep(20); // Giả lập DB write
        }
        @Override public String getUserById(long id) {
            sleep(10); return "User{id=" + id + ", name=mock}";
        }
        @Override public void deleteUser(long id) { sleep(15); }
    }

    @SuppressWarnings("unchecked")
    static <T> T createLoggingProxy(Class<T> iface, T target) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
            (proxy, method, args) -> {
                System.out.printf("  [LOG] → %s(%s)%n", method.getName(),
                    args == null ? "" : Arrays.toString(args));
                Object result = method.invoke(target, args);
                System.out.printf("  [LOG] ← %s = %s%n", method.getName(), result);
                return result;
            });
    }

    @SuppressWarnings("unchecked")
    static <T> T createTimingProxy(Class<T> iface, T target) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
            (proxy, method, args) -> {
                long start = System.currentTimeMillis();
                Object result = method.invoke(target, args);
                System.out.printf("  [TIMER] %s took %dms%n",
                    method.getName(), System.currentTimeMillis() - start);
                return result;
            });
    }

    static final Set<String> ADMIN_ONLY = Set.of("deleteUser", "createUser");

    @SuppressWarnings("unchecked")
    static <T> T createAccessProxy(Class<T> iface, T target, String role) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
            (proxy, method, args) -> {
                if (ADMIN_ONLY.contains(method.getName()) && !"ADMIN".equals(role))
                    throw new SecurityException(role + " không có quyền " + method.getName());
                return method.invoke(target, args);
            });
    }

    interface HeavyReport { String getTitle(); String getData(); }

    static class RealHeavyReport implements HeavyReport {
        RealHeavyReport() { System.out.println("  [INIT] RealHeavyReport — expensive init (DB, file, ...)"); }
        @Override public String getTitle() { return "Q4 Financial Report"; }
        @Override public String getData()  { return "...large dataset..."; }
    }

    @SuppressWarnings("unchecked")
    static <T> T createLazyProxy(Class<T> iface, Supplier<T> factory) {
        Object[] holder = new Object[1]; // holder[0] = lazy instance
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
            (proxy, method, args) -> {
                if (holder[0] == null) holder[0] = factory.get(); // init on first call
                return method.invoke(holder[0], args);
            });
    }

    // ================================================================
    // DEMO 3: Adapter — Tích hợp API không thể sửa
    // ================================================================

    /**
     * Adapter chuyển đổi interface của class này thành interface khác mà client mong đợi.
     * Dùng khi: tích hợp third-party library, legacy system, incompatible interfaces.
     *
     * 2 LOẠI:
     *   Object Adapter: compose object cần adapt (flexible — có thể adapt subclass)
     *   Class Adapter:  extends class cần adapt (Java: ít dùng vì single inheritance)
     *
     * THỰC TẾ:
     *   Arrays.asList()            — adapter: Array → List
     *   Collections.enumeration()  — adapter: Iterator → Enumeration
     *   InputStreamReader          — adapter: InputStream (bytes) → Reader (chars)
     *   SLF4J                      — adapter: cùng 1 API, log4j/logback/jul bên dưới
     *
     * SA INSIGHT: Anti-corruption Layer trong DDD dùng Adapter pattern.
     *   Khi integrate với legacy system hoặc external service:
     *   Domain model ↔ Adapter ↔ Legacy API
     *   Adapter giữ domain model sạch, không bị "ô nhiễm" bởi external model.
     */
    static void demo3_Adapter() {
        System.out.println("--- DEMO 3: Adapter ---");

        // Legacy payment system — không thể sửa
        LegacyPaymentGateway legacy = new LegacyPaymentGateway();

        // Client mong đợi PaymentProcessor interface
        PaymentProcessor processor = new LegacyPaymentAdapter(legacy);

        System.out.println("  Adapter: LegacyPaymentGateway → PaymentProcessor");
        PaymentResult r1 = processor.charge("card-123", 150.00, "USD");
        System.out.println("  charge(): " + r1);
        PaymentResult r2 = processor.refund("txn-xyz", 50.00);
        System.out.println("  refund(): " + r2);

        // Two-way adapter — convert giữa 2 format
        System.out.println("\n  Two-way Adapter: JSON ↔ XML:");
        DataFormat jsonFormat = new JsonFormat();
        DataFormat xmlAdapter = new JsonToXmlAdapter(jsonFormat);

        String json = "{\"name\":\"Alice\",\"age\":30}";
        String xml  = xmlAdapter.serialize(Map.of("name", "Bob", "age", "25"));
        System.out.println("  JsonToXmlAdapter.serialize() → " + xml);
        Map<String, Object> parsed = xmlAdapter.deserialize("<name>Alice</name><age>30</age>");
        System.out.println("  JsonToXmlAdapter.deserialize() → " + parsed);

        // Adapter cho metrics system
        System.out.println("\n  Metrics Adapter (Micrometer → Prometheus):");
        MetricsCollector micrometer = new MicrometerAdapter(new PrometheusRegistry());
        micrometer.increment("http.requests", Map.of("method", "GET", "status", "200"));
        micrometer.gauge("jvm.heap.used", 256.5, Map.of("area", "heap"));
        micrometer.timing("db.query.duration", 45, Map.of("query", "findUser"));
        System.out.println();
    }

    // Target interface — client code dùng cái này
    interface PaymentProcessor {
        PaymentResult charge(String paymentMethodId, double amount, String currency);
        PaymentResult refund(String transactionId, double amount);
    }

    record PaymentResult(boolean success, String transactionId, String message) {
        @Override public String toString() {
            return String.format("PaymentResult{success=%s, txn=%s, msg=%s}", success, transactionId, message);
        }
    }

    // Adaptee — legacy code, không thể sửa
    static class LegacyPaymentGateway {
        String processPayment(String cardNum, int amountCents, String curr) {
            return "TXN-" + Math.abs(cardNum.hashCode() % 10000); // fake txn ID
        }
        boolean reversePayment(String txnId, int amountCents) { return true; }
    }

    // Object Adapter — wraps LegacyPaymentGateway
    static class LegacyPaymentAdapter implements PaymentProcessor {
        private final LegacyPaymentGateway gateway;
        LegacyPaymentAdapter(LegacyPaymentGateway gw) { this.gateway = gw; }

        @Override public PaymentResult charge(String pmId, double amount, String currency) {
            int cents = (int)(amount * 100); // USD → cents conversion
            String txnId = gateway.processPayment(pmId, cents, currency);
            return new PaymentResult(txnId != null, txnId, "Charged " + amount + " " + currency);
        }

        @Override public PaymentResult refund(String txnId, double amount) {
            boolean ok = gateway.reversePayment(txnId, (int)(amount * 100));
            return new PaymentResult(ok, txnId, ok ? "Refunded " + amount : "Refund failed");
        }
    }

    interface DataFormat {
        String serialize(Map<String, Object> data);
        Map<String, Object> deserialize(String raw);
    }

    static class JsonFormat implements DataFormat {
        @Override public String serialize(Map<String, Object> data) {
            StringBuilder sb = new StringBuilder("{");
            data.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
            if (sb.length() > 1) sb.deleteCharAt(sb.length() - 1);
            return sb.append("}").toString();
        }
        @Override public Map<String, Object> deserialize(String raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            // simplified JSON parse for demo
            String cleaned = raw.replaceAll("[{}\"\\s]", "");
            for (String pair : cleaned.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) result.put(kv[0], kv[1]);
            }
            return result;
        }
    }

    static class JsonToXmlAdapter implements DataFormat {
        private final JsonFormat json;
        JsonToXmlAdapter(JsonFormat j) { this.json = j; }
        @Override public String serialize(Map<String, Object> data) {
            String jsonStr = json.serialize(data);
            // Convert JSON → XML (simplified)
            return jsonStr.replaceAll("\"(\\w+)\":\"([^\"]+)\"", "<$1>$2</$1>")
                          .replaceAll("[{},]", "").trim();
        }
        @Override public Map<String, Object> deserialize(String xml) {
            // Convert XML → JSON then parse
            String jsonStr = xml.replaceAll("<(\\w+)>([^<]+)<\\/\\w+>", "\"$1\":\"$2\"");
            return json.deserialize("{" + jsonStr + "}");
        }
    }

    interface MetricsCollector {
        void increment(String metric, Map<String, String> tags);
        void gauge(String metric, double value, Map<String, String> tags);
        void timing(String metric, long ms, Map<String, String> tags);
    }

    static class PrometheusRegistry {
        void counter(String name, double v, String... labels) {
            System.out.println("  [Prometheus] counter{" + name + ", " + Arrays.toString(labels) + "} += " + v);
        }
        void gauge(String name, double v, String... labels) {
            System.out.println("  [Prometheus] gauge{" + name + "} = " + v);
        }
        void histogram(String name, long ms, String... labels) {
            System.out.println("  [Prometheus] histogram{" + name + "} observe " + ms + "ms");
        }
    }

    static class MicrometerAdapter implements MetricsCollector {
        private final PrometheusRegistry registry;
        MicrometerAdapter(PrometheusRegistry r) { this.registry = r; }
        @Override public void increment(String m, Map<String, String> tags) {
            registry.counter(m, 1.0, tags.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        }
        @Override public void gauge(String m, double v, Map<String, String> tags) {
            registry.gauge(m, v);
        }
        @Override public void timing(String m, long ms, Map<String, String> tags) {
            registry.histogram(m, ms);
        }
    }

    // ================================================================
    // DEMO 4: Facade — Đơn giản hoá subsystem phức tạp
    // ================================================================

    /**
     * Facade cung cấp interface đơn giản hoá cho subsystem phức tạp.
     * Client chỉ cần biết Facade, không cần biết các class bên trong.
     *
     * KHÔNG PHẢI GOD OBJECT: Facade không implement logic — nó chỉ coordinate.
     *   Subsystem classes vẫn tồn tại và dùng trực tiếp được nếu cần.
     *
     * THỰC TẾ:
     *   SLF4J Logger           — facade cho Log4j/Logback/JUL
     *   Spring JdbcTemplate    — facade cho JDBC connection/statement/resultset
     *   Hibernate Session      — facade cho SQL, mapping, caching
     *   java.net.URL.openStream() — facade cho socket, protocol, stream
     *
     * Facade vs Adapter:
     *   Adapter: convert interface (1 class → 1 interface)
     *   Facade:  simplify (nhiều class → 1 simple interface)
     *
     * SA INSIGHT: Facade thường là entry point của module/microservice.
     *   Application Service trong DDD = Facade cho domain model.
     *   Orchestrates multiple domain services, repositories, events.
     */
    static void demo4_Facade() {
        System.out.println("--- DEMO 4: Facade ---");

        // Video conversion subsystem — phức tạp, nhiều step
        System.out.println("  VideoConverter Facade (ẩn AudioDecoder, VideoEncoder, BitrateReader, ...):");
        VideoConverter converter = new VideoConverter();
        String result = converter.convert("movie.mkv", "mp4");
        System.out.println("  Kết quả: " + result);

        // OrderFulfillment Facade — orchestrate nhiều service
        System.out.println("\n  OrderFulfillment Facade:");
        OrderFulfillmentFacade fulfillment = new OrderFulfillmentFacade(
            new InventoryService(),
            new PaymentService(),
            new ShippingService(),
            new NotificationService()
        );
        FulfillmentResult order = fulfillment.placeOrder("user-42", "prod-99", 2, "card-123");
        System.out.println("  " + order);
        System.out.println();
    }

    // Subsystem classes — complex, low-level
    static class VideoCodec  { String name; VideoCodec(String n)  { name = n; } }
    static class AudioDecoder {
        String decode(String file) { return "decoded audio from " + file; }
    }
    static class VideoEncoder {
        String encode(VideoCodec codec, String audio) {
            return "encoded with " + codec.name + " [" + audio.substring(0, 15) + "...]";
        }
    }
    static class BitrateReader {
        int read(String file, VideoCodec codec) { return 1080; }
    }

    // Facade — single simple interface
    static class VideoConverter {
        private final AudioDecoder audioDecoder = new AudioDecoder();
        private final VideoEncoder videoEncoder = new VideoEncoder();
        private final BitrateReader bitrateReader = new BitrateReader();

        String convert(String filename, String format) {
            System.out.println("  [1] Read bitrate...");
            VideoCodec codec = new VideoCodec(format.equals("mp4") ? "H.264" : "VP9");
            bitrateReader.read(filename, codec);
            System.out.println("  [2] Decode audio...");
            String audio = audioDecoder.decode(filename);
            System.out.println("  [3] Encode video...");
            String encoded = videoEncoder.encode(codec, audio);
            return filename.replace(".mkv", "." + format) + " (" + encoded + ")";
        }
    }

    static class InventoryService {
        boolean reserve(String productId, int qty) {
            System.out.println("  [Inventory] Reserved " + qty + "x " + productId);
            return true;
        }
    }
    static class PaymentService {
        String charge(String userId, double amount) {
            System.out.println("  [Payment] Charged $" + amount + " for " + userId);
            return "TXN-" + ThreadLocalRandom.current().nextInt(10000);
        }
    }
    static class ShippingService {
        String ship(String userId, String productId) {
            System.out.println("  [Shipping] Shipped " + productId + " to " + userId);
            return "SHIP-" + ThreadLocalRandom.current().nextInt(10000);
        }
    }
    static class NotificationService {
        void notify(String userId, String msg) {
            System.out.println("  [Notification] → " + userId + ": " + msg);
        }
    }

    record FulfillmentResult(boolean success, String txnId, String trackingId) {
        @Override public String toString() {
            return "Fulfillment{success=" + success + ", txn=" + txnId + ", tracking=" + trackingId + "}";
        }
    }

    static class OrderFulfillmentFacade {
        private final InventoryService inventory;
        private final PaymentService payment;
        private final ShippingService shipping;
        private final NotificationService notification;

        OrderFulfillmentFacade(InventoryService i, PaymentService p, ShippingService s, NotificationService n) {
            this.inventory = i; this.payment = p; this.shipping = s; this.notification = n;
        }

        // Client chỉ gọi 1 method này — không biết gì về các service bên trong
        FulfillmentResult placeOrder(String userId, String productId, int qty, String paymentMethod) {
            if (!inventory.reserve(productId, qty))
                return new FulfillmentResult(false, null, null);
            String txnId = payment.charge(userId, qty * 29.99);
            String trackingId = shipping.ship(userId, productId);
            notification.notify(userId, "Order confirmed! Tracking: " + trackingId);
            return new FulfillmentResult(true, txnId, trackingId);
        }
    }

    // ================================================================
    // DEMO 5: Composite — Xử lý tree structure đồng nhất
    // ================================================================

    /**
     * Composite: xử lý object đơn lẻ và nhóm object theo cùng 1 interface.
     * Client không cần phân biệt leaf node và composite node.
     *
     * CẤU TRÚC:
     *   Component (interface): operation()
     *   Leaf:      implements Component, không có children
     *   Composite: implements Component, chứa List<Component>, delegate xuống children
     *
     * THỰC TẾ:
     *   File system:  File (leaf) + Directory (composite) — cùng interface
     *   UI:           Widget (leaf) + Panel (composite) — render(), resize()
     *   Organization: Employee (leaf) + Department (composite) — getSalary()
     *   Expression tree: Literal + BinaryOp — evaluate()
     *
     * SA INSIGHT: Composite + Visitor = cách xử lý AST (Abstract Syntax Tree).
     *   Compiler, query optimizer, rule engine đều dùng pattern này.
     */
    static void demo5_Composite() {
        System.out.println("--- DEMO 5: Composite ---");

        // File system tree
        FileComponent root = new Directory("root")
            .add(new Directory("src")
                .add(new File("Main.java", 4200))
                .add(new File("Config.java", 1800))
                .add(new Directory("util")
                    .add(new File("StringUtils.java", 2100))
                    .add(new File("DateUtils.java", 1500))
                )
            )
            .add(new Directory("test")
                .add(new File("MainTest.java", 3200))
            )
            .add(new File("pom.xml", 850));

        System.out.println("  File System Tree:");
        root.print("");
        System.out.printf("  Total size: %,d bytes%n", root.getSize());

        // Organization chart
        System.out.println("\n  Organization Chart:");
        OrgUnit company = new Department("Acme Corp", 0)
            .add(new Department("Engineering", 0)
                .add(new EmployeeUnit("Alice (Lead)", 120000))
                .add(new EmployeeUnit("Bob", 90000))
                .add(new EmployeeUnit("Charlie", 85000))
            )
            .add(new Department("Marketing", 0)
                .add(new EmployeeUnit("Dave (Head)", 100000))
                .add(new EmployeeUnit("Eve", 75000))
            )
            .add(new EmployeeUnit("CEO Frank", 250000));

        company.print("");
        System.out.printf("  Total payroll: $%,.0f/year%n", company.getTotalSalary());

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 4.2 ===");
        System.out.println("  ✓ Decorator: thêm behaviour không sửa class, compose tại runtime");
        System.out.println("  ✓ Proxy: JDK Proxy intercept interface method → logging/timing/auth");
        System.out.println("  ✓             Virtual Proxy: lazy init | Protection: access control");
        System.out.println("  ✓ Adapter: convert interface → tích hợp legacy/third-party");
        System.out.println("  ✓ Facade: đơn giản hoá subsystem → Application Service trong DDD");
        System.out.println("  ✓ Composite: leaf + container cùng interface → xử lý tree đồng nhất");
        System.out.println("  → Bài tiếp: 4.3 BehavioralPatternsDemo — Observer, Strategy, Command, Chain");
    }

    // Composite: File System
    interface FileComponent {
        void print(String indent);
        long getSize();
    }

    static class File implements FileComponent {
        private final String name; private final long size;
        File(String name, long size) { this.name = name; this.size = size; }
        @Override public void print(String indent) {
            System.out.printf("%s📄 %s (%,d bytes)%n", indent, name, size);
        }
        @Override public long getSize() { return size; }
    }

    static class Directory implements FileComponent {
        private final String name;
        private final List<FileComponent> children = new ArrayList<>();
        Directory(String name) { this.name = name; }
        Directory add(FileComponent c) { children.add(c); return this; }
        @Override public void print(String indent) {
            System.out.println(indent + "📁 " + name + "/");
            children.forEach(c -> c.print(indent + "  "));
        }
        @Override public long getSize() { return children.stream().mapToLong(FileComponent::getSize).sum(); }
    }

    // Composite: Organization
    interface OrgUnit {
        void print(String indent);
        double getTotalSalary();
    }

    record EmployeeUnit(String name, double salary) implements OrgUnit {
        @Override public void print(String indent) {
            System.out.printf("%s👤 %s ($%,.0f)%n", indent, name, salary);
        }
        @Override public double getTotalSalary() { return salary; }
    }

    static class Department implements OrgUnit {
        private final String name;
        private final List<OrgUnit> members = new ArrayList<>();
        Department(String name, double ignored) { this.name = name; }
        Department add(OrgUnit u) { members.add(u); return this; }
        @Override public void print(String indent) {
            System.out.printf("%s🏢 %s (team=%d, payroll=$%,.0f)%n",
                indent, name, members.size(), getTotalSalary());
            members.forEach(m -> m.print(indent + "  "));
        }
        @Override public double getTotalSalary() {
            return members.stream().mapToDouble(OrgUnit::getTotalSalary).sum();
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
