package org.example.patterns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * =============================================================================
 * BÀI 4.5 — Anti-Patterns thực chiến (Java / SA Level)
 * =============================================================================
 *
 * Anti-pattern KHÔNG phải là code "sai" về mặt compile — nó là giải pháp
 * trông có vẻ đúng nhưng tạo ra technical debt, khó test, khó maintain, hoặc
 * phá vỡ scalability theo thời gian.
 *
 * Bài này cover 6 anti-pattern phổ biến nhất, LUÔN theo format:
 *   ❌ BAD — code anti-pattern + giải thích vì sao nó sai
 *   ✅ GOOD — refactored version + SA-level insight
 *
 * Anti-Patterns covered:
 *   1. God Object (Blob) — class biết/làm quá nhiều
 *   2. Service Locator — dependency tự pull thay vì được inject
 *   3. Singleton Abuse — global state disguised as patterns
 *   4. Primitive Obsession — dùng int/String thay domain types
 *   5. Anemic Domain Model — data class + service class tách đôi logic
 *   6. Magic Numbers & Stringly-Typed — literals rải rắc khắp code
 *
 * SA Insight tổng quát:
 *   - "Mọi anti-pattern đều bắt đầu từ một quyết định hợp lý trong ngữ cảnh cũ"
 *   - God Object: MVP cần ship fast → không refactor → Blob
 *   - Service Locator: cần flexibility → overused → untestable
 *   - Singleton Abuse: thread-safe global → hidden coupling → test nightmare
 *   - Primitive Obsession: YAGNI → business rule scattered → domain blindness
 *   - Anemic Domain Model: Transaction Script mindset → OOP betrayal
 *   - Magic literals: quick fix → comprehension debt
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.patterns.AntiPatternsDemo"
 */
public class AntiPatternsDemo {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 4.5 — Anti-Patterns thực chiến");
        System.out.println("=".repeat(70));

