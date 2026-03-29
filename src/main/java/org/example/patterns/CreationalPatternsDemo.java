package org.example.patterns;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * ============================================================
 * BÀI 4.1 — Creational Patterns thực chiến
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Singleton — 5 cách implement, chỉ 2 cách đúng
 *   2. Builder — fluent API, validation, immutable object
 *   3. Factory Method — tách logic tạo object khỏi business logic
 *   4. Abstract Factory — tạo family of objects nhất quán
 *   5. Prototype — clone object thay vì new, shallow vs deep copy
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.patterns.CreationalPatternsDemo"
 * ============================================================
 */
public class CreationalPatternsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 4.1: Creational Patterns ===\n");

        demo1_Singleton();
        demo2_Builder();
        demo3_FactoryMethod();
        demo4_AbstractFactory();
        demo5_Prototype();

        System.out.println("\n=== KẾT THÚC BÀI 4.1 ===");
    }

    // ================================================================
    // DEMO 1: Singleton — 5 cách, phân tích từng cách
    // ================================================================

    /**
     * Singleton đảm bảo chỉ có 1 instance trong JVM.
     * Dùng cho: config, connection pool, logger, cache, registry.
     *
     * 5 CÁCH IMPLEMENT (từ sai đến đúng):
     *
     *   ❌ Cách 1 — Eager init không lazy: khởi tạo ngay khi class load
     *      → Lãng phí nếu singleton chưa cần dùng và tốn tài nguyên
     *
     *   ❌ Cách 2 — Lazy, không synchronized: race condition!
     *      2 thread cùng thấy instance == null → tạo 2 instance
     *
     *   ❌ Cách 3 — synchronized method: đúng nhưng quá chậm
     *      Mỗi lần gọi getInstance() đều lock → bottleneck
     *
     *   ⚠️  Cách 4 — Double-checked locking: cần volatile!
     *      Không có volatile → JIT reorder → thread thấy object chưa init xong
     *
     *   ✅ Cách 5 — Initialization-on-demand Holder: tốt nhất
     *      Lazy loading nhờ class loading guarantee của JVM
     *      Thread-safe không cần lock (JVM đảm bảo class chỉ init 1 lần)
     *      Không thể break bằng reflection hay serialization (nếu thêm guard)
     *
     *   ✅ Cách 6 — Enum Singleton: chống reflection + serialization
     *      Joshua Bloch (Effective Java): "Enum singleton is the best way"
     *      Nhược điểm: không lazy, không extend class khác
     *
     * SA INSIGHT: Singleton bị chỉ trích vì:
     *   - Khó test (global state, không inject được)
     *   - Hidden coupling (caller không biết mình đang dùng singleton)
     *   - Spring @Bean(singleton) giải quyết cả hai: DI container quản lý lifecycle
     *   Trong production: ưu tiên DI container thay vì tự implement Singleton.
     */
    static void demo1_Singleton() throws Exception {
        System.out.println("--- DEMO 1: Singleton ---");

        // Holder pattern — lazy, thread-safe, no lock
        AppConfig cfg1 = AppConfig.getInstance();
        AppConfig cfg2 = AppConfig.getInstance();
        System.out.println("  Holder Singleton: cfg1 == cfg2 → " + (cfg1 == cfg2));
        System.out.println("  AppConfig: " + cfg1);

        // Thread-safe test — nhiều thread cùng gọi getInstance()
        int THREADS = 50;
        Set<AppConfig> instances = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> { instances.add(AppConfig.getInstance()); latch.countDown(); }).start();
        }
        latch.await();
        System.out.println("  " + THREADS + " threads → số instance khác nhau: " + instances.size()
                + " (phải là 1)");

        // Enum singleton — chống reflection
        DatabaseConnection db1 = DatabaseConnection.INSTANCE;
        DatabaseConnection db2 = DatabaseConnection.INSTANCE;
        System.out.println("\n  Enum Singleton: db1 == db2 → " + (db1 == db2));
        System.out.println("  DB Connection: " + db1.getUrl());

        // Reflection phá Holder singleton — enum miễn nhiễm
        try {
            var constructor = AppConfig.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            AppConfig hacked = (AppConfig) constructor.newInstance();
            System.out.println("\n  Reflection phá Holder: tạo được instance mới = " + (hacked != cfg1)
                    + " ← đây là vấn đề của Holder pattern!");
        } catch (Exception e) {
            System.out.println("\n  Reflection blocked: " + e.getMessage());
        }

        try {
            // Enum KHÔNG thể tạo bằng reflection — JVM chặn
            var constructor = DatabaseConnection.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            constructor.newInstance("HACKED", 0);
        } catch (Exception e) {
            System.out.println("  Reflection trên Enum singleton: bị JVM chặn ✓ ("
                    + e.getClass().getSimpleName() + ")");
        }
        System.out.println();
    }

    /** Holder pattern — tốt nhất cho hầu hết use case */
    static final class AppConfig {
        private final String env;
        private final int maxConnections;

        private AppConfig() {
            // Giả lập load từ file / env vars
            this.env = System.getProperty("app.env", "development");
            this.maxConnections = Integer.parseInt(System.getProperty("db.maxConn", "20"));
        }

        // Holder class chỉ được load khi getInstance() được gọi lần đầu
        // JVM đảm bảo class init là atomic → thread-safe không cần lock
        private static final class Holder {
            private static final AppConfig INSTANCE = new AppConfig();
        }

        public static AppConfig getInstance() { return Holder.INSTANCE; }
        public String getEnv()               { return env; }
        public int getMaxConnections()        { return maxConnections; }

        @Override public String toString() {
            return "AppConfig{env=" + env + ", maxConn=" + maxConnections + "}";
        }
    }

    /** Enum singleton — chống cả reflection và serialization */
    enum DatabaseConnection {
        INSTANCE;
        private final String url = "jdbc:postgresql://localhost:5432/mydb";
        public String getUrl()   { return url; }
        public void execute(String sql) { /* ... */ }
    }

    // ================================================================
    // DEMO 2: Builder — Fluent API, Validation, Immutable
    // ================================================================

    /**
     * Builder giải quyết 3 vấn đề của constructor nhiều tham số:
     *
     *   TELESCOPING CONSTRUCTOR anti-pattern:
     *     new Pizza(size, cheese, pepperoni, mushrooms, onions, ...)
     *     → Khó đọc, dễ đặt nhầm thứ tự, không biết tham số nào là gì
     *
     *   JAVABEANS anti-pattern (setter):
     *     pizza.setSize(...); pizza.setCheese(true); ...
     *     → Object ở trạng thái không nhất quán giữa các setter call
     *     → Không thể tạo immutable object
     *
     *   BUILDER PATTERN:
     *     → Fluent API: dễ đọc, self-documenting
     *     → Validation trong build(): đảm bảo object luôn valid
     *     → Immutable object sau khi build()
     *     → Required vs optional fields rõ ràng
     *
     * THỰC TẾ: Lombok @Builder, Jackson @JsonBuilder, Protobuf builder
     *   đều implement pattern này. Hiểu pattern = hiểu tool.
     *
     * SA INSIGHT: Builder với STEP BUILDER pattern (interface chain) ép buộc
     *   caller điền đúng thứ tự required fields — compile-time safety.
     */
    static void demo2_Builder() {
        System.out.println("--- DEMO 2: Builder Pattern ---");

        // Standard Builder — fluent, validation, immutable
        HttpRequest request = HttpRequest.builder()
            .url("https://api.example.com/users")
            .method(HttpMethod.POST)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer eyJhbGc...")
            .body("{\"name\": \"Alice\"}")
            .timeout(30)
            .retries(3)
            .build();

        System.out.println("  Standard Builder:");
        System.out.println("  " + request);

        // Validation trong build()
        System.out.println("\n  Validation trong build():");
        try {
            HttpRequest invalid = HttpRequest.builder()
                .url("not-a-valid-url")  // Sẽ fail validation
                .method(HttpMethod.GET)
                .build();
        } catch (IllegalArgumentException e) {
            System.out.println("  ✓ Invalid URL bị reject: " + e.getMessage());
        }

        try {
            HttpRequest invalid = HttpRequest.builder()
                .method(HttpMethod.POST)  // Missing required url
                .body("data")
                .build();
        } catch (IllegalStateException e) {
            System.out.println("  ✓ Missing required field: " + e.getMessage());
        }

        // Step Builder — compile-time enforcement của required fields
        System.out.println("\n  Step Builder (compile-time required fields):");
        EmailMessage email = EmailMessage
            .to("bob@example.com")         // Step 1: required
            .subject("Project Update")      // Step 2: required
            .body("Please review the PR")   // Step 3: required
            .cc("alice@example.com")        // Optional
            .priority(1)                    // Optional
            .send();
        System.out.println("  " + email);

        // Builder cho immutable config object
        System.out.println("\n  Builder với default values:");
        ServerConfig config = ServerConfig.builder()
            .host("api.example.com")
            // port, timeout, maxConn dùng default values
            .build();
        System.out.println("  " + config);
        System.out.println();
    }

    enum HttpMethod { GET, POST, PUT, DELETE, PATCH }

    /** Immutable HttpRequest với full Builder */
    static final class HttpRequest {
        private final String url;
        private final HttpMethod method;
        private final Map<String, String> headers;
        private final String body;
        private final int timeoutSeconds;
        private final int retries;

        private HttpRequest(Builder b) {
            this.url = b.url; this.method = b.method;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
            this.body = b.body; this.timeoutSeconds = b.timeoutSeconds; this.retries = b.retries;
        }

        public static Builder builder() { return new Builder(); }

        @Override public String toString() {
            return String.format("HttpRequest{%s %s, headers=%d, timeout=%ds, retries=%d}",
                method, url, headers.size(), timeoutSeconds, retries);
        }

        static final class Builder {
            private String url;
            private HttpMethod method = HttpMethod.GET; // default
            private final Map<String, String> headers = new LinkedHashMap<>();
            private String body;
            private int timeoutSeconds = 10;            // default
            private int retries = 0;                    // default

            public Builder url(String url)              { this.url = url; return this; }
            public Builder method(HttpMethod m)         { this.method = m; return this; }
            public Builder header(String k, String v)   { this.headers.put(k, v); return this; }
            public Builder body(String body)            { this.body = body; return this; }
            public Builder timeout(int seconds)         { this.timeoutSeconds = seconds; return this; }
            public Builder retries(int n)               { this.retries = n; return this; }

            public HttpRequest build() {
                // Validate required fields
                if (url == null || url.isBlank())
                    throw new IllegalStateException("url is required");
                if (!url.startsWith("http://") && !url.startsWith("https://"))
                    throw new IllegalArgumentException("url must start with http:// or https://");
                if (method == HttpMethod.POST && body == null)
                    throw new IllegalStateException("POST request requires a body");
                if (timeoutSeconds <= 0)
                    throw new IllegalArgumentException("timeout must be positive");
                return new HttpRequest(this);
            }
        }
    }

    /** Step Builder — interface chain ép buộc thứ tự required fields tại compile time */
    static final class EmailMessage {
        final String to, subject, body, cc;
        final int priority;

        private EmailMessage(FinalStep s) {
            this.to = s.to; this.subject = s.subject;
            this.body = s.body; this.cc = s.cc; this.priority = s.priority;
        }

        // Step 1: to() là bắt buộc đầu tiên
        public static SubjectStep to(String to) { return new FinalStep(to); }

        interface SubjectStep { BodyStep subject(String subject); }
        interface BodyStep    { FinalStep body(String body); }

        static final class FinalStep implements SubjectStep, BodyStep {
            String to, subject, body, cc = ""; int priority = 3;
            FinalStep(String to) { this.to = to; }
            @Override public BodyStep subject(String s)  { this.subject = s; return this; }
            @Override public FinalStep body(String b)    { this.body = b; return this; }
            public FinalStep cc(String cc)               { this.cc = cc; return this; }
            public FinalStep priority(int p)             { this.priority = p; return this; }
            public EmailMessage send()                   { return new EmailMessage(this); }
        }

        @Override public String toString() {
            return String.format("Email{to=%s, subject='%s', priority=%d, cc=%s}",
                to, subject, priority, cc.isEmpty() ? "none" : cc);
        }
    }

    /** Builder với default values cho ServerConfig */
    record ServerConfig(String host, int port, int timeoutMs, int maxConnections) {
        static Builder builder() { return new Builder(); }

        static final class Builder {
            private String host;
            private int port = 8080, timeoutMs = 5000, maxConnections = 100;

            Builder host(String h)        { this.host = h; return this; }
            Builder port(int p)           { this.port = p; return this; }
            Builder timeout(int ms)       { this.timeoutMs = ms; return this; }
            Builder maxConnections(int n)  { this.maxConnections = n; return this; }

            ServerConfig build() {
                Objects.requireNonNull(host, "host is required");
                return new ServerConfig(host, port, timeoutMs, maxConnections);
            }
        }
    }

    // ================================================================
    // DEMO 3: Factory Method — tách "cái gì" khỏi "tạo thế nào"
    // ================================================================

    /**
     * Factory Method: định nghĩa interface để tạo object,
     *   nhưng để subclass quyết định class nào sẽ được instantiate.
     *
     * Giải quyết: caller không cần biết concrete class → loose coupling
     *
     * 3 biến thể thực tế:
     *   Static Factory Method: Integer.valueOf(), List.of(), Optional.of()
     *   Factory Method (OOP):  abstract createProduct() trong superclass
     *   Registry Factory:      Map<String, Supplier<T>> — dynamic, extensible
     *
     * THỰC TẾ TRONG JAVA:
     *   - DriverManager.getConnection()    → JDBC factory
     *   - Calendar.getInstance()           → returns GregorianCalendar
     *   - Executors.newFixedThreadPool()   → thread pool factory
     *   - Files.newBufferedReader()        → I/O factory
     *
     * SA INSIGHT: Static factory method hơn constructor:
     *   ✓ Có tên (valueOf, of, from, create, getInstance, newInstance)
     *   ✓ Không phải luôn tạo object mới (caching, pooling)
     *   ✓ Có thể trả về subtype
     *   ✓ Giảm verbosity: List.of() thay new ArrayList<>()
     */
    static void demo3_FactoryMethod() {
        System.out.println("--- DEMO 3: Factory Method ---");

        // Static Factory Method — tên rõ ràng hơn constructor
        Notification emailNotif  = Notification.email("user@example.com", "Welcome!");
        Notification smsNotif    = Notification.sms("+84901234567", "OTP: 123456");
        Notification pushNotif   = Notification.push("device-token-xyz", "New message");

        System.out.println("  Static Factory Method:");
        System.out.println("  " + emailNotif.send());
        System.out.println("  " + smsNotif.send());
        System.out.println("  " + pushNotif.send());

        // Registry Factory — dynamic, extensible (mở rộng không sửa code cũ)
        NotificationFactory factory = new NotificationFactory();
        factory.register("email",    () -> new EmailNotification("", ""));
        factory.register("sms",      () -> new SmsNotification("", ""));
        factory.register("webhook",  () -> new WebhookNotification("", ""));

        System.out.println("\n  Registry Factory (dynamic):");
        for (String type : List.of("email", "sms", "webhook")) {
            Notification n = factory.create(type);
            System.out.println("  create(\"" + type + "\") → " + n.getClass().getSimpleName());
        }

        // Factory Method OOP — subclass quyết định implementation
        System.out.println("\n  Factory Method OOP:");
        NotificationSender emailSender = new EmailSender();
        NotificationSender smsSender   = new SmsSender();
        emailSender.send("Hello via email sender");
        smsSender.send("Hello via SMS sender");
        System.out.println();
    }

    interface Notification {
        String send();

        // Static factory methods — tên rõ ràng, ẩn concrete class
        static Notification email(String to, String msg) { return new EmailNotification(to, msg); }
        static Notification sms(String phone, String msg) { return new SmsNotification(phone, msg); }
        static Notification push(String token, String msg) { return new WebhookNotification(token, msg); }
    }

    record EmailNotification(String to, String msg) implements Notification {
        @Override public String send() { return "EMAIL → " + to + ": " + msg; }
    }
    record SmsNotification(String phone, String msg) implements Notification {
        @Override public String send() { return "SMS → " + phone + ": " + msg; }
    }
    record WebhookNotification(String token, String msg) implements Notification {
        @Override public String send() { return "PUSH → " + token.substring(0, Math.min(8, token.length())) + "...: " + msg; }
    }

    static class NotificationFactory {
        private final Map<String, Supplier<Notification>> registry = new HashMap<>();
        void register(String type, Supplier<Notification> creator) { registry.put(type, creator); }
        Notification create(String type) {
            Supplier<Notification> creator = registry.get(type);
            if (creator == null) throw new IllegalArgumentException("Unknown type: " + type);
            return creator.get();
        }
    }

    abstract static class NotificationSender {
        // Template Method + Factory Method kết hợp
        final void send(String message) {
            Notification n = createNotification(message); // Factory Method
            System.out.println("  " + n.send());
        }
        abstract Notification createNotification(String message); // Subclass quyết định
    }

    static class EmailSender extends NotificationSender {
        @Override Notification createNotification(String msg) { return Notification.email("default@example.com", msg); }
    }
    static class SmsSender extends NotificationSender {
        @Override Notification createNotification(String msg) { return Notification.sms("+84000000000", msg); }
    }

    // ================================================================
    // DEMO 4: Abstract Factory — Family of related objects
    // ================================================================

    /**
     * Abstract Factory: interface để tạo FAMILY of related objects
     *   mà không chỉ định concrete class.
     *
     * Khác Factory Method:
     *   Factory Method: 1 product, subclass quyết định
     *   Abstract Factory: nhiều product liên quan, factory quyết định cả family
     *
     * VÍ DỤ THỰC TẾ:
     *   - UI Toolkit: LightThemeFactory vs DarkThemeFactory
     *     → cùng tạo Button, TextBox, Dialog nhưng style khác nhau
     *   - Cloud Provider: AwsFactory vs GcpFactory vs AzureFactory
     *     → cùng tạo Storage, Compute, Database nhưng implementation khác
     *   - Database: MySqlFactory vs PostgresFactory
     *     → cùng tạo Connection, Statement, Transaction
     *
     * SA INSIGHT: Abstract Factory xuất hiện nhiều ở infrastructure layer.
     *   Spring's ApplicationContextFactory, Hibernate's SessionFactory đều là Abstract Factory.
     *   Khi cần swap toàn bộ infrastructure (AWS→GCP) mà không sửa business code.
     */
    static void demo4_AbstractFactory() {
        System.out.println("--- DEMO 4: Abstract Factory ---");

        // Chạy với AWS factory
        System.out.println("  AWS Infrastructure:");
        provisionInfrastructure(new AwsFactory());

        // Chạy với GCP factory — KHÔNG sửa 1 dòng code provisionInfrastructure
        System.out.println("\n  GCP Infrastructure:");
        provisionInfrastructure(new GcpFactory());

        // UI Theme example
        System.out.println("\n  UI Light Theme:");
        renderUI(new LightThemeFactory());
        System.out.println("  UI Dark Theme:");
        renderUI(new DarkThemeFactory());
        System.out.println();
    }

    // Business logic: không biết đang dùng AWS hay GCP
    static void provisionInfrastructure(CloudFactory factory) {
        CloudStorage  storage  = factory.createStorage();
        CloudCompute  compute  = factory.createCompute();
        CloudDatabase database = factory.createDatabase();
        System.out.println("  Storage:  " + storage.upload("data.csv"));
        System.out.println("  Compute:  " + compute.run("process.sh"));
        System.out.println("  Database: " + database.query("SELECT * FROM users"));
    }

    interface CloudStorage  { String upload(String file); }
    interface CloudCompute  { String run(String script); }
    interface CloudDatabase { String query(String sql); }

    interface CloudFactory {
        CloudStorage  createStorage();
        CloudCompute  createCompute();
        CloudDatabase createDatabase();
    }

    static class AwsFactory implements CloudFactory {
        @Override public CloudStorage  createStorage()  { return f -> "S3: uploaded " + f; }
        @Override public CloudCompute  createCompute()  { return s -> "EC2: running " + s; }
        @Override public CloudDatabase createDatabase() { return q -> "RDS: " + q; }
    }

    static class GcpFactory implements CloudFactory {
        @Override public CloudStorage  createStorage()  { return f -> "GCS: uploaded " + f; }
        @Override public CloudCompute  createCompute()  { return s -> "GCE: running " + s; }
        @Override public CloudDatabase createDatabase() { return q -> "CloudSQL: " + q; }
    }

    interface Button  { String render(); }
    interface TextBox { String render(); }

    interface UIFactory {
        Button  createButton(String label);
        TextBox createTextBox(String placeholder);
    }

    static void renderUI(UIFactory factory) {
        Button  btn = factory.createButton("Submit");
        TextBox txt = factory.createTextBox("Enter name...");
        System.out.println("    " + btn.render() + " | " + txt.render());
    }

    static class LightThemeFactory implements UIFactory {
        @Override public Button  createButton(String l)  { return () -> "[ " + l + " ] (light)"; }
        @Override public TextBox createTextBox(String p) { return () -> "[ " + p + " ] (light)"; }
    }
    static class DarkThemeFactory implements UIFactory {
        @Override public Button  createButton(String l)  { return () -> "▐ " + l + " ▌ (dark)"; }
        @Override public TextBox createTextBox(String p) { return () -> "▐ " + p + " ▌ (dark)"; }
    }

    // ================================================================
    // DEMO 5: Prototype — Clone thay vì new
    // ================================================================

    /**
     * Prototype: tạo object mới bằng cách COPY object hiện có thay vì new.
     *
     * KHI NÀO DÙNG:
     *   - Tạo object tốn kém (DB load, complex init) → clone rẻ hơn new
     *   - Cần nhiều variation của 1 object base (template object)
     *   - Object creation phụ thuộc vào state hiện tại
     *
     * SHALLOW COPY: copy reference của nested object → share state
     *   → Thay đổi nested object của clone ảnh hưởng original!
     *
     * DEEP COPY: copy toàn bộ object graph → hoàn toàn độc lập
     *   → An toàn nhưng tốn memory và time hơn
     *
     * THỰC TẾ TRONG JAVA:
     *   - Object.clone() → shallow copy (cần override và implement Cloneable)
     *   - Copy constructor → tường minh hơn, không dùng magic clone()
     *   - Serialization/deserialization → deep copy nhưng chậm
     *   - Libraries: Apache Commons BeanUtils, ModelMapper, MapStruct
     *
     * SA INSIGHT: Prototype Registry = Map<String, Prototype>
     *   Đăng ký các "template" object, clone khi cần → giống Object pool nhẹ.
     *   Game engine dùng nhiều: bullet, enemy, particle — đều prototype từ template.
     */
    static void demo5_Prototype() {
        System.out.println("--- DEMO 5: Prototype Pattern ---");

        // Shallow copy pitfall
        System.out.println("  Shallow copy:");
        ReportTemplate original = new ReportTemplate("Q1 Report", List.of("Revenue", "Cost"), new ReportConfig("PDF", "A4"));
        ReportTemplate shallowClone = original.shallowCopy();
        System.out.println("  Original:     " + original);
        System.out.println("  ShallowClone: " + shallowClone);
        System.out.println("  config cùng object? " + (original.config == shallowClone.config)
                + " ← shallow copy share config reference!");

        // Deep copy — hoàn toàn độc lập
        System.out.println("\n  Deep copy:");
        ReportTemplate deepClone = original.deepCopy();
        deepClone.title = "Q2 Report";
        deepClone.config.format = "EXCEL"; // Thay đổi deep clone KHÔNG ảnh hưởng original
        System.out.println("  Original:   " + original + " ← không bị ảnh hưởng ✓");
        System.out.println("  DeepClone:  " + deepClone);
        System.out.println("  config cùng object? " + (original.config == deepClone.config));

        // Prototype Registry — template pool
        System.out.println("\n  Prototype Registry:");
        PrototypeRegistry registry = new PrototypeRegistry();
        registry.register("pdf-a4",    new ReportTemplate("Template", List.of("Col1","Col2"), new ReportConfig("PDF","A4")));
        registry.register("excel-a3",  new ReportTemplate("Template", List.of("Col1","Col2"), new ReportConfig("EXCEL","A3")));
        registry.register("html",      new ReportTemplate("Template", List.of("Col1","Col2"), new ReportConfig("HTML","N/A")));

        ReportTemplate salesReport = registry.get("pdf-a4");
        salesReport.title = "Sales Report Q3";

        ReportTemplate hrReport = registry.get("excel-a3");
        hrReport.title = "HR Headcount";

        System.out.println("  Sales: " + salesReport);
        System.out.println("  HR:    " + hrReport);
        System.out.println("  Registry templates unchanged: " + registry.get("pdf-a4").title
                + " (deep copy đảm bảo template không bị modify)");

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 4.1 ===");
        System.out.println("  ✓ Singleton: Holder pattern (lazy+thread-safe) hoặc Enum (chống reflection)");
        System.out.println("  ✓ Builder: fluent API + validation trong build() + immutable result");
        System.out.println("  ✓ Factory Method: ẩn concrete class, static factory method > constructor");
        System.out.println("  ✓ Abstract Factory: swap toàn bộ family (AWS↔GCP, Light↔Dark) không sửa client");
        System.out.println("  ✓ Prototype: clone > new khi init tốn kém. Deep copy = hoàn toàn độc lập.");
        System.out.println("  → Bài tiếp: 4.2 StructuralPatternsDemo — Decorator, Proxy, Adapter, Facade");
    }

    static class ReportConfig implements Cloneable {
        String format, pageSize;
        ReportConfig(String format, String pageSize) { this.format = format; this.pageSize = pageSize; }
        @Override public ReportConfig clone() {
            try { return (ReportConfig) super.clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(); }
        }
        @Override public String toString() { return "Config{" + format + "/" + pageSize + "}"; }
    }

    static class ReportTemplate {
        String title;
        List<String> columns;
        ReportConfig config;

        ReportTemplate(String title, List<String> columns, ReportConfig config) {
            this.title = title; this.columns = columns; this.config = config;
        }

        /** Shallow copy — config reference shared! */
        ReportTemplate shallowCopy() {
            return new ReportTemplate(title, columns, config); // config NOT copied
        }

        /** Deep copy — hoàn toàn độc lập */
        ReportTemplate deepCopy() {
            return new ReportTemplate(title, new ArrayList<>(columns), config.clone());
        }

        @Override public String toString() {
            return "Report{'" + title + "', cols=" + columns + ", " + config + "}";
        }
    }

    static class PrototypeRegistry {
        private final Map<String, ReportTemplate> registry = new HashMap<>();
        void register(String key, ReportTemplate template) { registry.put(key, template); }
        ReportTemplate get(String key) {
            ReportTemplate template = registry.get(key);
            if (template == null) throw new IllegalArgumentException("Unknown template: " + key);
            return template.deepCopy(); // Luôn trả về deep copy
        }
    }
}