        demo1_GodObject();
        demo2_ServiceLocator();
        demo3_SingletonAbuse();
        demo4_PrimitiveObsession();
        demo5_AnemicDomainModel();
        demo6_MagicNumbers();
    }

    // =========================================================================
    // DEMO 1 — God Object (Blob Anti-Pattern)
    // =========================================================================

    static void demo1_GodObject() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 1 — God Object / Blob Anti-Pattern");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — God Object: OrderManager làm tất cả mọi thứ

              class OrderManager {
                  // Biết về Customer
                  public void validateCustomer(String email) { ... }
                  public String formatCustomerName(String first, String last) { ... }

                  // Biết về Payment
                  public void chargeCard(String cardNumber, double amount) { ... }
                  public void refund(String txId, double amount) { ... }

                  // Biết về Inventory
                  public boolean checkStock(String sku, int qty) { ... }
                  public void reserveItem(String sku, int qty) { ... }

                  // Biết về Notification
                  public void sendEmail(String to, String body) { ... }
                  public void sendSMS(String phone, String msg) { ... }

                  // Biết về Reporting
                  public Report generateMonthlyReport() { ... }
                  public void exportToCsv(Report r, String path) { ... }

                  // ... 2000 more lines
              }

            Vấn đề:
              • God Object vi phạm SRP (Single Responsibility Principle)
              • Coupling cao: mọi thay đổi có thể break bất kỳ feature nào
              • Impossible to unit test: phải mock toàn bộ vũ trụ
              • Merge conflict hell: mọi team đều sửa cùng 1 file
              • God Object thường xuất hiện trong "legacy system" khi MVP ship fast
                nhưng không có refactoring budget
            """);

        // ✅ GOOD — Bounded Context / Separated Concerns
        System.out.println("✅ GOOD — Refactored: Separated Concerns + Domain Services");

        // Mỗi class có 1 trách nhiệm rõ ràng
        var customerService   = new CustomerService();
        var inventoryService  = new InventoryService();
        var paymentService    = new PaymentService();
        var notificationService = new NotificationService();
        // OrderService chỉ ORCHESTRATE — không làm nghiệp vụ
        var orderService = new OrderService(customerService, inventoryService,
                                            paymentService, notificationService);

        System.out.println("[OrderService] Placing order for alice@example.com...");
        String result = orderService.placeOrder("alice@example.com", "SKU-001", 2, new BigDecimal("199.99"));
        System.out.println("[Result] " + result);

        System.out.println("""

            SA Insight:
              • DDD Bounded Context: Customer, Order, Inventory, Payment là 4 context riêng
              • OrderService = Application Service: chỉ orchestrate, không chứa domain logic
              • Mỗi service có thể test độc lập với mock
              • Microservices split tự nhiên theo các service đã tách
              • "If your class name ends in Manager, Service, Util, Helper → smell test it"
            """);
    }

    // ──────────── God Object — Supporting Classes ────────────

    static class CustomerService {
        public boolean validate(String email) {
            return email != null && email.contains("@");
        }
        public String getDisplayName(String email) {
            return email.split("@")[0];
        }
    }

    static class InventoryService {
        private final Map<String, Integer> stock = new HashMap<>(Map.of(
            "SKU-001", 100, "SKU-002", 5
        ));

        public boolean isAvailable(String sku, int qty) {
            return stock.getOrDefault(sku, 0) >= qty;
        }

        public void reserve(String sku, int qty) {
            stock.merge(sku, -qty, Integer::sum);
            System.out.printf("  [Inventory] Reserved %d × %s (remaining: %d)%n",
                qty, sku, stock.get(sku));
        }
    }

    static class PaymentService {
        public String charge(String email, BigDecimal amount) {
            String txId = "TXN-" + System.currentTimeMillis();
            System.out.printf("  [Payment] Charged %s $%.2f → txId: %s%n", email, amount, txId);
            return txId;
        }
    }

    static class NotificationService {
        public void sendOrderConfirmation(String email, String orderId) {
            System.out.printf("  [Notification] Email sent to %s: Order %s confirmed%n", email, orderId);
        }
    }

    // Application Service — chỉ orchestrate, không chứa business logic
    static class OrderService {
        private final CustomerService customer;
        private final InventoryService inventory;
        private final PaymentService payment;
        private final NotificationService notification;

        OrderService(CustomerService c, InventoryService i, PaymentService p, NotificationService n) {
            this.customer = c; this.inventory = i; this.payment = p; this.notification = n;
        }

        public String placeOrder(String email, String sku, int qty, BigDecimal unitPrice) {
            if (!customer.validate(email))      return "FAILED: Invalid customer";
            if (!inventory.isAvailable(sku, qty)) return "FAILED: Out of stock";

            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty));
            String txId = payment.charge(email, total);
            inventory.reserve(sku, qty);

            String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            notification.sendOrderConfirmation(email, orderId);
            return "SUCCESS: " + orderId + " (tx: " + txId + ")";
        }
    }

    // =========================================================================
    // DEMO 2 — Service Locator Anti-Pattern
    // =========================================================================

    static void demo2_ServiceLocator() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2 — Service Locator Anti-Pattern");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — Service Locator: dependencies bị ẩn trong registry

              class ReportGenerator {
                  public Report generate(String type) {
                      // Dependencies pulled from global registry — invisible to caller!
                      DataSource db   = ServiceLocator.get("dataSource");
                      Formatter  fmt  = ServiceLocator.get("formatter");
                      Mailer     mail = ServiceLocator.get("mailer");
                      ...
                  }
              }

              // Caller không biết ReportGenerator cần những gì:
              new ReportGenerator().generate("MONTHLY");  // might NPE at runtime!

            Vấn đề:
              • Hidden dependencies: signature lie về những gì class thực sự cần
              • Test nightmare: phải setup global ServiceLocator trước mỗi test
              • Runtime failure: thiếu registration → NullPointerException, không compile-time
              • Circular dependency: khó detect khi không có explicit graph
              • "Service Locator là Dependency Injection của người lười" — Mark Seemann
            """);

        // Setup Service Locator (the bad way)
        BadServiceLocator.register("formatter", (Formatter) s -> "[FORMATTED] " + s);
        BadServiceLocator.register("dataSource", (DataSource) query -> "raw_data_for_" + query);

        System.out.println("❌ BAD — using Service Locator:");
        var badReport = new BadReportGenerator();
        try {
            System.out.println("  " + badReport.generate("MONTHLY"));
        } catch (Exception e) {
            System.out.println("  RUNTIME ERROR: " + e.getMessage());
        }

        // ✅ GOOD — Dependency Injection
        System.out.println("\n✅ GOOD — Dependency Injection (constructor injection):");
        Formatter formatter = s -> "[FORMATTED] " + s;
        DataSource dataSource = query -> "raw_data_for_" + query;
        var goodReport = new GoodReportGenerator(dataSource, formatter);
        System.out.println("  " + goodReport.generate("MONTHLY"));

        System.out.println("""

            SA Insight:
              • DI Container (Spring) = Service Locator DONE RIGHT: wiring tách khỏi usage
              • Constructor injection > field injection: dependencies visible, final, testable
              • Spring @Autowired on field = hidden dependency = test antipattern
              • "Tell, Don't Ask" — inject what you need, don't go looking for it
              • Martin Fowler: "Service Locator is acceptable in 'main' composition root only"
            """);
    }

    // ──────────── Service Locator — Supporting ────────────

    @FunctionalInterface interface Formatter { String format(String s); }
    @FunctionalInterface interface DataSource { String query(String q); }

    static class BadServiceLocator {
        private static final Map<String, Object> registry = new HashMap<>();
        @SuppressWarnings("unchecked")
        static <T> T get(String name) {
            Object svc = registry.get(name);
            if (svc == null) throw new IllegalStateException("Service not found: " + name);
            return (T) svc;
        }
        static void register(String name, Object svc) { registry.put(name, svc); }
    }

    static class BadReportGenerator {
        public String generate(String type) {
            // mailer was NOT registered — runtime failure
            Formatter fmt = BadServiceLocator.get("formatter");
            DataSource ds = BadServiceLocator.get("dataSource");
            // BadServiceLocator.get("mailer"); // would throw at runtime!
            return fmt.format(ds.query(type));
        }
    }

    // ✅ Good: all dependencies explicit in constructor
    static class GoodReportGenerator {
        private final DataSource dataSource;
        private final Formatter formatter;

        GoodReportGenerator(DataSource dataSource, Formatter formatter) {
            this.dataSource = Objects.requireNonNull(dataSource);
            this.formatter  = Objects.requireNonNull(formatter);
        }

        public String generate(String type) {
            return formatter.format(dataSource.query(type));
        }
    }

    // =========================================================================
    // DEMO 3 — Singleton Abuse (Global State Anti-Pattern)
    // =========================================================================

    static void demo3_SingletonAbuse() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — Singleton Abuse (Global State)");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — Singleton Abuse: mutable global state

              class UserContext {                     // "It's just a singleton!"
                  private static UserContext INSTANCE = new UserContext();
                  private String currentUserId;       // MUTABLE global state

                  public void setCurrentUser(String id) { currentUserId = id; }
                  public String getCurrentUser()         { return currentUserId; }
              }

              // Thread A: UserContext.getInstance().setCurrentUser("alice");
              // Thread B: UserContext.getInstance().setCurrentUser("bob");
              // Thread A: UserContext.getInstance().getCurrentUser(); // returns "bob"!

            Vấn đề:
              • Mutable singleton = global variable = race condition
              • Test ordering matters: test A pollutes state for test B
              • Impossible to run parallel tests (JUnit parallel execution breaks)
              • Hidden coupling: any class can access/modify global state without declaration
              • "Singleton is just a GoF-approved global variable"
            """);

        // ✅ GOOD — Scoped context (per-request, per-thread, or explicit parameter)
        System.out.println("✅ GOOD — ThreadLocal-scoped context (per-request isolation):");

        var context = new RequestContext();

        // Simulate two concurrent requests
        Thread t1 = new Thread(() -> {
            RequestContext.set("user-alice");
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.printf("  [%s] current user = %s%n",
                Thread.currentThread().getName(), RequestContext.get());
            RequestContext.clear(); // prevent ThreadLocal leak
        }, "request-1");
        t1.start();

        Thread t2 = new Thread(() -> {
            RequestContext.set("user-bob");
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.printf("  [%s] current user = %s%n",
                Thread.currentThread().getName(), RequestContext.get());
            RequestContext.clear();
        }, "request-2");
        t2.start();

        try { t1.join(); t2.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        System.out.println("""

            SA Insight:
              • Singleton hợp lệ khi: IMMUTABLE (config, logger factory, enum constants)
              • Singleton nguy hiểm khi: mutable state, caching with invalidation, counters
              • Spring @Bean default = singleton scope — safe vì Spring beans thường stateless
              • Request-scoped state → ThreadLocal (Spring RequestContextHolder dùng cách này)
              • Virtual threads: ThreadLocal vẫn work nhưng xem xét ScopedValue (Java 21)
              • Rule: "If you need to reset it between tests, it's abused global state"
            """);
    }

    static class RequestContext {
        private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
        static void set(String userId)   { HOLDER.set(userId); }
        static String get()              { return HOLDER.get(); }
        static void clear()              { HOLDER.remove(); } // prevent leak in thread pools
    }

    // =========================================================================
    // DEMO 4 — Primitive Obsession
    // =========================================================================

    static void demo4_PrimitiveObsession() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — Primitive Obsession Anti-Pattern");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — Primitive Obsession: domain concepts as primitives

              void transfer(String fromAccount, String toAccount,
                            double amount, String currency) {
                  // Is "amount" in cents or dollars? USD or VND?
                  // Is "fromAccount" accountId or accountNumber?
                  // What if someone passes (toAccount, fromAccount, ...) by mistake?
              }

              // Accidental swap — COMPILES FINE, wrong at runtime:
              transfer(toAccountId, fromAccountId, -amount, "VND");

            Vấn đề:
              • Primitives không mang domain meaning → confusion, swap bugs
              • Business rules scattered: null check, format validation rải khắp codebase
              • Impossible to enforce invariants at type level
              • "Stringly-typed": String status, String type, String currency → no IDE help
            """);

        // ✅ GOOD — Value Objects
        System.out.println("✅ GOOD — Value Objects enforce domain rules at compile time:");

        var from   = AccountId.of("ACC-001");
        var to     = AccountId.of("ACC-002");
        var amount = Money.of(new BigDecimal("500.00"), Currency.USD);

        System.out.println("  Transfer: " + from + " → " + to + " : " + amount);

        // Type system prevents swap
        // Money.of(new BigDecimal("-100"), Currency.USD); // throws IllegalArgumentException

        // Money arithmetic is safe and meaningful
        Money price    = Money.of(new BigDecimal("99.99"), Currency.USD);
        Money discount = Money.of(new BigDecimal("10.00"), Currency.USD);
        Money total    = price.subtract(discount);
        System.out.println("  Price: " + price + " - Discount: " + discount + " = Total: " + total);

        try {
            Money usd = Money.of(new BigDecimal("100"), Currency.USD);
            Money vnd = Money.of(new BigDecimal("2500000"), Currency.VND);
            usd.add(vnd); // should throw
        } catch (IllegalArgumentException e) {
            System.out.println("  Currency mismatch caught: " + e.getMessage());
        }

        System.out.println("""

            SA Insight:
              • Value Object = immutable, equality by value, no identity
              • DDD: AccountId, Money, EmailAddress, PhoneNumber, OrderStatus = Value Objects
              • Java 16+ record: perfect for Value Objects (compact, immutable, equals/hashCode free)
              • "Make illegal states unrepresentable" — Yaron Minsky
              • Primitive for algo/math; Value Object for domain concepts
              • Money anti-pattern: double/float cho tiền → IEEE 754 rounding → use BigDecimal
            """);
    }

    // ──────────── Value Objects ────────────

    record AccountId(String value) {
        static AccountId of(String value) {
            if (value == null || !value.matches("ACC-\\d+"))
                throw new IllegalArgumentException("Invalid account ID: " + value);
            return new AccountId(value);
        }
        @Override public String toString() { return value; }
    }

    enum Currency { USD, VND, EUR }

    static final class Money {
        private final BigDecimal amount;
        private final Currency currency;

        private Money(BigDecimal amount, Currency currency) {
            if (amount.compareTo(BigDecimal.ZERO) < 0)
                throw new IllegalArgumentException("Amount cannot be negative: " + amount);
            this.amount   = amount.setScale(2, RoundingMode.HALF_UP);
            this.currency = currency;
        }

        static Money of(BigDecimal amount, Currency currency) {
            return new Money(Objects.requireNonNull(amount), Objects.requireNonNull(currency));
        }

        Money add(Money other) {
            assertSameCurrency(other);
            return new Money(amount.add(other.amount), currency);
        }

        Money subtract(Money other) {
            assertSameCurrency(other);
            BigDecimal result = amount.subtract(other.amount);
            if (result.compareTo(BigDecimal.ZERO) < 0)
                throw new IllegalArgumentException("Subtraction would result in negative amount");
            return new Money(result, currency);
        }

        private void assertSameCurrency(Money other) {
            if (this.currency != other.currency)
                throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }

        @Override public String toString() {
            return String.format("%s %.2f", currency, amount);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Money m)) return false;
            return amount.equals(m.amount) && currency == m.currency;
        }

        @Override public int hashCode() { return Objects.hash(amount, currency); }
    }

    // =========================================================================
    // DEMO 5 — Anemic Domain Model
    // =========================================================================

    static void demo5_AnemicDomainModel() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Anemic Domain Model Anti-Pattern");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — Anemic Domain Model: data bags + procedural services

              class AnemicOrder {          // Pure data bag — no behavior
                  private String id;
                  private String status;
                  private List<String> items;
                  private double totalAmount;
                  // getters + setters only
              }

              class AnemicOrderService {   // All business logic here
                  public void addItem(AnemicOrder order, String item, double price) {
                      if (order.getStatus().equals("CANCELLED")) throw ...;
                      order.getItems().add(item);                    // exposes internals
                      order.setTotalAmount(order.getTotalAmount() + price);
                  }
                  public void cancel(AnemicOrder order) {
                      if (!order.getStatus().equals("PENDING")) throw ...;
                      order.setStatus("CANCELLED");
                  }
              }

            Vấn đề:
              • Domain logic KHÔNG sống với domain data → OOP betrayal
              • AnemicOrder.getItems() trả List → caller có thể bypass addItem logic
              • Business rules duplicate: OrderService, OrderValidator, OrderProcessor...
              • Tell Don't Ask violation: service "asks" data then decides
              • Martin Fowler: "Anemic Domain Model is really just a procedural style design"
            """);

        // ✅ GOOD — Rich Domain Model
        System.out.println("✅ GOOD — Rich Domain Model: behavior lives with data:");

        RichOrder order = new RichOrder("ORD-001");
        order.addItem("Laptop", Money.of(new BigDecimal("999.99"), Currency.USD));
        order.addItem("Mouse",  Money.of(new BigDecimal("49.99"),  Currency.USD));

        System.out.println("  Order: " + order);
        System.out.println("  Items: " + order.itemCount() + ", Total: " + order.total());

        order.confirm();
        System.out.println("  Status after confirm: " + order.status());

        try {
            order.addItem("Keyboard", Money.of(new BigDecimal("79.99"), Currency.USD));
        } catch (IllegalStateException e) {
            System.out.println("  Cannot add to confirmed order: " + e.getMessage());
        }

        System.out.println("""

            SA Insight:
              • Rich Domain = Entity có behavior: validate, compute, enforce invariants
              • Private internal collections — never expose List<Item> directly
              • State transitions protected inside entity — không ai bypass được
              • Application Service vẫn cần: orchestrate, transaction boundary, publish events
              • DDD Rule: "Domain logic → Entity/Aggregate. Tech concerns → Application Service"
              • Spring JPA: @Entity rich domain model với @Transactional application service
            """);
    }

    // ──────────── Rich Domain Model ────────────

    static class RichOrder {
        public enum Status { DRAFT, CONFIRMED, SHIPPED, CANCELLED }

        private final String id;
        private Status status = Status.DRAFT;
        private final List<OrderItem> items = new ArrayList<>();

        RichOrder(String id) { this.id = Objects.requireNonNull(id); }

        // Behavior lives HERE — not in a separate "OrderService"
        public void addItem(String name, Money price) {
            if (status != Status.DRAFT)
                throw new IllegalStateException("Cannot add items to order in status: " + status);
            items.add(new OrderItem(name, price));
            System.out.printf("  [Order %s] Added: %s @ %s%n", id, name, price);
        }

        public void confirm() {
            if (status != Status.DRAFT)
                throw new IllegalStateException("Only DRAFT orders can be confirmed");
            if (items.isEmpty())
                throw new IllegalStateException("Cannot confirm empty order");
            this.status = Status.CONFIRMED;
            System.out.printf("  [Order %s] Confirmed%n", id);
        }

        public void cancel(String reason) {
            if (status == Status.SHIPPED)
                throw new IllegalStateException("Cannot cancel shipped order");
            this.status = Status.CANCELLED;
            System.out.printf("  [Order %s] Cancelled: %s%n", id, reason);
        }

        // Query methods — read-only projection, not raw collection exposure
        public int itemCount()   { return items.size(); }
        public Status status()   { return status; }
        public Money total() {
            return items.stream()
                .map(OrderItem::price)
                .reduce(Money.of(BigDecimal.ZERO, Currency.USD), Money::add);
        }
        public List<String> itemNames() { return items.stream().map(OrderItem::name).toList(); }

        @Override public String toString() {
            return String.format("Order{id=%s, status=%s, items=%d}", id, status, items.size());
        }

        private record OrderItem(String name, Money price) {}
    }

    // =========================================================================
    // DEMO 6 — Magic Numbers & Stringly-Typed Code
    // =========================================================================

    static void demo6_MagicNumbers() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — Magic Numbers & Stringly-Typed Code");
        System.out.println("─".repeat(70));

        System.out.println("""
            ❌ BAD — Magic Numbers và String literals rải rắc

              if (user.getRole().equals("ADMIN")) { ... }     // Magic string
              if (retry < 3) { ... }                           // What is 3? Max retries? Minutes?
              Thread.sleep(5000);                              // 5 seconds? 5 milliseconds? Why?
              double tax = amount * 0.1;                       // VAT? Import tax? Which country?
              if (status == 2) { ... }                         // What does 2 mean?!
              byte[] buffer = new byte[1024 * 8];              // Why 8KB? Why not 4KB or 16KB?

            Vấn đề:
              • Literal không có context → người đọc phải guess intent
              • Cùng magic number có thể có ý nghĩa khác nhau ở 2 nơi
              • Đổi business rule → phải tìm tất cả nơi dùng số đó
              • "Stringly-typed": String status = "ACTIVE" → typo không bị compile-time check
            """);

        // ✅ GOOD — Named Constants, Enums, Config
        System.out.println("✅ GOOD — Named Constants, Enums, và Configuration Objects:");

        // Enums thay String constants
        var role = UserRole.ADMIN;
        if (role == UserRole.ADMIN) {
            System.out.println("  [Auth] Admin access granted to " + role.displayName());
        }

        // Named constants với context
        var retryPolicy = RetryPolicy.DEFAULT;
        System.out.printf("  [Retry] Max attempts: %d, backoff: %dms%n",
            retryPolicy.maxAttempts(), retryPolicy.backoffMs());

        // Value Object thay primitive
        var taxRate  = TaxRate.VAT_STANDARD;
        var price    = Money.of(new BigDecimal("1000.00"), Currency.USD);
        // tax = price * 0.1 → magic; this = explicit
        var tax      = taxRate.apply(price);
        System.out.printf("  [Tax] %s on %s = %s%n", taxRate, price, tax);

        // Order status via enum not int/String
        var status = OrderStatus.AWAITING_PAYMENT;
        System.out.println("  [Order] Status: " + status + " (code=" + status.code() + ")");
        if (status.requiresPayment()) {
            System.out.println("  [Order] Payment required → redirect to payment gateway");
        }

        System.out.println("""

            SA Insight:
              • Rule of thumb: any literal used MORE THAN ONCE → named constant minimum
              • String status/type/role → Enum: compile-time safety, switch exhaustiveness
              • Config values (timeouts, pool sizes, thresholds) → @ConfigurationProperties (Spring)
              • Ports & adapters: magic ports (8080, 5432) in application.yml, not in code
              • "A magic number is a number without a name" — Clean Code
              • Enum > int codes for status: ORDER_SHIPPED not status == 4
            """);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 4.5 — Anti-Patterns");
        System.out.println("=".repeat(70));
        System.out.println("""
            Anti-Pattern          | Triệu chứng                    | Giải pháp
            ──────────────────────┼────────────────────────────────┼──────────────────────────
            God Object            | Class > 500 lines, name=*Mgr   | SRP + Bounded Context
            Service Locator       | ServiceLocator.get() in logic  | Constructor Injection (DI)
            Singleton Abuse       | Mutable static field           | Scoped/ThreadLocal/Inject
            Primitive Obsession   | String/double as domain concept| Value Objects (record)
            Anemic Domain Model   | Setter beans + procedure svc   | Rich Entity + App Service
            Magic Numbers         | Literal 0.1, 3, "ADMIN", 5000  | Enum + Named Constant

            SA Decision Rule:
              → Can you test this class in isolation without setting up global state? NO → Refactor
              → Does the class name describe ONE thing clearly? NO → Split
              → Are domain rules in ONE place? NO → Domain Model enrichment needed
              → Does the type system prevent wrong usage? NO → Value Object needed
            """);
    }

    // ──────────── Magic Numbers — Supporting ────────────

    enum UserRole {
        ADMIN("Administrator"), EDITOR("Content Editor"), VIEWER("Read Only");
        private final String displayName;
        UserRole(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    enum OrderStatus {
        DRAFT(0, false), AWAITING_PAYMENT(1, true),
        PROCESSING(2, false), SHIPPED(3, false), DELIVERED(4, false), CANCELLED(5, false);

        private final int code;
        private final boolean requiresPayment;

        OrderStatus(int code, boolean requiresPayment) {
            this.code = code; this.requiresPayment = requiresPayment;
        }
        public int code()              { return code; }
        public boolean requiresPayment() { return requiresPayment; }
    }

    record RetryPolicy(int maxAttempts, long backoffMs, int maxBackoffMs) {
        static final RetryPolicy DEFAULT    = new RetryPolicy(3, 500, 5_000);
        static final RetryPolicy AGGRESSIVE = new RetryPolicy(5, 200, 2_000);
        static final RetryPolicy LENIENT    = new RetryPolicy(10, 1_000, 30_000);
    }

    enum TaxRate {
        VAT_STANDARD(new BigDecimal("0.10"), "VAT 10%"),
        VAT_REDUCED(new BigDecimal("0.05"),  "VAT 5% (reduced)"),
        IMPORT_DUTY(new BigDecimal("0.25"),  "Import Duty 25%");

        private final BigDecimal rate;
        private final String description;

        TaxRate(BigDecimal rate, String description) {
            this.rate = rate; this.description = description;
        }

        public Money apply(Money base) {
            BigDecimal taxAmount = base.amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return Money.of(taxAmount, base.currency);
        }

        @Override public String toString() { return description; }
    }
}
